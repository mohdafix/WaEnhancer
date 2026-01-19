package com.wmods.wppenhacer.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.wmods.wppenhacer.services.VideoRecordingService;

public class ScreenRecordingStarterActivity extends Activity {
    private static final int REQUEST_CODE = 1001;
    private MediaProjectionManager mProjectionManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        // Start the system dialog to request screen capture permission
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                // Permission granted, start the service with the token
                VideoRecordingService.startService(this, resultCode, data);
            }
            finish();
        }
    }
}