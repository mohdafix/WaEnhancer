package com.wmods.wppenhacer.xposed.features.media;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
    private AudioRecord audioRecord;
    private OutputStream outputStream;
    private File tempPcmFile;
    private Thread recordingThread;
    private int payloadSize = 0;
    private volatile String currentContactName = "Unknown";
    private static boolean permissionGranted = false;
    
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final short CHANNELS = 1;
    private static final short BITS_PER_SAMPLE = 16;

    public CallRecording(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("call_recording_enable", false)) {
            XposedBridge.log("WaEnhancer: Call Recording is disabled");
            return;
        }
        
        XposedBridge.log("WaEnhancer: Call Recording feature initializing...");
        hookCallStateChanges();
    }

    /**
     * More aggressive system-level hook to bypass hardware source restrictions.
     */
    public static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !lpparam.packageName.equals("android")) return;

        try {
            // Hooking the PermissionPolicyService is more effective on modern Android for CAPTURE_AUDIO_OUTPUT
            Class<?> ppsClass = XposedHelpers.findClassIfExists("com.android.server.policy.PermissionPolicyService", lpparam.classLoader);
            if (ppsClass != null) {
                XposedBridge.hookAllMethods(ppsClass, "checkPermission", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 2 && ("com.whatsapp".equals(param.args[1]) || "com.whatsapp.w4b".equals(param.args[1]))) {
                            String perm = (String) param.args[0];
                            if (perm.contains("CAPTURE_AUDIO_OUTPUT") || perm.contains("RECORD_AUDIO")) {
                                param.setResult(PackageManager.PERMISSION_GRANTED);
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("WaEnhancer: System Audio hook failed: " + t.getMessage());
        }
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
                            try {
                                boolean isVideoCall = XposedHelpers.getBooleanField(callInfo, "isVideoCall");
                                if (isVideoCall) {
                                    XposedBridge.log("WaEnhancer: Video call detected, skipping audio recorder");
                                    return;
                                }
                            } catch (Throwable ignored) {}
                        }

                        isCallConnected.set(true);
                        extractContactInfo(param.thisObject);
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000);
                                if (isCallConnected.get() && !isRecording.get()) startRecording();
                            } catch (Exception ignored) {}
                        }).start();
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
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: Error hooking VoiceServiceEventCallback: " + e);
        }

        try {
            var voipActivityClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.Contains, "VoipActivity");
            if (voipActivityClass != null) {
                XposedHelpers.findAndHookMethod(voipActivityClass, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
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
            if (callInfo == null) return;
            
            Object peerJid = XposedHelpers.getObjectField(callInfo, "peerJid");
            if (peerJid != null) {
                FMessageWpp.UserJid userJid = new FMessageWpp.UserJid(peerJid);
                String realName = WppCore.getContactName(userJid);
                if (realName != null && !realName.isEmpty() && !realName.equals("Whatsapp Contact")) {
                    currentContactName = realName;
                } else {
                    currentContactName = userJid.getPhoneNumber();
                    if (currentContactName == null) {
                        String raw = userJid.getUserRawString();
                        if (raw != null && raw.contains("@")) {
                            currentContactName = raw.split("@")[0];
                        }
                    }
                }
                XposedBridge.log("WaEnhancer: Recording for contact: " + currentContactName);
            }
        } catch (Throwable e) {
            XposedBridge.log("WaEnhancer: extractContactInfo error: " + e.getMessage());
        }
    }

    private void grantPermissionsViaRoot() {
        if (permissionGranted) return;
        new Thread(() -> {
            try {
                String pkg = FeatureLoader.mApp.getPackageName();
                XposedBridge.log("WaEnhancer: Attempting root permission grant for " + pkg);
                Process p = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
                os.writeBytes("pm grant " + pkg + " android.permission.CAPTURE_AUDIO_OUTPUT\n");
                os.writeBytes("appops set " + pkg + " RECORD_AUDIO allow\n");
                os.writeBytes("appops set " + pkg + " CAPTURE_AUDIO_OUTPUT allow\n");
                os.writeBytes("exit\n");
                os.flush();
                int result = p.waitFor();
                permissionGranted = (result == 0);
            } catch (Exception e) {
                XposedBridge.log("WaEnhancer: Root grant failed: " + e.getMessage());
            }
        }).start();
    }

    private synchronized void startRecording() {
        if (isRecording.get()) return;
        
        try {
            if (prefs.getBoolean("call_recording_use_root", false)) {
                grantPermissionsViaRoot();
            }

            tempPcmFile = new File(FeatureLoader.mApp.getCacheDir(), "call_temp.pcm");
            outputStream = new FileOutputStream(tempPcmFile);
            
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            int bufferSize = Math.max(minBufferSize, 2048) * 8; // Further increased buffer
            
            // Re-ordered sources: 4=VOICE_CALL, 9=UNPROCESSED (Cleaner), 7=VOICE_COMMUNICATION, 6=VOICE_RECOGNITION, 1=MIC
            int[] sources = prefs.getBoolean("call_recording_use_root", false)
                    ? new int[]{4, 9, 7, 6, 1} 
                    : new int[]{7, 9, 1};

            audioRecord = null;
            for (int source : sources) {
                try {
                    if (ContextCompat.checkSelfPermission(FeatureLoader.mApp, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        continue;
                    }
                    
                    // Specific fix for Source 4: try lower sample rate if 44.1k fails
                    int rate = (source == 4) ? 16000 : SAMPLE_RATE;
                    int bSize = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT) * 4;

                    XposedBridge.log("WaEnhancer: Trying audio source " + source + " at " + rate + "Hz");
                    AudioRecord ar = new AudioRecord(source, rate, CHANNEL_CONFIG, AUDIO_FORMAT, bSize);
                    if (ar.getState() == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = ar;
                        XposedBridge.log("WaEnhancer: SUCCESS Source " + source);
                        break;
                    }
                    ar.release();
                } catch (Throwable t) {
                    XposedBridge.log("WaEnhancer: Source " + source + " failed: " + t.getMessage());
                }
            }

            if (audioRecord == null) {
                XposedBridge.log("WaEnhancer: All audio sources unavailable");
                return;
            }

            audioRecord.startRecording();
            isRecording.set(true);
            payloadSize = 0;
            
            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                try {
                    while (isRecording.get() && audioRecord != null) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            outputStream.write(buffer, 0, read);
                            payloadSize += read;
                        } else if (read < 0) {
                            XposedBridge.log("WaEnhancer: AudioRecord read error: " + read);
                            break;
                        }
                    }
                } catch (IOException e) {
                    XposedBridge.log("WaEnhancer: Recording thread IO error: " + e.getMessage());
                }
            });
            recordingThread.start();
            
            if (prefs.getBoolean("call_recording_toast", true)) {
                Utils.showToast("Call recording started", 0);
            }
            
        } catch (Exception ignored) {}
    }

    private synchronized void stopRecording() {
        if (!isRecording.get()) return;
        isRecording.set(false);
        
        try {
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                audioRecord.release();
                audioRecord = null;
            }
            
            if (recordingThread != null) {
                recordingThread.join(1000);
                recordingThread = null;
            }
            
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }

            if (payloadSize > 0) {
                saveFinalWavFile();
            } else if (tempPcmFile != null) {
                tempPcmFile.delete();
            }
            
        } catch (Exception ignored) {}
    }

    private void saveFinalWavFile() {
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String sanitizedName = currentContactName.replaceAll("[\\\\/:*?\"<>|]", "_");
            String fileName = "Call_" + sanitizedName + "_" + timestamp + ".wav";
            
            XposedBridge.log("WaEnhancer: Saving final WAV file: " + fileName);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/WaEnhancer/Recordings");
                
                Uri finalUri = FeatureLoader.mApp.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
                if (finalUri != null) {
                    try (OutputStream os = FeatureLoader.mApp.getContentResolver().openOutputStream(finalUri)) {
                        writeWavHeader(os, payloadSize);
                        java.nio.file.Files.copy(tempPcmFile.toPath(), os);
                    }
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WaEnhancer/Recordings");
                if (!dir.exists()) dir.mkdirs();
                File wavFile = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(wavFile)) {
                    writeWavHeader(fos, payloadSize);
                    java.nio.file.Files.copy(tempPcmFile.toPath(), fos);
                }
            }

            if (prefs.getBoolean("call_recording_toast", true)) {
                Utils.showToast("Call recording saved: " + sanitizedName, 0);
            }
            
            if (tempPcmFile != null) tempPcmFile.delete();
            currentContactName = "Unknown";
            
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: saveFinalWavFile Error: " + e.getMessage());
        }
    }

    private void writeWavHeader(OutputStream out, int pcmLen) throws IOException {
        long totalDataLen = pcmLen + 36;
        long byteRate = (long) SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) CHANNELS; header[23] = 0;
        header[24] = (byte) (SAMPLE_RATE & 0xff);
        header[25] = (byte) ((SAMPLE_RATE >> 8) & 0xff);
        header[26] = (byte) ((SAMPLE_RATE >> 16) & 0xff);
        header[27] = (byte) ((SAMPLE_RATE >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (CHANNELS * BITS_PER_SAMPLE / 8); header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmLen & 0xff);
        header[41] = (byte) ((pcmLen >> 8) & 0xff);
        header[42] = (byte) ((pcmLen >> 16) & 0xff);
        header[43] = (byte) ((pcmLen >> 24) & 0xff);

        out.write(header);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Call Recording";
    }
}
