package com.wmods.wppenhacer.xposed.features.general;

import android.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import com.wmods.wppenhacer.xposed.bridge.WhatsAppKeepAlive;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

public class ScheduledMessageServiceHook extends Feature {
    public static final String ACTION_STOP_SCHEDULED_SERVICE = "com.wmods.wppenhacer.STOP_SCHEDULED_SERVICE";
    private static Service instrumentationServiceInstance = null;

    public ScheduledMessageServiceHook(ClassLoader classLoader, XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override // com.wmods.wppenhacer.xposed.core.Feature
    public void doHook() {
        hookInstrumentationService();
        registerStopReceiver();
    }

    private void hookInstrumentationService() {
        Class cls = Integer.TYPE;
        try {
            XposedHelpers.findAndHookMethod(Service.class, "onStartCommand", new Object[]{Intent.class, cls, cls, new XC_MethodHook() { // from class: com.wmods.wppenhacer.xposed.features.general.ScheduledMessageServiceHook.1
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
        BroadcastReceiver stopScheduledServiceReceiver = new BroadcastReceiver() { // from class: com.wmods.wppenhacer.xposed.features.general.ScheduledMessageServiceHook.2
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context, Intent intent) {
                ScheduledMessageServiceHook.stopScheduledService();
            }
        };
        ContextCompat.registerReceiver(FeatureLoader.mApp, stopScheduledServiceReceiver, new IntentFilter(ACTION_STOP_SCHEDULED_SERVICE), 2);
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
        return "Scheduled Message Service Hook";
    }
}