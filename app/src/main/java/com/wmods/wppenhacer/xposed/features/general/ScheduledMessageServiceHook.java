package com.wmods.wppenhacer.xposed.features.general;

import android.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.IntentFilter;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.wmods.wppenhacer.xposed.bridge.WhatsAppKeepAlive;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ScheduledMessageServiceHook extends Feature {
    public static final String ACTION_STOP_SCHEDULED_SERVICE = "com.wmods.wppenhacer.STOP_SCHEDULED_SERVICE";
    public static final String ACTION_SCHEDULED_MESSAGE = "com.wmods.wppenhacer.SCHEDULED_MESSAGE";
    
    private static Service instrumentationServiceInstance = null;
    
    // Sender Objects
    private static Object mMediaActionUser;
    private static Method mMediaActionUserMethod;
    private static Object mUserActionSend;
    private static Method userActionSendMethod;

    public ScheduledMessageServiceHook(ClassLoader classLoader, XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override // com.wmods.wppenhacer.xposed.core.Feature
    public void doHook() {
        hookInstrumentationService();
        registerStopReceiver();
        registerScheduledMessageReceiver();
        initSenders();
    }

    private void initSenders() {
        try {
            // Text Sender
            userActionSendMethod = Unobfuscator.loadSendTextUserAction(classLoader);
            if (userActionSendMethod != null) {
                // Hook constructors to capture instance? Or just instantiate on demand.
                XposedBridge.hookAllConstructors(userActionSendMethod.getDeclaringClass(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mUserActionSend = param.thisObject;
                    }
                });
            }

            // Media Sender
            mMediaActionUserMethod = Unobfuscator.loadSendMediaUserAction(classLoader);
            if (mMediaActionUserMethod != null) {
                XposedBridge.hookAllConstructors(mMediaActionUserMethod.getDeclaringClass(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mMediaActionUser = param.thisObject;
                    }
                });
            }

        } catch (Exception e) {
            XposedBridge.log("SC: Failed to init Senders: " + e.getMessage());
        }
    }
    
    private Object getUserTextSendAction() {
        if (mUserActionSend == null && userActionSendMethod != null) {
            try {
                mUserActionSend = userActionSendMethod.getDeclaringClass().getConstructors()[0].newInstance();
            } catch (Exception e) { XposedBridge.log(e); }
        }
         return mUserActionSend;
    }

    private Object getMediaSendAction() {
        if (mMediaActionUser == null && mMediaActionUserMethod != null) {
             try {
                mMediaActionUser = mMediaActionUserMethod.getDeclaringClass().getConstructors()[0].newInstance();
            } catch (Exception e) { XposedBridge.log(e); }
        }
        return mMediaActionUser;
    }
    
    // --- Hook Service ---
    
    private void hookInstrumentationService() {
        Class cls = Integer.TYPE;
        try {
            XposedHelpers.findAndHookMethod(Service.class, "onStartCommand", new Object[]{Intent.class, cls, cls, new XC_MethodHook() { 
                public void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Intent intent;
                    String serviceName = param.thisObject.getClass().getName();
                    if (!serviceName.endsWith("InstrumentationService") || (intent = (Intent) param.args[0]) == null || !intent.getBooleanExtra(WhatsAppKeepAlive.EXTRA_FROM_KEEPALIVE, false)) {
                        return;
                    }
                    Service serviceInstance = (Service) param.thisObject;
                    NotificationManager notificationManager = (NotificationManager) serviceInstance.getSystemService("notification");
                    NotificationChannel channel = new NotificationChannel("wae_scheduled_channel", "Scheduled Messages", 2);
                    channel.setDescription("Keeps WhatsApp running for scheduled messages");
                    channel.setShowBadge(false);
                    notificationManager.createNotificationChannel(channel);
                    Notification notification = new Notification.Builder(serviceInstance, "wae_scheduled_channel").setContentTitle("WhatsApp").setContentText("Running for scheduled messages").setSmallIcon(R.drawable.ic_dialog_info).setOngoing(true).build();
                    serviceInstance.startForeground(9999, notification);
                    ScheduledMessageServiceHook.instrumentationServiceInstance = serviceInstance;
                    ScheduledMessageServiceHook.this.logDebug("InstrumentationService started in foreground mode");
                }
            }});
        } catch (Exception e) {
            log("Error hooking InstrumentationService: " + e.getMessage());
        }
    }

    private void registerStopReceiver() {
        BroadcastReceiver stopScheduledServiceReceiver = new BroadcastReceiver() { 
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                ScheduledMessageServiceHook.stopScheduledService();
            }
        };
        ContextCompat.registerReceiver(FeatureLoader.mApp, stopScheduledServiceReceiver, new IntentFilter(ACTION_STOP_SCHEDULED_SERVICE), 2);
    }
    
    // --- Scheduled Message Receiver ---

    private void registerScheduledMessageReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_SCHEDULED_MESSAGE.equals(intent.getAction())) return;

                String jid = intent.getStringExtra("jid");
                String message = intent.getStringExtra("message");
                String mediaPath = intent.getStringExtra("media_path"); // Optional
                long id = intent.getLongExtra("id", -1);
                
                if (TextUtils.isEmpty(jid)) return;
                
                boolean success = false;
                File mediaFile = null;

                if (!TextUtils.isEmpty(mediaPath)) {
                    File f = new File(mediaPath);
                    if (f.exists()) {
                         // Copy to cache to ensure WhatsApp can read it
                         mediaFile = copyImageToWhatsAppCache(context, f);
                         if (mediaFile == null && f.canRead()) mediaFile = f; // Fallback
                    }
                }

                if (mediaFile != null) {
                    // Send Media (and Caption)
                    XposedBridge.log("SC: Sending media to " + jid + " file=" + mediaFile.getAbsolutePath());
                    success = sendImageMessage(jid, message != null ? message : "", mediaFile);
                } else if (!TextUtils.isEmpty(message)) {
                    // Send Text
                    XposedBridge.log("SC: Sending text to " + jid);
                    success = sendTextMessage(jid, message);
                }

                // Notify UI/App that it's done
                Intent resultIntent = new Intent("com.wmods.wppenhacer.SCHEDULED_MESSAGE_SENT");
                resultIntent.putExtra("id", id);
                resultIntent.putExtra("success", success);
                resultIntent.setPackage("com.wmods.wppenhacer"); // Target the main app
                context.sendBroadcast(resultIntent);
            }
        };
        
        ContextCompat.registerReceiver(FeatureLoader.mApp, receiver, new IntentFilter(ACTION_SCHEDULED_MESSAGE), 2);
    }

    // --- Sending Logic ---

    public boolean sendTextMessage(String jid, String text) {
        try {
            Object sender = getUserTextSendAction();
            if (sender != null && userActionSendMethod != null) {
                 Class<?>[] paramTypes = userActionSendMethod.getParameterTypes();
                 Object[] args = new Object[paramTypes.length];
                 // Basic robust arg filling
                 for(int i=0; i<paramTypes.length; i++) {
                     if(paramTypes[i] == List.class) args[i] = Collections.singletonList(new FMessageWpp.UserJid(jid).userJid);
                     else if(paramTypes[i] == String.class) args[i] = text;
                 }
                 userActionSendMethod.invoke(sender, args);
                 return true;
            }
            XposedBridge.log("SC: Sender is null or method null for text");
            return false;
        } catch(Exception e) {
            XposedBridge.log("SC: Failed to send text: " + e.getMessage());
            return false;
        }
    }

    public boolean sendImageMessage(String jid, String caption, File file) {
        try {
            Object sender = getMediaSendAction();
            if (sender != null && mMediaActionUserMethod != null) {
                 Class<?>[] paramTypes = mMediaActionUserMethod.getParameterTypes();
                 Object[] args = new Object[paramTypes.length];
                  for(int i=0; i<paramTypes.length; i++) {
                     if(paramTypes[i] == List.class) args[i] = Collections.singletonList(new FMessageWpp.UserJid(jid).userJid);
                     else if(paramTypes[i] == String.class) args[i] = caption;
                     else if(paramTypes[i] == File.class) args[i] = file;
                     else if(paramTypes[i] == Uri.class) args[i] = Uri.fromFile(file);
                 }
                 mMediaActionUserMethod.invoke(sender, args);
                 return true;
            }
             XposedBridge.log("SC: Sender is null or method null for media");
            return false;
        } catch(Exception e) {
            XposedBridge.log("SC: Failed to send media: " + e.getMessage());
            return false;
        }
    }

    private File copyImageToWhatsAppCache(Context context, File file) {
        // Copy to App's external picture directory or private cache
        try {
            File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WaEnhancer");
            if (!destDir.exists()) destDir.mkdirs();
            
            File destFile = new File(destDir, "SCH_" + System.currentTimeMillis() + "_" + file.getName());
            
            try (FileInputStream in = new FileInputStream(file);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return destFile;
        } catch (IOException e) {
            XposedBridge.log("SC: Failed to copy image: " + e.getMessage());
            return null;
        }
    }

    public static void stopScheduledService() {
        if (instrumentationServiceInstance != null) {
            instrumentationServiceInstance.stopForeground(true);
            instrumentationServiceInstance.stopSelf();
            instrumentationServiceInstance = null;
        }
    }

    public static boolean isServiceRunning() {
        return instrumentationServiceInstance != null;
    }

    @Override // com.wmods.wppenhacer.xposed.core.Feature
    public String getPluginName() {
        return "Scheduled Message Manager";
    }
}