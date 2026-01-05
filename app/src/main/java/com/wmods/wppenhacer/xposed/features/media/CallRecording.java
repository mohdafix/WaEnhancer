package com.wmods.wppenhacer.xposed.features.media;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CallRecording extends Feature {

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isCallConnected = new AtomicBoolean(false);
    private MediaRecorder mMediaRecorder;
    private Uri mFileUri;
    private String mOutputPath;
    private volatile String currentContactName = "Unknown";
    
    public CallRecording(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("call_recording_enable", false)) return;
        XposedBridge.log("WaEnhancer: Call Recording feature active");
        hookCallStateChanges();
    }

    public static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !lpparam.packageName.equals("android")) return;
        try {
            Class<?> pmClass = XposedHelpers.findClassIfExists("com.android.server.pm.permission.PermissionManagerServiceImpl", lpparam.classLoader);
            if (pmClass == null) pmClass = XposedHelpers.findClassIfExists("com.android.server.pm.permission.PermissionManagerService", lpparam.classLoader);

            if (pmClass != null) {
                XposedBridge.hookAllMethods(pmClass, "checkPermission", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 2 && "com.whatsapp".equals(param.args[1])) {
                            String permission = (String) param.args[0];
                            if (permission.contains("RECORD_AUDIO") || permission.contains("CAPTURE_AUDIO_OUTPUT")) {
                                param.setResult(PackageManager.PERMISSION_GRANTED);
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private void hookCallStateChanges() {
        try {
            var clsCallEventCallback = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "VoiceServiceEventCallback");
            if (clsCallEventCallback != null) {
                XposedBridge.hookAllMethods(clsCallEventCallback, "soundPortCreated", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object callInfo = XposedHelpers.callMethod(param.thisObject, "getCallInfo");
                        if (callInfo != null) {
                            // Safe check for isVideoCall using dynamic field search
                            boolean isVideo = false;
                            try {
                                isVideo = (boolean) XposedHelpers.callMethod(callInfo, "isVideoCall");
                            } catch (Throwable t) {
                                try { isVideo = XposedHelpers.getBooleanField(callInfo, "isVideoCall"); } catch (Throwable ignored) {}
                            }
                            if (isVideo) return;
                        }

                        isCallConnected.set(true);
                        extractContactInfo(param.thisObject);
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isCallConnected.get()) startRecording();
                        }, 2500);
                    }
                });

                XposedBridge.hookAllMethods(clsCallEventCallback, "fieldstatsReady", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        isCallConnected.set(false);
                        stopRecording();
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private void extractContactInfo(Object callback) {
        try {
            Object callInfo = XposedHelpers.callMethod(callback, "getCallInfo");
            Object peerJid = XposedHelpers.getObjectField(callInfo, "peerJid");
            if (peerJid != null) {
                FMessageWpp.UserJid userJid = new FMessageWpp.UserJid(peerJid);
                String realName = WppCore.getContactName(userJid);
                currentContactName = (realName != null && !realName.isEmpty() && !realName.equals("Whatsapp Contact")) 
                    ? realName : userJid.getPhoneNumber();
                XposedBridge.log("WaEnhancer: Recording contact: " + currentContactName);
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void startRecording() {
        if (isRecording.get()) return;

        // Re-ordered sources: 4=VOICE_CALL, 9=UNPROCESSED (Cleaner), 7=VOICE_COMMUNICATION, 6=VOICE_RECOGNITION, 1=MIC
        int[] sources = prefs.getBoolean("call_recording_use_root", false)
                ? new int[]{6, 9, 4, 1}
                : new int[]{9, 1};

        for (int source : sources) {
            try {
                mMediaRecorder = new MediaRecorder();
                mMediaRecorder.setAudioSource(source);
                mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mMediaRecorder.setAudioSamplingRate(16000);
                mMediaRecorder.setAudioEncodingBitRate(128000);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String fileName = "Call_" + currentContactName.replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + timestamp + ".m4a";

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/WaEnhancer/Recordings");
                    mFileUri = FeatureLoader.mApp.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                    mMediaRecorder.setOutputFile(FeatureLoader.mApp.getContentResolver().openFileDescriptor(mFileUri, "rw").getFileDescriptor());
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WaEnhancer/Recordings");
                    if (!dir.exists()) dir.mkdirs();
                    mOutputPath = new File(dir, fileName).getAbsolutePath();
                    mMediaRecorder.setOutputFile(mOutputPath);
                }

                mMediaRecorder.prepare();
                mMediaRecorder.start();
                isRecording.set(true);
                XposedBridge.log("WaEnhancer: M4A Recording started using source: " + source);

                if (prefs.getBoolean("call_recording_toast", true)) {
                    Utils.showToast("Call recording started", 0);
                }
                return; // Successfully started
            } catch (Exception e) {
                XposedBridge.log("WaEnhancer: Source " + source + " failed: " + e.getMessage());
                if (mMediaRecorder != null) {
                    mMediaRecorder.release();
                    mMediaRecorder = null;
                }
            }
        }
        XposedBridge.log("WaEnhancer: All audio sources unavailable");
    }

    private synchronized void stopRecording() {
        if (!isRecording.get() || mMediaRecorder == null) return;
        isRecording.set(false);
        try {
            mMediaRecorder.stop();
            mMediaRecorder.release();
            mMediaRecorder = null;
            XposedBridge.log("WaEnhancer: M4A Recording saved");
        } catch (Exception ignored) {}
        currentContactName = "Unknown";
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Call Recording";
    }
}
