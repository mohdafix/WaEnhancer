package com.wmods.wppenhacer.xposed.features.others;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Voice Changer Feature
 * 
 * Intercepts voice recordings and applies audio effects (pitch shift, tempo
 * change)
 * before WhatsApp sends them.
 * 
 * Hook Strategy:
 * 1. Hook OpusRecorder constructor to capture the output file path
 * 2. Hook OpusRecorder.stop() to trigger voice processing after recording
 * 3. Process the opus file: decode -> apply effects -> re-encode
 * 4. Replace the original file with the processed version
 */
public class VoiceChanger extends Feature {

    private static final String TAG = "VoiceChanger";

    // Voice effect types (matching C++ enum)
    public static final int EFFECT_DISABLED = 0;
    public static final int EFFECT_BABY = 1;
    public static final int EFFECT_TEENAGER = 2;
    public static final int EFFECT_DEEP = 3;
    public static final int EFFECT_ROBOT = 4;
    public static final int EFFECT_DRUNK = 5;
    public static final int EFFECT_FAST = 6;
    public static final int EFFECT_SLOW_MOTION = 7;
    public static final int EFFECT_UNDERWATER = 8;
    public static final int EFFECT_FUN = 9;

    // Effect names for UI display (matching arrays.xml voice_effect_entries)
    public static final String[] EFFECT_NAMES = {
            "Disabled", "Baby", "Teenager", "Deep", "Robot",
            "Drunk", "Fast", "Slow Motion", "Underwater", "Fun"
    };

    // Menu item ID for conversation menu
    private static final int MENU_ITEM_ID = 0x57A22;

    // Current effect (stored for dynamic updates)
    private static int currentEffect = EFFECT_DISABLED;

    // Current recording file path (captured from constructor)
    private static String currentRecordingPath = null;

    // Track if we've processed the current recording
    private static boolean recordingProcessed = false;

    // Executor for background processing
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Handler for main thread callbacks
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Native library loaded flag
    private static boolean nativeLoaded = false;

    // Static block to load native library
    static {
        try {
            System.loadLibrary("voicechanger");
            nativeLoaded = true;
            nativeInit();
            XposedBridge.log("WaEnhancer: VoiceChanger native library loaded");
        } catch (UnsatisfiedLinkError e) {
            XposedBridge.log("WaEnhancer: Failed to load voicechanger library: " + e.getMessage());
            nativeLoaded = false;
        }
    }

    // Native methods
    private static native boolean nativeInit();

    private static native void nativeRelease();

    private static native void nativeSetEffect(int effectType);

    private static native void nativeSetCustomParams(float tempo, float pitch, float speed);

    private static native short[] nativeProcessAudio(short[] input, int sampleRate);

    private static native boolean nativeIsEnabled();

    public VoiceChanger(ClassLoader classLoader, XSharedPreferences xSharedPreferences) {
        super(classLoader, xSharedPreferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("voice_changer_enabled", false)) {
            log("Voice Changer is disabled in preferences");
            return;
        }

        if (!nativeLoaded) {
            log("Voice Changer native library not loaded, feature disabled");
            return;
        }

        // Load the selected effect from preferences (or from runtime storage)
        String storedEffect = WppCore.getPrivString("voice_changer_current_effect", null);
        if (storedEffect != null) {
            currentEffect = Integer.parseInt(storedEffect);
        } else {
            currentEffect = Integer.parseInt(prefs.getString("voice_changer_effect", "0"));
        }
        nativeSetEffect(currentEffect);
        log("Voice Changer initialized with effect: " + currentEffect + " (" + getEffectName(currentEffect) + ")");

        hookOpusRecorder();
        hookConversationMenu();
    }

    /**
     * Get the effect name for display
     */
    public static String getEffectName(int effectType) {
        if (effectType >= 0 && effectType < EFFECT_NAMES.length) {
            return EFFECT_NAMES[effectType];
        }
        return "Unknown";
    }

    /**
     * Set the voice effect dynamically (can be called from UI)
     */
    public static void setEffect(int effectType) {
        if (effectType >= 0 && effectType < EFFECT_NAMES.length) {
            currentEffect = effectType;
            if (nativeLoaded) {
                nativeSetEffect(effectType);
            }
            // Store in runtime preferences so it persists during session
            WppCore.setPrivString("voice_changer_current_effect", String.valueOf(effectType));
            XposedBridge.log("WaEnhancer: Voice Changer effect set to: " + getEffectName(effectType));
        }
    }

    /**
     * Get the current effect type
     */
    public static int getCurrentEffect() {
        return currentEffect;
    }

    /**
     * Hook the conversation menu to add voice changer quick access
     */
    private void hookConversationMenu() throws Exception {
        var onCreateMenuConversationMethod = Unobfuscator.loadBlueOnReplayCreateMenuConversationMethod(classLoader);
        if (onCreateMenuConversationMethod == null) {
            log("Could not find conversation menu method, skipping UI integration");
            return;
        }

        XposedBridge.hookMethod(onCreateMenuConversationMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var menu = (Menu) param.args[0];
                var activity = WppCore.getCurrentConversation();
                if (activity == null)
                    return;
                addVoiceChangerMenuItem(menu, activity);
            }
        });
        log("Voice Changer conversation menu hook installed");
    }

    /**
     * Add voice changer menu item to conversation menu
     */
    private void addVoiceChangerMenuItem(Menu menu, Activity activity) {
        if (menu.findItem(MENU_ITEM_ID) != null)
            return;

        String title = "Voice: " + getEffectName(currentEffect);
        MenuItem item = menu.add(0, MENU_ITEM_ID, 0, title);

        // Try to use a microphone icon
        try {
            var iconDraw = DesignUtils.getDrawableByName("ic_microphone");
            if (iconDraw == null) {
                iconDraw = activity.getDrawable(android.R.drawable.ic_btn_speak_now);
            }
            if (iconDraw != null) {
                iconDraw.setTint(DesignUtils.getPrimaryTextColor());
                item.setIcon(iconDraw);
            }
        } catch (Throwable ignored) {
        }

        // Show in action bar if space available
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        item.setOnMenuItemClickListener(mi -> {
            showVoiceChangerDialog(activity, item);
            return true;
        });
    }

    /**
     * Show dialog to select voice effect
     */
    private void showVoiceChangerDialog(Activity activity, MenuItem menuItem) {
        try {
            AlertDialogWpp dialog = new AlertDialogWpp(activity);
            dialog.setTitle("Voice Changer");

            // Create list of effect options with current selection indicator
            String[] displayNames = new String[EFFECT_NAMES.length];
            for (int i = 0; i < EFFECT_NAMES.length; i++) {
                displayNames[i] = (i == currentEffect ? "✓ " : "   ") + EFFECT_NAMES[i];
            }

            dialog.setItems(displayNames, (d, which) -> {
                setEffect(which);
                Utils.showToast("Voice effect: " + getEffectName(which), Toast.LENGTH_SHORT);

                // Update menu item title
                if (menuItem != null) {
                    menuItem.setTitle("Voice: " + getEffectName(which));
                }
            });

            dialog.setNegativeButton("Cancel", null);
            dialog.show();
        } catch (Throwable e) {
            log("Error showing voice changer dialog: " + e.getMessage());
            XposedBridge.log(e);
        }
    }

    /**
     * Hook the OpusRecorder class to intercept voice recordings
     */
    private void hookOpusRecorder() throws Exception {
        // Find the OpusRecorder class
        Class<?> opusRecorderClass = Unobfuscator.loadOpusRecorderClass(classLoader);

        if (opusRecorderClass == null) {
            log("Could not find OpusRecorder class");
            return;
        }

        log("Found OpusRecorder class: " + opusRecorderClass.getName());

        // Log all methods in the class for debugging
        log("OpusRecorder methods:");
        for (Method m : opusRecorderClass.getDeclaredMethods()) {
            log("  - " + m.getName() + "(" + java.util.Arrays.toString(m.getParameterTypes()) + ") -> "
                    + m.getReturnType().getSimpleName());
        }

        // Hook the constructor to capture the file path
        // Constructor signature: OpusRecorder(String filePath,
        // PttNativeMetricsCallback, OpusRecorderConfig)
        XposedBridge.hookAllConstructors(opusRecorderClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                log("OpusRecorder constructor called with " + (param.args != null ? param.args.length : 0)
                        + " arguments");

                // Log all arguments for debugging
                if (param.args != null) {
                    for (int i = 0; i < param.args.length; i++) {
                        Object arg = param.args[i];
                        log("  arg[" + i + "]: " + (arg != null ? arg.getClass().getName() + " = " + arg : "null"));
                    }
                }

                // The first argument should be the file path
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                    currentRecordingPath = (String) param.args[0];
                    recordingProcessed = false;
                    log("OpusRecorder created with path: " + currentRecordingPath);
                }
            }
        });

        // Hook ALL methods that could be "stop" or end the recording
        // Try different method names that might stop recording
        String[] stopMethodNames = { "stop", "stopRecording", "finish", "close", "release" };
        boolean hookedAtLeastOne = false;

        for (String methodName : stopMethodNames) {
            for (Method method : opusRecorderClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName) ||
                        method.getName().toLowerCase().contains("stop")) {
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                log("OpusRecorder." + method.getName() + "() called");

                                if (currentRecordingPath == null) {
                                    log("No recording path captured, skipping processing");
                                    return;
                                }

                                if (recordingProcessed) {
                                    log("Recording already processed, skipping");
                                    return;
                                }

                                if (!nativeIsEnabled()) {
                                    log("Voice effect is disabled (effect=0), skipping processing");
                                    return;
                                }

                                final String filePath = currentRecordingPath;
                                recordingProcessed = true;

                                log("Recording stopped, will process: " + filePath);

                                // Process synchronously to ensure it's done before WhatsApp reads the file
                                try {
                                    processVoiceRecording(filePath);
                                } catch (Exception e) {
                                    log("Error processing voice recording: " + e.getMessage());
                                    XposedBridge.log(e);
                                }
                            }
                        });
                        log("Hooked method: " + method.getName());
                        hookedAtLeastOne = true;
                    } catch (Exception e) {
                        log("Failed to hook " + methodName + ": " + e.getMessage());
                    }
                }
            }
        }

        if (!hookedAtLeastOne) {
            // Fallback: hook all methods and log them to find the right one
            log("Could not find stop method, hooking all methods for debugging");
            for (Method method : opusRecorderClass.getDeclaredMethods()) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        log("OpusRecorder." + method.getName() + "() was called");
                    }
                });
            }
        }

        log("OpusRecorder hooks installed successfully");
    }

    /**
     * Process the voice recording with the selected effect
     */
    private void processVoiceRecording(String opusFilePath) throws Exception {
        File opusFile = new File(opusFilePath);
        if (!opusFile.exists()) {
            log("Opus file not found: " + opusFilePath);
            return;
        }

        log("Processing voice recording: " + opusFilePath);
        log("File size: " + opusFile.length() + " bytes");

        // Step 1: Decode opus to PCM
        short[] pcmData = decodeOpusToPcm(opusFile);
        if (pcmData == null || pcmData.length == 0) {
            log("Failed to decode opus file");
            return;
        }
        log("Decoded " + pcmData.length + " PCM samples");

        // Step 2: Apply voice effect via native library
        short[] processedPcm = nativeProcessAudio(pcmData, 48000); // Opus typically uses 48kHz
        if (processedPcm == null || processedPcm.length == 0) {
            log("Voice processing returned empty result");
            return;
        }
        log("Processed " + processedPcm.length + " PCM samples");

        // Step 3: Encode PCM back to opus
        File tempFile = new File(opusFile.getParent(), "voice_processed_temp.opus");
        boolean encoded = encodePcmToOpus(processedPcm, 48000, tempFile);
        if (!encoded) {
            log("Failed to encode processed audio");
            return;
        }
        log("Encoded to temp file: " + tempFile.getAbsolutePath() + " (" + tempFile.length() + " bytes)");

        // Step 4: Replace original file with processed file
        if (opusFile.delete() && tempFile.renameTo(opusFile)) {
            log("Voice recording processed successfully! Replaced original file.");
        } else {
            log("Failed to replace original file");
            // Try alternative: copy content
            try {
                copyFile(tempFile, opusFile);
                tempFile.delete();
                log("Voice recording processed successfully (via copy)");
            } catch (Exception e) {
                log("Failed to copy processed file: " + e.getMessage());
                tempFile.delete();
            }
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
                FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }
    }

    /**
     * Decode an Opus file to PCM samples using MediaCodec
     */
    private short[] decodeOpusToPcm(File opusFile) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(opusFile.getAbsolutePath());

            // Find the audio track
            int audioTrackIndex = -1;
            MediaFormat format = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                log("Track " + i + " mime: " + mime);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    format = trackFormat;
                    break;
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                log("No audio track found in opus file");
                return null;
            }

            log("Found audio track: " + format.toString());
            extractor.selectTrack(audioTrackIndex);

            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Collect decoded samples
            java.util.ArrayList<Short> samples = new java.util.ArrayList<>();
            boolean inputDone = false;
            boolean outputDone = false;
            long timeoutUs = 10000; // 10ms timeout

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize,
                                    presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // Get output
                int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
                if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }

                    if (bufferInfo.size > 0) {
                        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                        ShortBuffer shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                        while (shortBuffer.hasRemaining()) {
                            samples.add(shortBuffer.get());
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = decoder.getOutputBuffers();
                }
            }

            // Convert ArrayList to short[]
            short[] result = new short[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                result[i] = samples.get(i);
            }
            return result;

        } catch (Exception e) {
            log("Error decoding opus: " + e.getMessage());
            XposedBridge.log(e);
            return null;
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            if (extractor != null) {
                extractor.release();
            }
        }
    }

    /**
     * Encode PCM samples to Opus file using MediaCodec
     */
    private boolean encodePcmToOpus(short[] pcmData, int sampleRate, File outputFile) {
        MediaCodec encoder = null;
        MediaMuxer muxer = null;

        try {
            // Create encoder for Opus
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64000); // 64 kbps
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
            int trackIndex = -1;
            boolean muxerStarted = false;

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Convert short[] to byte[]
            byte[] pcmBytes = new byte[pcmData.length * 2];
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.nativeOrder())
                    .asShortBuffer().put(pcmData);

            int inputOffset = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            long presentationTimeUs = 0;
            long timeoutUs = 10000;

            // Opus frame size is typically 960 samples for 48kHz (20ms)
            int frameSize = 960 * 2; // bytes per frame (mono, 16-bit)

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inputBufferIndex = encoder.dequeueInputBuffer(timeoutUs);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();

                            int bytesRemaining = pcmBytes.length - inputOffset;
                            int bytesToWrite = Math.min(Math.min(inputBuffer.capacity(), frameSize), bytesRemaining);

                            if (bytesToWrite > 0) {
                                inputBuffer.put(pcmBytes, inputOffset, bytesToWrite);
                                encoder.queueInputBuffer(inputBufferIndex, 0, bytesToWrite,
                                        presentationTimeUs, 0);
                                inputOffset += bytesToWrite;
                                presentationTimeUs += (bytesToWrite / 2) * 1000000L / sampleRate;
                            }

                            if (inputOffset >= pcmBytes.length) {
                                // Signal end of stream on next available buffer
                                int eosBufferIndex = encoder.dequeueInputBuffer(timeoutUs);
                                if (eosBufferIndex >= 0) {
                                    encoder.queueInputBuffer(eosBufferIndex, 0, 0,
                                            presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputDone = true;
                                }
                            }
                        }
                    }
                }

                // Get output
                int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs);
                if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }

                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) {
                            MediaFormat outputFormat = encoder.getOutputFormat();
                            trackIndex = muxer.addTrack(outputFormat);
                            muxer.start();
                            muxerStarted = true;
                        }

                        ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                        }
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        MediaFormat outputFormat = encoder.getOutputFormat();
                        trackIndex = muxer.addTrack(outputFormat);
                        muxer.start();
                        muxerStarted = true;
                    }
                }
            }

            return true;

        } catch (Exception e) {
            log("Error encoding opus: " + e.getMessage());
            XposedBridge.log(e);
            return false;
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception ignored) {
                }
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public String getPluginName() {
        return TAG;
    }
}
