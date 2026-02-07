package com.wmods.wppenhacer.xposed.features.media;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.BuildConfig;
import com.wmods.wppenhacer.services.VideoRecordingService;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;

import org.luckypray.dexkit.query.enums.StringMatchType;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class VideoCallRecording extends Feature {

    public static MediaProjection rootMediaProjection;
    private static Activity currentActivity;
    private static View overlayView;
    private static boolean isRecordingActive = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VideoCallRecording(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("video_call_screen_rec", false)) return;
        
        XposedBridge.log("VideoCallRecording: [DEBUG] Feature enabled");
        hookActivity();
        hookCallEvents();
    }

    private void hookActivity() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    currentActivity = (Activity) param.thisObject;
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult", int.class, int.class, Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    XposedBridge.log("VideoCallRecording: [DEBUG] Activity.onActivityResult. Request: " + requestCode + " Result: " + resultCode);
                    if (requestCode == 2024 && resultCode == Activity.RESULT_OK && data != null) {
                        if (FeatureLoader.mApp != null) {
                            VideoRecordingService.startService(FeatureLoader.mApp, resultCode, data);
                            isRecordingActive = true;
                            updateOverlayButton();
                        }
                    }
                }
            });
        } catch (Exception e) {
            XposedBridge.log("VideoCallRecording: HookActivity error: " + e.getMessage());
        }
    }

    private void hookCallEvents() {
        try {
            var clsCallEventCallback = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "VoiceServiceEventCallback");
            if (clsCallEventCallback == null) return;

            XC_MethodHook callStartHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object callInfo = XposedHelpers.callMethod(param.thisObject, "getCallInfo");
                    if (isVideoCall(callInfo)) {
                        XposedBridge.log("VideoCallRecording: [DEBUG] Video call detected, showing button");
                        mainHandler.post(() -> showOverlay());
                        if (prefs.getBoolean("video_call_auto_rec", false)) {
                            mainHandler.postDelayed(() -> startRecording(), 4000);
                        }
                    }
                }
            };

            XposedBridge.hookAllMethods(clsCallEventCallback, "soundPortCreated", callStartHook);
            XposedBridge.hookAllMethods(clsCallEventCallback, "videoPortCreated", callStartHook);
            
            XposedBridge.hookAllMethods(clsCallEventCallback, "fieldstatsReady", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    mainHandler.post(() -> {
                        removeOverlay();
                        stopRecording();
                    });
                }
            });
        } catch (Exception e) {
            XposedBridge.log("VideoCallRecording: HookCallEvents error: " + e.getMessage());
        }
    }

    private boolean isVideoCall(Object callInfo) {
        if (callInfo == null) return false;
        try {
            return (boolean) XposedHelpers.callMethod(callInfo, "isVideoCall");
        } catch (Throwable t) {
            try { return XposedHelpers.getBooleanField(callInfo, "videoEnabled"); } catch (Throwable ignored) {}
        }
        return false;
    }

    private void showOverlay() {
        if (overlayView != null) return;
        
        Context context = (currentActivity != null) ? currentActivity : FeatureLoader.mApp;
        if (context == null) return;

        mainHandler.post(() -> {
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) return;

                TextView btn = new TextView(context);
                btn.setText("REC");
                btn.setTextColor(Color.WHITE);
                btn.setBackgroundColor(Color.parseColor("#CCFF0000"));
                btn.setPadding(40, 25, 40, 25);
                btn.setGravity(Gravity.CENTER);
                btn.setTextSize(16);
                
                int[] windowTypes = { 1003, 2038, 2002, 2003, 2007 };

                for (int type : windowTypes) {
                    try {
                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                type,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                PixelFormat.TRANSLUCENT);

                        params.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
                        params.x = 0;
                        params.y = 0;

                        btn.setOnTouchListener(new View.OnTouchListener() {
                            private int initialX, initialY;
                            private float initialTouchX, initialTouchY;

                            @Override
                            public boolean onTouch(View v, MotionEvent event) {
                                switch (event.getAction()) {
                                    case MotionEvent.ACTION_DOWN:
                                        initialX = params.x;
                                        initialY = params.y;
                                        initialTouchX = event.getRawX();
                                        initialTouchY = event.getRawY();
                                        return true;
                                    case MotionEvent.ACTION_MOVE:
                                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                                        try { wm.updateViewLayout(overlayView, params); } catch (Exception ignored) {}
                                        return true;
                                    case MotionEvent.ACTION_UP:
                                        if (Math.abs(event.getRawX() - initialTouchX) < 20 && Math.abs(event.getRawY() - initialTouchY) < 20) toggleRecording();
                                        return true;
                                }
                                return false;
                            }
                        });

                        overlayView = btn;
                        wm.addView(overlayView, params);
                        updateOverlayButton();
                        XposedBridge.log("VideoCallRecording: [DEBUG] Overlay added with type " + type);
                        break;
                    } catch (Exception e) {
                        overlayView = null;
                    }
                }
            } catch (Exception e) {
                XposedBridge.log("VideoCallRecording: [ERROR] showOverlay: " + e.getMessage());
            }
        });
    }

    private void updateOverlayButton() {
        if (overlayView instanceof TextView) {
            TextView tv = (TextView) overlayView;
            tv.setText(isRecordingActive ? "STOP" : "REC");
            tv.setBackgroundColor(isRecordingActive ? Color.parseColor("#CC00FF00") : Color.parseColor("#CCFF0000"));
        }
    }

    private void toggleRecording() {
        XposedBridge.log("VideoCallRecording: [DEBUG] toggleRecording active=" + isRecordingActive);
        if (isRecordingActive) stopRecording(); else startRecording();
    }

    private void startRecording() {
        if (isRecordingActive || currentActivity == null) return;
        try {
            android.media.projection.MediaProjectionManager manager = (android.media.projection.MediaProjectionManager) 
                    FeatureLoader.mApp.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            currentActivity.startActivityForResult(manager.createScreenCaptureIntent(), 2024);
        } catch (Exception e) {
            XposedBridge.log("VideoCallRecording: [ERROR] StartRecording: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (!isRecordingActive) return;
        VideoRecordingService.stopService(FeatureLoader.mApp);
        isRecordingActive = false;
        updateOverlayButton();
    }

    private void removeOverlay() {
        if (overlayView != null) {
            try {
                WindowManager wm = (WindowManager) ( (currentActivity != null) ? currentActivity : FeatureLoader.mApp ).getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(overlayView);
            } catch (Exception ignored) {}
            overlayView = null;
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "VideoCallRecording";
    }

    public static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !"android".equals(lpparam.packageName)) return;
        
        try {
            // 1. MediaProjection hooks
            Class<?> mpmService = XposedHelpers.findClassIfExists("com.android.server.media.projection.MediaProjectionManagerService", lpparam.classLoader);
            if (mpmService == null) mpmService = XposedHelpers.findClassIfExists("com.android.server.media.MediaProjectionManagerService", lpparam.classLoader);
            if (mpmService != null) {
                XposedBridge.hookAllMethods(mpmService, "createProjection", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 4) {
                            String pkg = (String) param.args[1];
                            if ("com.whatsapp".equals(pkg) || "com.wmods.wppenhacer".equals(pkg) || BuildConfig.APPLICATION_ID.equals(pkg)) {
                                param.args[3] = true; // Privileged
                            }
                        }
                    }
                });
            }

            // 2. WindowManager hooks
            Class<?> wmsClass = XposedHelpers.findClassIfExists("com.android.server.wm.WindowManagerService", lpparam.classLoader);
            if (wmsClass != null) {
                XposedBridge.hookAllMethods(wmsClass, "checkAddPermission", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(0); // ADD_OKAY - Nuclear bypass
                    }
                });
            }

            // 3. NUCLEAR BYPASS: Hook ActiveServices to stop FGS crashes
            Class<?> activeServices = XposedHelpers.findClassIfExists("com.android.server.am.ActiveServices", lpparam.classLoader);
            if (activeServices != null) {
                XposedBridge.hookAllMethods(activeServices, "validateForegroundServiceType", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object serviceRecord = param.args[0];
                        String pkg = (String) XposedHelpers.getObjectField(serviceRecord, "packageName");
                        if (pkg != null && (pkg.contains("com.whatsapp") || pkg.contains("wppenhacer"))) {
                            XposedBridge.log("VideoCallRecording: [SYSTEM] Bypassing FGS validation for " + pkg);
                            param.setResult(null); // Return early, skip exception!
                        }
                    }
                });
            }

            // 4. Permission hooks
            Class<?> permService = XposedHelpers.findClassIfExists("com.android.server.pm.permission.PermissionManagerServiceImpl", lpparam.classLoader);
            if (permService == null) permService = XposedHelpers.findClassIfExists("com.android.server.pm.permission.PermissionManagerService", lpparam.classLoader);
            if (permService != null) {
                XposedBridge.hookAllMethods(permService, "checkPermission", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length >= 2 && param.args[1] != null) {
                            String pkg = param.args[1].toString();
                            if (pkg.contains("com.whatsapp") || pkg.contains("wppenhacer")) {
                                param.setResult(0); // PERMISSION_GRANTED - Force everything
                            }
                        }
                    }
                });
            }

            // 5. AppOps hooks
            Class<?> appOpsService = XposedHelpers.findClassIfExists("com.android.server.appop.AppOpsService", lpparam.classLoader);
            if (appOpsService != null) {
                XposedBridge.hookAllMethods(appOpsService, "checkOperation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String pkg = (String) param.args[2];
                        if (pkg != null && (pkg.contains("com.whatsapp") || pkg.contains("wppenhacer"))) {
                            param.setResult(0); // MODE_ALLOWED
                        }
                    }
                });
                XposedBridge.hookAllMethods(appOpsService, "noteOperation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String pkg = (String) param.args[2]; // arg indexes might vary by version, but pkg is usually here
                        if (pkg != null && (pkg.contains("com.whatsapp") || pkg.contains("wppenhacer"))) {
                            param.setResult(0); // MODE_ALLOWED
                        }
                    }
                });
            }

        } catch (Throwable t) {
            XposedBridge.log("VideoCallRecording: [SYSTEM ERROR] " + t.getMessage());
        }
    }
}
