package com.wmods.wppenhacer.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.xposed.features.media.VideoCallRecording;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VideoRecordingService extends Service {
    private static final String TAG = "VideoRecordingService";
    private static final String CHANNEL_ID = "video_recording_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_START_ROOT = "ACTION_START_ROOT";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE";
    public static final String EXTRA_DATA = "EXTRA_DATA";
    public static final String EXTRA_AUDIO_SOURCE = "EXTRA_AUDIO_SOURCE";

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;

    private int mScreenWidth;
    private int mScreenHeight;
    private int mScreenDensity;

    private String mOutputPath;
    private Uri mVideoUri;

    public static void startService(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, VideoRecordingService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_DATA, data);
        startForegroundServiceCompat(context, intent);
    }

    public static void startServiceRoot(Context context) {
        Intent intent = new Intent(context, VideoRecordingService.class);
        intent.setAction(ACTION_START_ROOT);
        startForegroundServiceCompat(context, intent);
    }

    public static void stopService(Context context) {
        Intent intent = new Intent(context, VideoRecordingService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    private static void startForegroundServiceCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
        mScreenDensity = metrics.densityDpi;

        if (mScreenWidth % 2 != 0) mScreenWidth--;
        if (mScreenHeight % 2 != 0) mScreenHeight--;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (action.equals(ACTION_START) || action.equals(ACTION_START_ROOT)) {
            startForeground(NOTIFICATION_ID, createNotification());
            try {
                if (action.equals(ACTION_START_ROOT)) {
                    if (VideoCallRecording.rootMediaProjection != null) {
                        mMediaProjection = VideoCallRecording.rootMediaProjection;
                        VideoCallRecording.rootMediaProjection = null;
                        proceedToStart(MediaRecorder.AudioSource.MIC);
                    } else {
                        Log.e(TAG, "Root MediaProjection is null");
                        stopSelf();
                    }
                } else {
                    int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
                    Intent data = intent.getParcelableExtra(EXTRA_DATA);
                    int audioSource = intent.getIntExtra(EXTRA_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC);
                    startRecording(resultCode, data, audioSource);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recording", e);
                showToast("Error: " + e.getMessage());
                stopSelf();
            }
        } else if (action.equals(ACTION_STOP)) {
            stopRecording();
            stopForeground(true);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startRecording(int resultCode, Intent data, int audioSource) throws IOException {
        if (data != null && data.hasExtra("android.media.projection.extra.EXTRA_MEDIA_PROJECTION")) {
             mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        } else if (resultCode != 0 && data != null) {
             mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        }

        if (mMediaProjection == null) {
            throw new IOException("MediaProjection is null. Permission denied or root bypass failed.");
        }

        proceedToStart(audioSource);
    }

    private void proceedToStart(int audioSource) throws IOException {
        initRecorder(audioSource);

        try {
            mMediaRecorder.prepare();
        } catch (SecurityException e) {
            throw new IOException("SecurityException: Mic permission might be missing in WhatsApp.", e);
        }

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG,
                mScreenWidth, mScreenHeight, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mMediaRecorder.getSurface(), null, null);

        mMediaRecorder.start();
        Log.d(TAG, "Recording started");
        showToast(getString(R.string.recording_started));
    }

    private void initRecorder(int audioSource) throws IOException {
        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setAudioSource(audioSource);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        String fileName = "VideoCall_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".mp4";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WhatsAppEnhancer");

            mVideoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            mMediaRecorder.setOutputFile(getContentResolver().openFileDescriptor(mVideoUri, "rw").getFileDescriptor());
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "WhatsAppEnhancer");
            if (!dir.exists()) dir.mkdirs();
            mOutputPath = new File(dir, fileName).getAbsolutePath();
            mMediaRecorder.setOutputFile(mOutputPath);
        }

        mMediaRecorder.setVideoSize(mScreenWidth, mScreenHeight);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setAudioSamplingRate(44100);
        mMediaRecorder.setAudioEncodingBitRate(128000);
    }

    private void stopRecording() {
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
                mMediaRecorder.reset();
                mMediaRecorder.release();

                String savedPath = (mVideoUri != null) ? mVideoUri.toString() : mOutputPath;
                showToast(getString(R.string.recording_saved, savedPath));
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recorder", e);
            }
            mMediaRecorder = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    private Notification createNotification() {
        createNotificationChannel();

        Intent stopIntent = new Intent(this, VideoRecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.recording_service_notification_title))
                .setContentText(getString(R.string.recording_service_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.recording_service_stop), stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Video Call Recording", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
