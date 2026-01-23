package com.wmods.wppenhacer.xposed.features.media;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.VideoView;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.SeekBar;
import android.widget.TextView;


import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.HKDF;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.BufferedSink;
import okio.Okio;

public class MediaPreview extends Feature {

    private File filePath;
    private Dialog videoDialog;
    private VideoView currentVideoView;
    private MediaPlayer currentMediaPlayer;
    private boolean isMuted = false;
    private float playbackSpeed = 1.0f;

    static HashMap<String, byte[]> MEDIA_KEYS = new HashMap<>();

    static {
        MEDIA_KEYS.put("image", "WhatsApp Image Keys".getBytes());
        MEDIA_KEYS.put("video", "WhatsApp Video Keys".getBytes());
        MEDIA_KEYS.put("audio", "WhatsApp Audio Keys".getBytes());
        MEDIA_KEYS.put("document", "WhatsApp Document Keys".getBytes());
        MEDIA_KEYS.put("image/jpeg", "WhatsApp Image Keys".getBytes());
        MEDIA_KEYS.put("image/png", "WhatsApp Image Keys".getBytes());
        MEDIA_KEYS.put("video/mp4", "WhatsApp Video Keys".getBytes());
    }

    public MediaPreview(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() {

        if (!prefs.getBoolean("media_preview", true)) return;

        try {
            XposedBridge.hookAllConstructors(
                    Unobfuscator.loadVideoViewContainerClass(classLoader),
                    new PreviewButtonHook(true)
            );
        } catch (Throwable ignored) {}

        try {
            XposedBridge.hookAllConstructors(
                    Unobfuscator.loadImageVewContainerClass(classLoader),
                    new PreviewButtonHook(false)
            );
        } catch (Throwable ignored) {}
    }

    // ================= PREVIEW BUTTON =================

    private class PreviewButtonHook extends XC_MethodHook {

        private final boolean isVideo;

        PreviewButtonHook(boolean isVideo) {
            this.isVideo = isVideo;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {

            if (param.args.length < 2) return;

            View root = (View) param.thisObject;
            Context context = root.getContext();

            if (isVideo) {
                // Video button positioning (from reference code)
                ViewGroup surface =
                        root.findViewById(Utils.getID("invisible_press_surface", "id"));
                if (surface == null || surface.getChildCount() == 0) return;

                View control = surface.getChildAt(0);
                surface.removeViewAt(0);

                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER);
                surface.addView(row);
                row.addView(control);

                ImageView btn = createPreviewButton(context);
                row.addView(btn);

                btn.setOnClickListener(v -> {
                    Object msg = XposedHelpers.callMethod(param.thisObject, "getFMessage");
                    if (msg == null) return;

                    long id = new FMessageWpp(msg).getRowId();
                    boolean newsletter = WppCore.getCurrentUserJid() != null
                            && WppCore.getCurrentUserJid().isNewsletter();

                    startPlayer(id, context, newsletter);
                });
            } else {
                // Image button positioning (centered like reference)
                ViewGroup mediaContainer =
                        root.findViewById(Utils.getID("media_container", "id"));
                ViewGroup controlFrame =
                        root.findViewById(Utils.getID("control_frame", "id"));

                if (mediaContainer == null || controlFrame == null) return;

                LinearLayout linearLayout = new LinearLayout(context);
                linearLayout.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                ));
                linearLayout.setOrientation(LinearLayout.VERTICAL);
                linearLayout.setGravity(Gravity.CENTER);

                mediaContainer.removeView(controlFrame);
                linearLayout.addView(controlFrame);
                mediaContainer.addView(linearLayout);

                ImageView btn = createPreviewButton(context);
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        Utils.dipToPixels(42),
                        Utils.dipToPixels(32)
                );
                btnParams.gravity = Gravity.CENTER;
                btnParams.topMargin = Utils.dipToPixels(8);
                btn.setLayoutParams(btnParams);
                linearLayout.addView(btn);

                btn.setVisibility(controlFrame.getVisibility());
                controlFrame.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    if (btn.getVisibility() != controlFrame.getVisibility())
                        btn.setVisibility(controlFrame.getVisibility());
                });

                btn.setOnClickListener(v -> {
                    Object msg = XposedHelpers.callMethod(param.thisObject, "getFMessage");
                    if (msg == null) return;

                    long id = new FMessageWpp(msg).getRowId();
                    boolean newsletter = WppCore.getCurrentUserJid() != null
                            && WppCore.getCurrentUserJid().isNewsletter();

                    startPlayer(id, context, newsletter);
                });
            }
        }
    }

    // ================= BUTTON UI =================

    private ImageView createPreviewButton(Context context) {
        ImageView btn = new ImageView(context);
        btn.setImageDrawable(context.getDrawable(ResId.drawable.preview_eye));
        btn.setColorFilter(Color.WHITE);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Utils.dipToPixels(6);
        btn.setPadding(pad, pad, pad, pad);
        btn.setBackground(createRoundDrawable(0x66000000));
        return btn;
    }

    // ================= MEDIA LOADING =================

    private void startPlayer(long id, Context context, boolean isNewsletter) {

        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Cursor c = MessageStore.getInstance().getDatabase().rawQuery(
                    String.format(Locale.ENGLISH,
                            "SELECT message_url,mime_type,hex(media_key),direct_path " +
                                    "FROM message_media WHERE message_row_id=\"%d\"", id),
                    null
            );

            if (c == null || !c.moveToFirst()) return;

            String url = c.getString(0);
            String mime = c.getString(1);
            String key = c.getString(2);
            String path = c.getString(3);
            c.close();

            if (isNewsletter) {
                url = "https://mmg.whatsapp.net" + path;
            }

            String finalUrl = url;

            executor.execute(() -> decodeAndShow(finalUrl, key, mime, context, isNewsletter));

        } catch (Throwable e) {
            Utils.showToast(e.getMessage(), Toast.LENGTH_LONG);
            executor.shutdownNow();
        }
    }

    private void decodeAndShow(
            String url,
            String mediaKey,
            String mimeType,
            Context context,
            boolean isNewsletter
    ) {
        try {
            boolean isImage = mimeType.startsWith("image");

            filePath = new File(
                    Utils.getApplication().getCacheDir(),
                    "preview_" + System.currentTimeMillis() + (isImage ? ".jpg" : ".mp4")
            );

            byte[] data = Objects.requireNonNull(
                    new OkHttpClient().newCall(
                            new Request.Builder().url(url).build()
                    ).execute().body()
            ).bytes();

            byte[] out = isNewsletter ? data : decryptMedia(data, mediaKey, mimeType);

            try (BufferedSink sink = Okio.buffer(Okio.sink(filePath))) {
                sink.write(out);
            }

            if (isImage) {
                new Handler(Looper.getMainLooper()).post(() -> showImage(context));
            } else {
                new Handler(Looper.getMainLooper()).post(() -> showVideo(context));
            }

        } catch (Throwable e) {
            Utils.showToast(e.getMessage(), Toast.LENGTH_LONG);
        }
    }

    // ================= IMAGE =================

    private void showImage(Context context) {

        ImageView imageView = new ImageView(context);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setImageBitmap(BitmapFactory.decodeFile(filePath.getAbsolutePath()));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(imageView)
                .create();

        dialog.setOnDismissListener(d -> cleanup());
        dialog.show();
    }

    // ================= VIDEO =================

    private void showVideo(Context context) {

        videoDialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        videoDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        VideoView videoView = new VideoView(context);
        videoView.setVideoURI(Uri.fromFile(filePath));
        
        // Center video like image viewer - not full screen
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Utils.dipToPixels(280), // Reasonable height
                Gravity.CENTER
        );
        videoView.setLayoutParams(videoParams);
        root.addView(videoView);

        // Main floating control panel (positioned below video)
        FrameLayout floatingPanel = new FrameLayout(context);
        floatingPanel.setBackground(createRoundDrawable(Color.parseColor("#CC000000")));
        
        int panelWidth = Utils.dipToPixels(280);
        int panelHeight = Utils.dipToPixels(120);
        
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth, panelHeight,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM
        );
        panelParams.setMargins(0, 0, 0, Utils.dipToPixels(100)); // Position above bottom
        root.addView(floatingPanel, panelParams);

        // Play/Pause button in center
        ImageView playPauseBtn = new ImageView(context);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setPadding(Utils.dipToPixels(16), Utils.dipToPixels(16), Utils.dipToPixels(16), Utils.dipToPixels(16));
        playPauseBtn.setBackground(createRoundDrawable(Color.parseColor("#80FFFFFF")));
        
        FrameLayout.LayoutParams playPauseParams = new FrameLayout.LayoutParams(
                Utils.dipToPixels(56),
                Utils.dipToPixels(56),
                Gravity.CENTER
        );
        floatingPanel.addView(playPauseBtn, playPauseParams);

        // Top row controls (mute and speed)
        LinearLayout topControls = new LinearLayout(context);
        topControls.setOrientation(LinearLayout.HORIZONTAL);
        topControls.setGravity(Gravity.CENTER_VERTICAL);
        
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Utils.dipToPixels(36),
                Gravity.TOP
        );
        topParams.setMargins(Utils.dipToPixels(16), Utils.dipToPixels(8), Utils.dipToPixels(16), 0);
        floatingPanel.addView(topControls, topParams);

        // Speed button (modern pill design)
        TextView speedBtn = new TextView(context);
        speedBtn.setText("1.0x");
        speedBtn.setTextColor(Color.WHITE);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setTextSize(12);
        speedBtn.setPadding(Utils.dipToPixels(12), Utils.dipToPixels(4), Utils.dipToPixels(12), Utils.dipToPixels(4));
        speedBtn.setBackground(createRoundDrawable(Color.parseColor("#66000000")));
        
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topControls.addView(speedBtn, speedParams);

        // Spacer between controls
        View spacer = new View(context);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1.0f);
        topControls.addView(spacer, spacerParams);

        // Mute button
        ImageView muteBtn = new ImageView(context);
        muteBtn.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
        muteBtn.setColorFilter(Color.WHITE);
        muteBtn.setPadding(Utils.dipToPixels(8), Utils.dipToPixels(8), Utils.dipToPixels(8), Utils.dipToPixels(8));
        muteBtn.setBackground(createRoundDrawable(Color.parseColor("#66000000")));

        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(
                Utils.dipToPixels(36),
                Utils.dipToPixels(36)
        );
        topControls.addView(muteBtn, muteParams);

        // Bottom seek bar
        SeekBar seekBar = new SeekBar(context);
        
        FrameLayout.LayoutParams seekParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Utils.dipToPixels(32),
                Gravity.BOTTOM
        );
        seekParams.setMargins(Utils.dipToPixels(16), 0, Utils.dipToPixels(16), Utils.dipToPixels(8));
        floatingPanel.addView(seekBar, seekParams);

        // Time labels (optional enhancement)
        LinearLayout timeContainer = new LinearLayout(context);
        timeContainer.setOrientation(LinearLayout.HORIZONTAL);
        timeContainer.setGravity(Gravity.CENTER_VERTICAL);
        
        FrameLayout.LayoutParams timeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Utils.dipToPixels(20),
                Gravity.BOTTOM
        );
        timeParams.setMargins(Utils.dipToPixels(16), 0, Utils.dipToPixels(16), Utils.dipToPixels(36));
        floatingPanel.addView(timeContainer, timeParams);

        TextView currentTime = new TextView(context);
        currentTime.setText("00:00");
        currentTime.setTextColor(Color.WHITE);
        currentTime.setTextSize(10);
        timeContainer.addView(currentTime);

        View timeSpacer = new View(context);
        LinearLayout.LayoutParams timeSpacerParams = new LinearLayout.LayoutParams(0, 1, 1.0f);
        timeContainer.addView(timeSpacer, timeSpacerParams);

        TextView totalTime = new TextView(context);
        totalTime.setText("00:00");
        totalTime.setTextColor(Color.WHITE);
        totalTime.setTextSize(10);
        timeContainer.addView(totalTime);

        videoDialog.setContentView(root);
        videoDialog.show();

        Handler handler = new Handler(Looper.getMainLooper());

        // Auto-hide floating panel with smooth animations
        Handler hideHandler = new Handler(Looper.getMainLooper());
        Runnable hideRunnable = new Runnable() {
            @Override
            public void run() {
                floatingPanel.animate().alpha(0f).scaleY(0.8f).scaleX(0.8f).setDuration(500).start();
            }
        };

        floatingPanel.setAlpha(0f);
        floatingPanel.setScaleX(0.8f);
        floatingPanel.setScaleY(0.8f);

        videoView.setOnPreparedListener(mp -> {
            currentMediaPlayer = mp;
            seekBar.setMax(mp.getDuration());
            totalTime.setText(formatTime(mp.getDuration()));
            videoView.start();

            // Show panel with scale animation
            floatingPanel.animate().alpha(1f).scaleY(1f).scaleX(1f).setDuration(300).start();
            hideHandler.postDelayed(hideRunnable, 3000);

            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (videoView.isPlaying()) {
                        int pos = videoView.getCurrentPosition();
                        seekBar.setProgress(pos);
                        currentTime.setText(formatTime(pos));
                    }
                    handler.postDelayed(this, 100);
                }
            });

            // Play/Pause button logic
            playPauseBtn.setOnClickListener(v -> {
                if (videoView.isPlaying()) {
                    videoView.pause();
                    playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
                } else {
                    videoView.start();
                    playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
                }
            });

            // Mute button logic
            muteBtn.setOnClickListener(v -> {
                isMuted = !isMuted;

                if (currentMediaPlayer != null) {
                    currentMediaPlayer.setVolume(
                            isMuted ? 0f : 1f,
                            isMuted ? 0f : 1f
                    );
                }

                muteBtn.setImageResource(
                        isMuted
                                ? android.R.drawable.ic_lock_silent_mode
                                : android.R.drawable.ic_lock_silent_mode_off
                );
            });

            // Speed button logic with visual feedback
            speedBtn.setOnClickListener(v -> {
                if (playbackSpeed == 1.0f) {
                    playbackSpeed = 1.5f;
                } else if (playbackSpeed == 1.5f) {
                    playbackSpeed = 2.0f;
                } else {
                    playbackSpeed = 1.0f;
                }

                speedBtn.setText(playbackSpeed + "x");

                // Animate button
                speedBtn.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).withEndAction(() -> {
                    speedBtn.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                }).start();

                if (currentMediaPlayer != null) {
                    try {
                        currentMediaPlayer.setPlaybackParams(
                                currentMediaPlayer.getPlaybackParams()
                                        .setSpeed(playbackSpeed)
                        );
                    } catch (Throwable ignored) {}
                }
            });
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) {
                    videoView.seekTo(p);
                    currentTime.setText(formatTime(p));
                }
            }

            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Touch interactions with panel reveal
        videoView.setOnTouchListener(new View.OnTouchListener() {
            long lastTap = 0;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 300) {
                        if (videoView.isPlaying()) {
                            videoView.pause();
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
                        } else {
                            videoView.start();
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
                        }
                    }
                    lastTap = now;
                }
                
                // Show floating panel with smooth animation
                floatingPanel.animate().alpha(1f).scaleY(1f).scaleX(1f).setDuration(300).start();
                hideHandler.removeCallbacks(hideRunnable);
                hideHandler.postDelayed(hideRunnable, 3000);
                
                return false;
            }
        });

        videoDialog.setOnDismissListener(d -> cleanup());
    }

    private String formatTime(int milliseconds) {
        int seconds = milliseconds / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    // ================= CLEANUP =================

    private void cleanup() {
        try {
            if (currentMediaPlayer != null) {
                currentMediaPlayer.release();
                currentMediaPlayer = null;
            }
            currentVideoView = null;

            if (filePath != null && filePath.exists()) {
                filePath.delete();
            }
        } catch (Exception ignored) {}
    }

    // ================= DECRYPT =================

    private byte[] decryptMedia(byte[] encryptedData, String mediaKey, String mimeType)
            throws Exception {

        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 64; i += 2) {
            keyBytes[i / 2] =
                    (byte) ((Character.digit(mediaKey.charAt(i), 16) << 4)
                            + Character.digit(mediaKey.charAt(i + 1), 16));
        }

        byte[] typeKey = MEDIA_KEYS.getOrDefault(mimeType, MEDIA_KEYS.get("document"));
        byte[] derived = HKDF.createFor(3).deriveSecrets(keyBytes, typeKey, 112);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(Arrays.copyOfRange(derived, 16, 48), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(derived, 0, 16))
        );

        return cipher.doFinal(Arrays.copyOfRange(encryptedData, 0, encryptedData.length - 10));
    }

    private static GradientDrawable createRoundDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Media Preview";
    }
}
