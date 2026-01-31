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
import android.view.WindowManager;
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
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(Color.BLACK);

        ImageView imageView = new ImageView(context);
        imageView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        imageView.setImageBitmap(BitmapFactory.decodeFile(filePath.getAbsolutePath()));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(imageView);

        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> cleanup());
        dialog.show();
    }

    // ================= VIDEO =================

    private void showVideo(Context context) {
        
        videoDialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        videoDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        Window window = videoDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(Color.BLACK);

        VideoView videoView = new VideoView(context);
        videoView.setVideoURI(Uri.fromFile(filePath));
        
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        videoView.setLayoutParams(videoParams);
        root.addView(videoView);

        // ================= OVERLAY LAYER (Always Visible for Clicks) =================
        FrameLayout touchOverlay = new FrameLayout(context);
        touchOverlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));
        touchOverlay.setClickable(true);
        root.addView(touchOverlay);

        // ================= CONTROLS CONTAINER =================
        FrameLayout controlsOverlay = new FrameLayout(context);
        controlsOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(controlsOverlay);

        // Center Play/Pause Button
        ImageView centerPlayBtn = new ImageView(context);
        centerPlayBtn.setImageResource(android.R.drawable.ic_media_pause);
        centerPlayBtn.setColorFilter(Color.WHITE);
        centerPlayBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int playBtnSize = Utils.dipToPixels(64);
        int playBtnPadding = Utils.dipToPixels(18);
        centerPlayBtn.setPadding(playBtnPadding, playBtnPadding, playBtnPadding, playBtnPadding);
        centerPlayBtn.setBackground(createCircleDrawable(Color.parseColor("#80000000")));
        
        FrameLayout.LayoutParams centerPlayParams = new FrameLayout.LayoutParams(playBtnSize, playBtnSize);
        centerPlayParams.gravity = Gravity.CENTER;
        controlsOverlay.addView(centerPlayBtn, centerPlayParams);

        // Bottom Controls Container (Gradient Background)
        LinearLayout bottomContainer = new LinearLayout(context);
        bottomContainer.setOrientation(LinearLayout.VERTICAL);
        bottomContainer.setPadding(
                Utils.dipToPixels(16),
                Utils.dipToPixels(16),
                Utils.dipToPixels(16),
                Utils.dipToPixels(16)
        );
        
        // Gradient background for readability
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.parseColor("#B3000000")}
        );
        bottomContainer.setBackground(gradient);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bottomParams.gravity = Gravity.BOTTOM;
        controlsOverlay.addView(bottomContainer, bottomParams);

        // Row 1: Seek Bar
        SeekBar seekBar = new SeekBar(context);
        seekBar.setPadding(0, 0, 0, 0);
        // Style seekbar if possible, otherwise default is okay
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        seekParams.bottomMargin = Utils.dipToPixels(8);
        bottomContainer.addView(seekBar, seekParams);

        // Row 2: Time and Actions
        LinearLayout actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomContainer.addView(actionsRow);

        // Time Text
        TextView timeText = new TextView(context);
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(12);
        timeText.setText("00:00 / 00:00");
        actionsRow.addView(timeText);

        // Spacer
        View spacer = new View(context);
        actionsRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

        // Speed Button
        TextView speedBtn = new TextView(context);
        speedBtn.setText("1.0x");
        speedBtn.setTextColor(Color.WHITE);
        speedBtn.setTextSize(12);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        speedBtn.setPadding(Utils.dipToPixels(12), Utils.dipToPixels(6), Utils.dipToPixels(12), Utils.dipToPixels(6));
        speedBtn.setBackground(createRoundedRectDrawable(Color.parseColor("#4DFFFFFF"), Utils.dipToPixels(16)));
        
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        speedParams.rightMargin = Utils.dipToPixels(16);
        actionsRow.addView(speedBtn, speedParams);

        // Mute Button
        ImageView muteBtn = new ImageView(context);
        muteBtn.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
        muteBtn.setColorFilter(Color.WHITE);
        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(
                Utils.dipToPixels(24),
                Utils.dipToPixels(24)
        );
        actionsRow.addView(muteBtn, muteParams);

        videoDialog.setContentView(root);
        videoDialog.show();

        // Logic
        Handler handler = new Handler(Looper.getMainLooper());
        Handler hideHandler = new Handler(Looper.getMainLooper());
        
        Runnable hideControls = () -> {
            controlsOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> {
                         controlsOverlay.setVisibility(View.GONE);
                         centerPlayBtn.setVisibility(View.GONE); // Ensure plays button is gone so touchOverlay gets clicks
                    })
                    .start();
        };

        Runnable showControls = () -> {
            controlsOverlay.setVisibility(View.VISIBLE);
            centerPlayBtn.setVisibility(View.VISIBLE);
            controlsOverlay.animate().alpha(1f).setDuration(200).start();
            hideHandler.removeCallbacks(hideControls);
            hideHandler.postDelayed(hideControls, 3000);
        };

        // This overlay is ALWAYS clickable and below control buttons but ABOVE video
        // It toggles the controls overlay
        touchOverlay.setOnClickListener(v -> {
            // Toggle
            if (controlsOverlay.getVisibility() == View.VISIBLE && controlsOverlay.getAlpha() > 0.5f) {
                hideHandler.removeCallbacks(hideControls);
                hideControls.run();
            } else {
                showControls.run();
            }
        });

        // The controls themselves should consume click events so they don't toggle the UI
        bottomContainer.setOnClickListener(v -> hideHandler.removeCallbacks(hideControls)); // Keep alive

        centerPlayBtn.setOnClickListener(v -> {
            if (videoView.isPlaying()) {
                videoView.pause();
                centerPlayBtn.setImageResource(android.R.drawable.ic_media_play);
                hideHandler.removeCallbacks(hideControls); // Keep controls visible while paused
            } else {
                videoView.start();
                centerPlayBtn.setImageResource(android.R.drawable.ic_media_pause);
                showControls.run(); // Will auto-hide in 3s
            }
        });

        videoView.setOnPreparedListener(mp -> {
            currentMediaPlayer = mp;
            int duration = mp.getDuration();
            seekBar.setMax(duration);
            timeText.setText("00:00 / " + formatTime(duration));
            videoView.start();
            
            // Initial hide after delay
            hideHandler.postDelayed(hideControls, 2500);

            // Update Progress
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (videoView.isPlaying()) {
                            int pos = videoView.getCurrentPosition();
                            seekBar.setProgress(pos);
                            timeText.setText(formatTime(pos) + " / " + formatTime(duration));
                        }
                        // Continue updating even if paused to catch seek changes
                        handler.postDelayed(this, 200);
                    } catch (Exception ignored) {}
                }
            });

            // Mute Logic
            muteBtn.setOnClickListener(v -> {
                isMuted = !isMuted;
                if (currentMediaPlayer != null) {
                   try {
                       currentMediaPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
                   } catch(Exception ignored){}
                }
                muteBtn.setImageResource(isMuted ? android.R.drawable.ic_lock_silent_mode : android.R.drawable.ic_lock_silent_mode_off);
                showControls.run(); // Reset timer
            });

            // Speed Logic
            speedBtn.setOnClickListener(v -> {
                if (playbackSpeed == 1.0f) {
                    playbackSpeed = 1.5f;
                } else if (playbackSpeed == 1.5f) {
                    playbackSpeed = 2.0f;
                } else {
                    playbackSpeed = 1.0f;
                }
                speedBtn.setText(playbackSpeed + "x");
                if (currentMediaPlayer != null) {
                    try {
                        currentMediaPlayer.setPlaybackParams(currentMediaPlayer.getPlaybackParams().setSpeed(playbackSpeed));
                    } catch (Exception ignored) {}
                }
                showControls.run(); // Reset timer
            });
        });
        
        videoView.setOnCompletionListener(mp -> {
             centerPlayBtn.setImageResource(android.R.drawable.ic_media_play);
             showControls.run();
             hideHandler.removeCallbacks(hideControls); // Keep controls valid on end
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    videoView.seekTo(progress);
                    int total = videoView.getDuration();
                    timeText.setText(formatTime(progress) + " / " + formatTime(total));
                    showControls.run();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                hideHandler.removeCallbacks(hideControls);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if(videoView.isPlaying()) showControls.run();
            }
        });

        videoDialog.setOnDismissListener(d -> {
            handler.removeCallbacksAndMessages(null);
            hideHandler.removeCallbacksAndMessages(null);
            cleanup();
        });
    }

    private GradientDrawable createCircleDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private GradientDrawable createRoundedRectDrawable(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radius);
        d.setColor(color);
        return d;
    }

    private String formatTime(int milliseconds) {
        if (milliseconds <= 0) return "00:00";
        int seconds = milliseconds / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
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
