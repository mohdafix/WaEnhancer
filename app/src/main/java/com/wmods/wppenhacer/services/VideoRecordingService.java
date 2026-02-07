package com.wmods.wppenhacer.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.wmods.wppenhacer.BuildConfig;
import com.wmods.wppenhacer.xposed.features.media.VideoCallRecording;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

public class VideoRecordingService extends Service {
    private static final String TAG = "WaEnhancer-Rec";
    private static final String CHANNEL_ID = "video_recording_channel";
    private static final int NOTIFICATION_ID = 2024;

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE";
    public static final String EXTRA_DATA = "EXTRA_DATA";

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;

    private int mScreenWidth;
    private int mScreenHeight;
    private int mScreenDensity;
    private Uri mVideoUri;
    private boolean isRecording = false;

    public static void startService(Context context, int resultCode, Intent data) {
        XposedBridge.log("VideoRecordingService: [DEBUG] Preparing to start service from " + context.getPackageName());
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, VideoRecordingService.class.getName()));
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_DATA, data);
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            XposedBridge.log("VideoRecordingService: [DEBUG] startService command sent successfully");
        } catch (Exception e) {
            XposedBridge.log("VideoRecordingService: [ERROR] Failed to send startService: " + e.getMessage());
        }
    }

    public static void stopService(Context context) {
        XposedBridge.log("VideoRecordingService: [DEBUG] stopService called");
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, VideoRecordingService.class.getName()));
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception e) {
            XposedBridge.log("VideoRecordingService: [ERROR] Failed to send stopService: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        updateDisplayMetrics();
    }

    private void updateDisplayMetrics() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
        mScreenDensity = metrics.densityDpi;

        if (mScreenWidth % 2 != 0) mScreenWidth--;
        if (mScreenHeight % 2 != 0) mScreenHeight--;
        Log.i(TAG, "Metrics: " + mScreenWidth + "x" + mScreenHeight + " d=" + mScreenDensity);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand: " + action);
        if (ACTION_START.equals(action)) {
            startForegroundNotification();
            handleStart(intent);
        } else if (ACTION_STOP.equals(action)) {
            stopRecording();
            stopForeground(true);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startForegroundNotification() {
        createNotificationChannel();
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void handleStart(Intent intent) {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);

        if (data != null) {
            Log.i(TAG, "Creating MediaProjection");
            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, data);
        }

        if (mMediaProjection == null) {
            Log.e(TAG, "MediaProjection is null!");
            stopSelf();
            return;
        }

        mMediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "MediaProjection onStop");
                stopRecording();
            }
        }, new Handler(Looper.getMainLooper()));

        try {
            initRecorder();
            Log.i(TAG, "Creating VirtualDisplay");
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG,
                    mScreenWidth, mScreenHeight, mScreenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mMediaRecorder.getSurface(), null, null);
            
            Log.i(TAG, "MediaRecorder start");
            mMediaRecorder.start();
            isRecording = true;
            showToast("Video Recording started");
        } catch (Exception e) {
            Log.e(TAG, "Start failed: " + e.getMessage(), e);
            showToast("Failed to start video recording: " + e.getMessage());
            stopRecording();
            stopSelf();
        }
    }

    private void initRecorder() throws IOException {
        Log.i(TAG, "initRecorder");
        mMediaRecorder = new MediaRecorder();
        
        boolean audioEnabled = true;
        try {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        } catch (Exception e) {
            Log.d(TAG, "VOICE_COMM fallback to MIC");
            try {
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            } catch (Exception e2) {
                Log.w(TAG, "No audio source available");
                audioEnabled = false;
            }
        }
        
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        if (audioEnabled) {
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mMediaRecorder.setAudioSamplingRate(44100);
            mMediaRecorder.setAudioEncodingBitRate(128000);
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "VideoCall_" + timestamp + ".mp4";
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WaEnhancer");
            mVideoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (mVideoUri != null) {
                FileDescriptor fd = getContentResolver().openFileDescriptor(mVideoUri, "rw").getFileDescriptor();
                mMediaRecorder.setOutputFile(fd);
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "WaEnhancer");
            if (!dir.exists()) dir.mkdirs();
            mMediaRecorder.setOutputFile(new File(dir, fileName).getAbsolutePath());
        }

        mMediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mScreenWidth, mScreenHeight);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        Log.i(TAG, "MediaRecorder prepare (audio=" + audioEnabled + ")");
        mMediaRecorder.prepare();
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        Log.i(TAG, "stopRecording");

        try {
            if (mMediaRecorder != null) {
                mMediaRecorder.stop();
                mMediaRecorder.release();
            }
            showToast("Recording saved to Movies/WaEnhancer");
        } catch (Exception e) {
            Log.e(TAG, "Stop error: " + e.getMessage());
            if (mVideoUri != null) getContentResolver().delete(mVideoUri, null, null);
        } finally {
            mMediaRecorder = null;
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
            if (mMediaProjection != null) {
                mMediaProjection.stop();
                mMediaProjection = null;
            }
        }
    }

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent();
        stopIntent.setComponent(new ComponentName(BuildConfig.APPLICATION_ID, "com.wmods.wppenhacer.services.VideoRecordingService"));
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, 
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WaEnhancer Recording")
                .setContentText("Video call recording in progress...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Video Recording", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
