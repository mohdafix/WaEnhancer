package com.wmods.wppenhacer.xposed.features.media;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.view.WindowManager;
import android.graphics.PorterDuff;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.HKDF;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
import okhttp3.Response;

public class MediaPreview extends Feature {

    private static final String TAG_PREVIEW_BUTTON = "preview_button";
    private static final String TAG_PREVIEW_CONTAINER = "preview_container";

    private File filePath;
    private Dialog dialog;
    private VideoView currentVideoView;
    private MediaPlayer currentMediaPlayer;
    private float currentSpeed = 1.0f;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args.length < 2) return;
                            View root = (View) param.thisObject;
                            Context context = root.getContext();

                            ViewGroup surface = root.findViewById(Utils.getID("invisible_press_surface", "id"));
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
                        }
                    }
            );
        } catch (Throwable e) {
            XposedBridge.log("MediaPreview: Failed to hook video view container - " + e.getMessage());
        }

        try {
            XposedBridge.hookAllConstructors(
                    Unobfuscator.loadImageVewContainerClass(classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.args.length < 2) return;
                            View root = (View) param.thisObject;
                            Context context = root.getContext();

                            ViewGroup mediaContainer = root.findViewById(Utils.getID("media_container", "id"));
                            ViewGroup controlFrame = root.findViewById(Utils.getID("control_frame", "id"));

                            if (mediaContainer == null || controlFrame == null) return;

                            LinearLayout linearLayout = new LinearLayout(context);
                            linearLayout.setLayoutParams(new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.CENTER
                            ));
                            linearLayout.setOrientation(LinearLayout.VERTICAL);
                            linearLayout.setGravity(Gravity.CENTER);
                            linearLayout.setBackground(DesignUtils.createDrawable("rect", Color.parseColor("#40000000")));
                            int p = Utils.dipToPixels(4);
                            linearLayout.setPadding(p, p, p, p);

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
            );
        } catch (Throwable e) {
            XposedBridge.log("MediaPreview: Failed to hook image view container - " + e.getMessage());
        }
    }

    private ImageView createPreviewButton(Context context) {
        ImageView btn = new ImageView(context);
        btn.setImageDrawable(context.getDrawable(ResId.drawable.preview_eye));
        btn.setColorFilter(Color.WHITE);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Utils.dipToPixels(4);
        btn.setPadding(pad, pad, pad, pad);
        btn.setBackground(DesignUtils.createDrawable("oval", Color.parseColor("#80000000")));
        return btn;
    }

    @SuppressLint("SetTextI18n")
    private void startPlayer(long j, Context context, boolean isNewsletter) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Cursor c = MessageStore.getInstance().getDatabase().rawQuery(
                    String.format(Locale.ENGLISH,
                            "SELECT message_url,mime_type,hex(media_key),direct_path,file_size " +
                                    "FROM message_media WHERE message_row_id=\"%d\"", j),
                    null
            );

            if (c == null || !c.moveToFirst()) return;

            String url = c.getString(0);
            String mime = c.getString(1);
            String key = c.getString(2);
            String path = c.getString(3);
            long fileSize = c.getLong(4);
            c.close();

            if (isNewsletter) {
                url = "https://mmg.whatsapp.net" + path;
            }

            final String finalUrl = url;

            dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(true);
            
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            RelativeLayout root = new RelativeLayout(context);
            root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
            root.setBackgroundColor(Color.BLACK);

            RelativeLayout header = createHeader(context, mime);
            header.setId(View.generateViewId());
            root.addView(header);

            FrameLayout frameLayout = new FrameLayout(context);
            RelativeLayout.LayoutParams frameParams = new RelativeLayout.LayoutParams(-1, -1);
            frameParams.addRule(RelativeLayout.BELOW, header.getId());
            frameLayout.setLayoutParams(frameParams);
            frameLayout.setId(View.generateViewId());
            root.addView(frameLayout);

            LinearLayout loadingView = createLoadingView(context);
            frameLayout.addView(loadingView);

            dialog.setContentView(root);
            dialog.setOnDismissListener(d -> cleanupResources(executor));
            dialog.show();

            executor.execute(() -> downloadAndDisplayMedia(finalUrl, key, mime, fileSize, isNewsletter, context, frameLayout, loadingView, (ProgressBar) loadingView.getChildAt(0), (TextView) loadingView.getChildAt(1), executor));

        } catch (Exception e) {
            Utils.showToast(e.getMessage(), Toast.LENGTH_LONG);
            cleanupDialog(executor);
        }
    }

    private void downloadAndDisplayMedia(String url, String key, String mime, long fileSize, boolean isNewsletter, Context context, FrameLayout frameLayout, LinearLayout loadingView, ProgressBar progressBar, TextView textView, ExecutorService executor) {
        try {
            boolean isImage = mime.startsWith("image");
            filePath = new File(Utils.getApplication().getCacheDir(), "preview_" + System.currentTimeMillis() + (isImage ? ".jpg" : ".mp4"));
            
            if (filePath.exists()) filePath.delete();

            OkHttpClient client = new OkHttpClient.Builder().build();
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "WhatsApp/2.24.2.76 A")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new Exception("Download failed: " + response.code());
                
                InputStream is = response.body().byteStream();
                long totalBytes = response.body().contentLength();
                if (totalBytes <= 0) totalBytes = fileSize;

                if (isNewsletter) {
                    downloadWithProgress(is, totalBytes, progressBar, textView);
                } else {
                    downloadAndDecryptWithProgress(is, totalBytes, key, mime, progressBar, textView);
                }
            }

            mainHandler.post(() -> {
                loadingView.setVisibility(View.GONE);
                if (isImage) {
                    displayImage(context, frameLayout);
                } else {
                    displayVideo(context, frameLayout);
                }
            });

        } catch (Throwable e) {
            handleError(e, executor);
        }
    }

    private void downloadWithProgress(InputStream is, long total, ProgressBar progressBar, TextView textView) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int read;
            long downloaded = 0;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                downloaded += read;
                if (total > 0) {
                    final int progress = (int) ((downloaded * 100) / total);
                    final String sizeText = formatSize(downloaded) + " / " + formatSize(total);
                    mainHandler.post(() -> {
                        progressBar.setProgress(progress);
                        textView.setText(Utils.getApplication().getString(ResId.string.downloading) + " " + progress + "% - " + sizeText);
                    });
                }
            }
        } finally {
            is.close();
        }
    }

    private void downloadAndDecryptWithProgress(InputStream is, long total, String key, String mime, ProgressBar progressBar, TextView textView) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            long downloaded = 0;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
                downloaded += read;
                if (total > 0) {
                    final int progress = (int) ((downloaded * 100) / total);
                    final String sizeText = formatSize(downloaded) + " / " + formatSize(total);
                    mainHandler.post(() -> {
                        progressBar.setProgress(progress);
                        textView.setText(Utils.getApplication().getString(ResId.string.downloading) + " " + progress + "% - " + sizeText);
                    });
                }
            }
            byte[] encrypted = baos.toByteArray();
            byte[] decrypted = decryptMedia(encrypted, key, mime);
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(decrypted);
            }
        } finally {
            is.close();
            baos.close();
        }
    }

    private void displayImage(Context context, FrameLayout frameLayout) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(filePath.getAbsolutePath());
            if (bitmap == null) {
                Utils.showToast("Failed to decode image", 0);
                return;
            }
            ZoomableImageView zoomableImageView = new ZoomableImageView(context);
            zoomableImageView.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            zoomableImageView.setImageBitmap(bitmap);
            frameLayout.addView(zoomableImageView);
        } catch (Exception e) {
            Utils.showToast("Error loading image: " + e.getMessage(), 0);
        }
    }

    private void displayVideo(Context context, FrameLayout frameLayout) {
        try {
            RelativeLayout container = new RelativeLayout(context);
            container.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            
            VideoView videoView = new VideoView(context);
            this.currentVideoView = videoView;
            RelativeLayout.LayoutParams videoParams = new RelativeLayout.LayoutParams(-1, -1);
            videoParams.addRule(RelativeLayout.CENTER_IN_PARENT);
            videoView.setLayoutParams(videoParams);
            videoView.setId(View.generateViewId());
            
            LinearLayout controls = createVideoControls(context, videoView);
            
            container.addView(videoView);
            container.addView(controls);
            frameLayout.addView(container);
            
            videoView.setVideoURI(Uri.fromFile(filePath));
            videoView.setOnPreparedListener(mp -> {
                currentMediaPlayer = mp;
                mp.setLooping(false);
                videoView.start();
                updateVideoDuration(controls, mp.getDuration());
            });
            
            videoView.setOnCompletionListener(mp -> {
                videoView.seekTo(0);
                updatePlayPauseButton(controls, false);
            });
            
            videoView.setOnErrorListener((mp, what, extra) -> {
                Utils.showToast("Error playing video", 0);
                return true;
            });

        } catch (Exception e) {
            Utils.showToast("Error loading video: " + e.getMessage(), 0);
        }
    }

    private RelativeLayout createHeader(Context context, String mime) {
        RelativeLayout header = new RelativeLayout(context);
        header.setLayoutParams(new RelativeLayout.LayoutParams(-1, dpToPx(context, 56)));
        header.setBackgroundColor(Color.parseColor("#FF121B22"));
        header.setPadding(dpToPx(context, 16), 0, dpToPx(context, 16), 0);

        ImageButton backBtn = new ImageButton(context);
        backBtn.setId(View.generateViewId());
        RelativeLayout.LayoutParams backParams = new RelativeLayout.LayoutParams(dpToPx(context, 40), dpToPx(context, 40));
        backParams.addRule(RelativeLayout.ALIGN_PARENT_START);
        backParams.addRule(RelativeLayout.CENTER_VERTICAL);
        backBtn.setLayoutParams(backParams);
        backBtn.setBackgroundColor(0);
        backBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        backBtn.setColorFilter(Color.WHITE);
        backBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        backBtn.setOnClickListener(v -> {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        });
        header.addView(backBtn);

        TextView title = new TextView(context);
        RelativeLayout.LayoutParams titleParams = new RelativeLayout.LayoutParams(-2, -2);
        titleParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        title.setLayoutParams(titleParams);
        title.setTextColor(Color.WHITE);
        title.setTextSize(2, 18);
        title.setText(context.getString(mime.startsWith("image") ? ResId.string.preview_image : ResId.string.preview_video));
        header.addView(title);

        return header;
    }

    private LinearLayout createLoadingView(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        ProgressBar progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(dpToPx(context, 200), dpToPx(context, 8));
        pbParams.gravity = Gravity.CENTER;
        progressBar.setLayoutParams(pbParams);
        
        // Progress bar color
        progressBar.getProgressDrawable().setColorFilter(Color.parseColor("#FF25D366"), android.graphics.PorterDuff.Mode.SRC_IN);

        layout.addView(progressBar);

        TextView text = new TextView(context);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-2, -2);
        textParams.topMargin = dpToPx(context, 16);
        textParams.gravity = Gravity.CENTER;
        text.setLayoutParams(textParams);
        text.setTextColor(Color.WHITE);
        text.setTextSize(2, 14);
        text.setText("Starting download...");
        layout.addView(text);

        return layout;
    }

    @SuppressLint("SetTextI18n")
    private LinearLayout createVideoControls(Context context, VideoView videoView) {
        currentSpeed = 1.0f;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(-1, -2);
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        layout.setLayoutParams(params);
        layout.setBackgroundColor(Color.parseColor("#B3000000"));
        layout.setPadding(dpToPx(context, 16), dpToPx(context, 8), dpToPx(context, 16), dpToPx(context, 16));

        SeekBar seekBar = new SeekBar(context);
        seekBar.setId(View.generateViewId());
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        seekBar.setMax(100);
        seekBar.setProgress(0);
        layout.addView(seekBar);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dpToPx(context, 8);
        row.setLayoutParams(rowParams);

        ImageButton playPauseBtn = new ImageButton(context);
        playPauseBtn.setId(View.generateViewId());
        playPauseBtn.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(context, 48), dpToPx(context, 48)));
        playPauseBtn.setBackgroundColor(0);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setTag("pause");
        row.addView(playPauseBtn);

        TextView timeCurrent = new TextView(context);
        timeCurrent.setId(View.generateViewId());
        LinearLayout.LayoutParams timeCurParams = new LinearLayout.LayoutParams(-2, -2);
        timeCurParams.leftMargin = dpToPx(context, 8);
        timeCurrent.setLayoutParams(timeCurParams);
        timeCurrent.setTextColor(Color.WHITE);
        timeCurrent.setTextSize(2, 12);
        timeCurrent.setText("00:00");
        row.addView(timeCurrent);

        TextView divider = new TextView(context);
        divider.setText(" / ");
        divider.setTextColor(Color.WHITE);
        divider.setTextSize(2, 12);
        row.addView(divider);

        TextView timeTotal = new TextView(context);
        timeTotal.setId(View.generateViewId());
        timeTotal.setTextColor(Color.WHITE);
        timeTotal.setTextSize(2, 12);
        timeTotal.setText("00:00");
        row.addView(timeTotal);

        View spacer = new View(context);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1.0f));

        TextView speedBtn = new TextView(context);
        speedBtn.setText("1.0x");
        speedBtn.setTextColor(Color.WHITE);
        speedBtn.setTextSize(2, 12);
        speedBtn.setGravity(Gravity.CENTER);
        speedBtn.setPadding(dpToPx(context, 8), dpToPx(context, 4), dpToPx(context, 8), dpToPx(context, 4));
        speedBtn.setBackground(DesignUtils.createDrawable("rect", Color.parseColor("#4DFFFFFF")));
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(dpToPx(context, 40), dpToPx(context, 24));
        speedBtn.setLayoutParams(speedParams);
        row.addView(speedBtn);

        layout.addView(row);

        playPauseBtn.setOnClickListener(v -> {
            if (videoView.isPlaying()) {
                videoView.pause();
                updatePlayPauseButton(layout, false);
            } else {
                videoView.start();
                updatePlayPauseButton(layout, true);
            }
        });

        speedBtn.setOnClickListener(v -> {
            if (currentSpeed == 1.0f) currentSpeed = 1.5f;
            else if (currentSpeed == 1.5f) currentSpeed = 2.0f;
            else if (currentSpeed == 2.0f) currentSpeed = 0.5f;
            else currentSpeed = 1.0f;
            
            speedBtn.setText(currentSpeed + "x");
            if (currentMediaPlayer != null) {
                try {
                    currentMediaPlayer.setPlaybackParams(currentMediaPlayer.getPlaybackParams().setSpeed(currentSpeed));
                } catch (Exception ignored) {}
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && videoView.getDuration() > 0) {
                    videoView.seekTo((videoView.getDuration() * progress) / 100);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (videoView.isPlaying() && videoView.getDuration() > 0) {
                    seekBar.setProgress((videoView.getCurrentPosition() * 100) / videoView.getDuration());
                    timeCurrent.setText(formatTime(videoView.getCurrentPosition()));
                }
                if (dialog != null && dialog.isShowing()) {
                    handler.postDelayed(this, 500);
                }
            }
        });

        return layout;
    }

    private void updatePlayPauseButton(LinearLayout controls, boolean isPlaying) {
        ImageButton btn = (ImageButton) ((LinearLayout) controls.getChildAt(1)).getChildAt(0);
        btn.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        btn.setTag(isPlaying ? "pause" : "play");
    }

    private void updateVideoDuration(LinearLayout controls, int duration) {
        TextView tv = (TextView) ((LinearLayout) controls.getChildAt(1)).getChildAt(3);
        tv.setText(formatTime(duration));
    }

    private void cleanupDialog(ExecutorService executor) {
        mainHandler.post(() -> {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        });
        cleanupResources(executor);
    }

    private void cleanupResources(ExecutorService executor) {
        if (currentMediaPlayer != null) {
            try { currentMediaPlayer.release(); } catch (Exception ignored) {}
            currentMediaPlayer = null;
        }
        currentVideoView = null;
        if (filePath != null && filePath.exists()) filePath.delete();
        if (executor != null) executor.shutdownNow();
    }

    private void handleError(Throwable e, ExecutorService executor) {
        XposedBridge.log(e);
        mainHandler.post(() -> Utils.showToast(e.getMessage(), 1));
        cleanupDialog(executor);
    }

    private int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private String formatTime(int ms) {
        int sec = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    private byte[] decryptMedia(byte[] encryptedData, String mediaKey, String mimeType) throws Exception {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 64; i += 2) {
            keyBytes[i / 2] = (byte) ((Character.digit(mediaKey.charAt(i), 16) << 4) + Character.digit(mediaKey.charAt(i + 1), 16));
        }
        byte[] typeKey = MEDIA_KEYS.getOrDefault(mimeType, MEDIA_KEYS.get("document"));
        byte[] derived = HKDF.createFor(3).deriveSecrets(keyBytes, typeKey, 112);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(derived, 16, 48), "AES"), new IvParameterSpec(Arrays.copyOfRange(derived, 0, 16)));
        return cipher.doFinal(Arrays.copyOfRange(encryptedData, 0, encryptedData.length - 10));
    }

    // ================= ZOOMABLE IMAGE VIEW =================

    public static class ZoomableImageView extends androidx.appcompat.widget.AppCompatImageView {
        private static final long DOUBLE_TAP_TIMEOUT = 300;
        private Matrix matrix = new Matrix();
        private Matrix savedMatrix = new Matrix();
        private static final int NONE = 0;
        private static final int DRAG = 1;
        private static final int ZOOM = 2;
        private int mode = NONE;
        private PointF last = new PointF();
        private PointF mid = new PointF();
        private float oldDist = 1f;
        private float[] m = new float[9];
        private float minScale = 1f;
        private float maxScale = 5f;
        private long lastTapTime = 0;
        private boolean isZoomed = false;

        public ZoomableImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            setOnTouchListener((v, event) -> {
                int action = event.getAction() & MotionEvent.ACTION_MASK;
                if (event.getPointerCount() > 1) v.getParent().requestDisallowInterceptTouchEvent(true);

                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        savedMatrix.set(matrix);
                        last.set(event.getX(), event.getY());
                        mode = DRAG;
                        long time = System.currentTimeMillis();
                        if (time - lastTapTime < DOUBLE_TAP_TIMEOUT) {
                            handleDoubleTap(event.getX(), event.getY());
                            lastTapTime = 0;
                            return true;
                        }
                        lastTapTime = time;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        oldDist = spacing(event);
                        if (oldDist > 10f) {
                            savedMatrix.set(matrix);
                            midPoint(mid, event);
                            mode = ZOOM;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        mode = NONE;
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (mode == DRAG) {
                            matrix.set(savedMatrix);
                            matrix.postTranslate(event.getX() - last.x, event.getY() - last.y);
                        } else if (mode == ZOOM) {
                            float newDist = spacing(event);
                            if (newDist > 10f) {
                                matrix.set(savedMatrix);
                                float scale = newDist / oldDist;
                                matrix.postScale(scale, scale, mid.x, mid.y);
                            }
                        }
                        limitScaleAndPan();
                        setImageMatrix(matrix);
                        break;
                }
                return true;
            });
        }

        private void handleDoubleTap(float x, float y) {
            if (isZoomed) {
                centerImage();
                isZoomed = false;
            } else {
                matrix.postScale(3f, 3f, x, y);
                isZoomed = true;
            }
            limitScaleAndPan();
            setImageMatrix(matrix);
        }

        private float spacing(MotionEvent event) {
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        private void midPoint(PointF point, MotionEvent event) {
            float x = event.getX(0) + event.getX(1);
            float y = event.getY(0) + event.getY(1);
            point.set(x / 2, y / 2);
        }

        private void limitScaleAndPan() {
            matrix.getValues(m);
            float scale = m[Matrix.MSCALE_X];
            float transX = m[Matrix.MTRANS_X];
            float transY = m[Matrix.MTRANS_Y];

            if (scale < minScale) matrix.postScale(minScale / scale, minScale / scale, getWidth() / 2f, getHeight() / 2f);
            else if (scale > maxScale) matrix.postScale(maxScale / scale, maxScale / scale, getWidth() / 2f, getHeight() / 2f);

            // Re-get values after scale limit
            matrix.getValues(m);
            scale = m[Matrix.MSCALE_X];
            transX = m[Matrix.MTRANS_X];
            transY = m[Matrix.MTRANS_Y];

            float width = getDrawable().getIntrinsicWidth() * scale;
            float height = getDrawable().getIntrinsicHeight() * scale;

            if (width < getWidth()) {
                m[Matrix.MTRANS_X] = (getWidth() - width) / 2f;
            } else {
                if (transX > 0) m[Matrix.MTRANS_X] = 0;
                else if (transX < getWidth() - width) m[Matrix.MTRANS_X] = getWidth() - width;
            }

            if (height < getHeight()) {
                m[Matrix.MTRANS_Y] = (getHeight() - height) / 2f;
            } else {
                if (transY > 0) m[Matrix.MTRANS_Y] = 0;
                else if (transY < getHeight() - height) m[Matrix.MTRANS_Y] = getHeight() - height;
            }
            matrix.setValues(m);
        }

        private void centerImage() {
            if (getDrawable() == null) return;
            float viewWidth = getWidth();
            float viewHeight = getHeight();
            float drawableWidth = getDrawable().getIntrinsicWidth();
            float drawableHeight = getDrawable().getIntrinsicHeight();
            float scale = Math.min(viewWidth / drawableWidth, viewHeight / drawableHeight);
            minScale = scale;
            matrix.setScale(scale, scale);
            matrix.postTranslate((viewWidth - drawableWidth * scale) / 2f, (viewHeight - drawableHeight * scale) / 2f);
            setImageMatrix(matrix);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (changed) centerImage();
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Media Preview";
    }
}
