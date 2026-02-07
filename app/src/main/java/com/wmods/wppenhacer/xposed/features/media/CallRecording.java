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
import android.os.ParcelFileDescriptor; // Ensure this import is present
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.io.File;
import java.io.IOException; // Required for ParcelFileDescriptor operations
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
                            boolean isVideo = false;
                            try {
                                isVideo = (boolean) XposedHelpers.callMethod(callInfo, "isVideoCall");
                            } catch (Throwable t) {
                                try {
                                    isVideo = XposedHelpers.getBooleanField(callInfo, "videoEnabled");
                                } catch (Throwable ignored) {
                                    try {
                                        isVideo = XposedHelpers.getBooleanField(callInfo, "isVideoCall");
                                    } catch (Throwable ignored2) {}
                                }
                            }
                            if (isVideo) {
                                XposedBridge.log("WaEnhancer: CallRecording - Video call detected, skipping audio recording");
                                return;
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
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void startRecording() {
        if (isRecording.get() || !isCallConnected.get()) {
            return;
        }

        int[] sources = prefs.getBoolean("call_recording_use_root", false)
                ? new int[]{6, 9, 4, 1}
                : new int[]{9, 1};

        for (int source : sources) {
            ParcelFileDescriptor pfd = null; // Defined as android.os.ParcelFileDescriptor
            try {
                if (!isCallConnected.get()) break;

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
                    values.put(MediaStore.Audio.Media.IS_PENDING, 1);

                    mFileUri = FeatureLoader.mApp.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                    if (mFileUri != null) {
                        // Open the descriptor
                        pfd = FeatureLoader.mApp.getContentResolver().openFileDescriptor(mFileUri, "rw");
                        if (pfd != null) {
                            // Correct method call for ParcelFileDescriptor
                            mMediaRecorder.setOutputFile(pfd.getFileDescriptor());
                        }
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WaEnhancer/Recordings");
                    if (!dir.exists()) dir.mkdirs();
                    mMediaRecorder.setOutputFile(new File(dir, fileName).getAbsolutePath());
                }

                mMediaRecorder.prepare();

                if (!isCallConnected.get()) {
                    throw new Exception("Call ended immediately");
                }

                mMediaRecorder.start();
                isRecording.set(true);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mFileUri != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    FeatureLoader.mApp.getContentResolver().update(mFileUri, values, null, null);
                }

                if (prefs.getBoolean("call_recording_toast", true)) {
                    Utils.showToast("Call recording started", 0);
                }
                return;
            } catch (Exception e) {
                XposedBridge.log("WaEnhancer: Recording source " + source + " failed: " + e.getMessage());

                if (mMediaRecorder != null) {
                    try { mMediaRecorder.release(); } catch (Exception ignored) {}
                    mMediaRecorder = null;
                }

                // Close the file descriptor if it was opened
                if (pfd != null) {
                    try {
                        // ParcelFileDescriptor.close() is valid
                        pfd.close();
                    } catch (Exception ignored) {}
                }

                if (!isCallConnected.get()) {
                    break;
                }
            }
        }
    }

    private synchronized void stopRecording() {
        if (mMediaRecorder == null || !isRecording.compareAndSet(true, false)) {
            return;
        }

        try {
            mMediaRecorder.stop();
            XposedBridge.log("WaEnhancer: Call Recording saved");
        } catch (RuntimeException e) {
            XposedBridge.log("WaEnhancer: Call recording stopped with no data. Message: " + e.getMessage());
        } finally {
            try {
                mMediaRecorder.release();
            } catch (Exception e) {
                XposedBridge.log("WaEnhancer: Error releasing media recorder: " + e.getMessage());
            }
            mMediaRecorder = null;
            currentContactName = "Unknown";
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Call Recording";
    }
}
