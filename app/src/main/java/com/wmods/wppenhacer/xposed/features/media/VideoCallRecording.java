package com.wmods.wppenhacer.xposed.features.media;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.ResultReceiver;
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
    private static android.app.Activity currentActivity;

    public VideoCallRecording(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("video_call_screen_rec", false)) {
            return;
        }
        hookActivity();
        hookCallEvents();
    }

    private void hookActivity() {
        try {
            Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", classLoader);
            XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    currentActivity = (Activity) param.thisObject;
                }
            });
            XposedBridge.hookAllMethods(activityClass, "onActivityResult", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int requestCode = (int) param.args[0];
                    int resultCode = (int) param.args[1];
                    Intent data = (Intent) param.args[2];
                    if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
                        try {
                            VideoRecordingService.startService(FeatureLoader.mApp, resultCode, data);
                            XposedBridge.log("VideoCallRecording: Video recording service started via user consent");
                        } catch (Exception e) {
                            XposedBridge.log("VideoCallRecording: Error starting recording service: " + e.getMessage());
                        }
                    }
                }
            });
            XposedBridge.log("VideoCallRecording: Activity hooks applied");
        } catch (Exception e) {
            XposedBridge.log("VideoCallRecording: Activity hook failed: " + e.getMessage());
        }
    }

    private void hookClientManager() {
        try {
            Class<?> managerClass = XposedHelpers.findClass("android.media.projection.MediaProjectionManager", classLoader);
            XposedBridge.hookAllMethods(managerClass, "createProjection", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args.length >= 4 && param.args[3] instanceof Boolean) {
                        param.args[3] = true;
                        XposedBridge.log("VideoCallRecording: Client hook - set isPrivileged to true");
                    }
                }
            });
            XposedBridge.log("VideoCallRecording: Client hook applied");
        } catch (Exception e) {
            XposedBridge.log("VideoCallRecording: Client hook failed: " + e.getMessage());
        }
    }

    private void hookCallEvents() {
        try {
            var clsCallEventCallback = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "VoiceServiceEventCallback");
            if (clsCallEventCallback != null) {
                XposedBridge.hookAllMethods(clsCallEventCallback, "soundPortCreated", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object callInfo = XposedHelpers.callMethod(param.thisObject, "getCallInfo");
                        if (isVideoCall(callInfo)) {
                            XposedBridge.log("WaEnhancer: Video call detected");
                            triggerRootBypass();
                        }
                    }
                });

                XposedBridge.hookAllMethods(clsCallEventCallback, "fieldstatsReady", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        VideoRecordingService.stopService(FeatureLoader.mApp);
                    }
                });
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Video hooks failed: " + e.getMessage());
        }
    }

    private boolean isVideoCall(Object callInfo) {
        if (callInfo == null) return false;
        try {
            if ((boolean) XposedHelpers.callMethod(callInfo, "isVideoCall")) return true;
        } catch (Throwable ignored) {}
        try {
            if (XposedHelpers.getBooleanField(callInfo, "videoEnabled")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void triggerRootBypass() {
        try {
            XposedBridge.log("WaEnhancer: [1] Getting MediaProjectionManager");
            MediaProjectionManager manager = (MediaProjectionManager) FeatureLoader.mApp.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) {
                XposedBridge.log("WaEnhancer: Manager is null");
                return;
            }

            if (currentActivity != null) {
                Intent intent = manager.createScreenCaptureIntent();
                if (intent != null) {
                    currentActivity.startActivityForResult(intent, 1001);
                    XposedBridge.log("WaEnhancer: Started screen capture intent for user consent");
                } else {
                    XposedBridge.log("WaEnhancer: createScreenCaptureIntent returned null");
                    showToast("Video recording: Unable to create capture intent");
                }
            } else {
                XposedBridge.log("WaEnhancer: No current activity to start intent");
                showToast("Video recording: No active activity");
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Error triggering recording: " + e.getMessage());
            showToast("Video recording failed: " + e.getMessage());
        }
    }

    private void showToast(String message) {
        new Handler(FeatureLoader.mApp.getMainLooper()).post(() ->
            Toast.makeText(FeatureLoader.mApp, message, Toast.LENGTH_SHORT).show()
        );
    }

    private Object findServiceByType(MediaProjectionManager manager) {
        try {
            for (java.lang.reflect.Field f : manager.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType().getName().contains("IMediaProjectionManager")) {
                    return f.get(manager);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "VideoCallRecording";
    }

    // This hook MUST be active in LSPosed "System Framework" for Silent Recording
    public static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !lpparam.packageName.equals("android")) return;

        try {
            Class<?> serviceClass = null;
            try {
                serviceClass = Class.forName("com.android.server.media.projection.MediaProjectionManagerService", false, ClassLoader.getSystemClassLoader());
                XposedBridge.log("VideoCallRecording: System Hook - Found with Class.forName");
            } catch (Exception e) {
                XposedBridge.log("VideoCallRecording: System Hook - Failed to find projection class: " + e.getMessage());
            }
            if (serviceClass == null) {
                try {
                    serviceClass = Class.forName("com.android.server.media.MediaProjectionManagerService", false, ClassLoader.getSystemClassLoader());
                    XposedBridge.log("VideoCallRecording: System Hook - Found media class with Class.forName");
                } catch (Exception e) {
                    XposedBridge.log("VideoCallRecording: System Hook - Failed to find media class: " + e.getMessage());
                }
            }
            if (serviceClass == null) {
                XposedBridge.log("VideoCallRecording: System Hook - MediaProjectionManagerService class not found");
                return;
            }
            XposedBridge.log("VideoCallRecording: System Hook - Found " + serviceClass.getName());

            // Hook 1: createProjection
            // Hook Context.checkCallingOrSelfPermission to bypass MANAGE_MEDIA_PROJECTION check
            try {
                Class<?> contextClass = Class.forName("android.content.Context", false, ClassLoader.getSystemClassLoader());
                XposedBridge.hookAllMethods(contextClass, "checkCallingPermission", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String permission = (String) param.args[0];
                        if ("android.permission.MANAGE_MEDIA_PROJECTION".equals(permission)) {
                            XposedBridge.log("VideoCallRecording: Bypassing MANAGE_MEDIA_PROJECTION permission check");
                            param.setResult(0); // PERMISSION_GRANTED
                        }
                    }
                });
                XposedBridge.log("VideoCallRecording: Hooked Context.checkCallingPermission on " + contextClass.getName());
            } catch (Exception e) {
                XposedBridge.log("VideoCallRecording: Failed to hook Context: " + e.getMessage());
            }

            XposedBridge.log("VideoCallRecording: System Hook - Hooking createProjection on " + serviceClass.getName());
            XposedBridge.hookAllMethods(serviceClass, "createProjection", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("VideoCallRecording: System Hook - createProjection called, args length: " + param.args.length);
                    for (int i = 0; i < param.args.length; i++) {
                        XposedBridge.log("VideoCallRecording: System Hook - arg" + i + ": " + param.args[i]);
                    }
                    if (param.args.length > 1) {
                        String pkg = (String) param.args[1];
                        XposedBridge.log("VideoCallRecording: System Hook - pkg: " + pkg);
                        if ("com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg) || BuildConfig.APPLICATION_ID.equals(pkg)) {
                            if (param.args.length >= 4) {
                                XposedBridge.log("VideoCallRecording: System Hook - Setting isPrivileged to true for " + pkg);
                                param.args[3] = true; // isPrivileged = true
                                XposedBridge.log("VideoCallRecording: System Hook - Privileged granted to " + pkg);
                            } else {
                                XposedBridge.log("VideoCallRecording: System Hook - Not enough args to set privileged");
                            }
                        } else {
                            XposedBridge.log("VideoCallRecording: System Hook - Pkg not matching: " + pkg);
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.log("VideoCallRecording: System Hook - createProjection result: " + param.getResult());
                }
            });

            // Hook 2: createScreenCaptureIntent
            XposedBridge.hookAllMethods(serviceClass, "createScreenCaptureIntent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String callingPackage = (String) param.args[0];
                    if (callingPackage != null && (callingPackage.equals("com.whatsapp") || callingPackage.equals("com.whatsapp.w4b") || callingPackage.equals(BuildConfig.APPLICATION_ID))) {
                        ResultReceiver callback = (ResultReceiver) param.args[2];
                        param.setResult(null);

                        Object service = param.thisObject;
                        int uid = (int) param.args[1];

                        // Find method
                        java.lang.reflect.Method createProjMethod = null;
                        for (java.lang.reflect.Method m : service.getClass().getDeclaredMethods()) {
                            if (m.getName().equals("createProjection")) {
                                Class<?>[] p = m.getParameterTypes();
                                if (p.length == 4 && p[0] == int.class && p[1] == String.class && p[2] == int.class && p[3] == boolean.class) {
                                    createProjMethod = m;
                                    break;
                                }
                            }
                        }

                        if (createProjMethod != null) {
                            createProjMethod.setAccessible(true);
                            // Pass true to bypass
                            Object projection = createProjMethod.invoke(service, uid, callingPackage, 0, true);

                            if (projection != null) {
                                Intent resultIntent = new Intent();
                                Object binder = XposedHelpers.callMethod(projection, "asBinder");
                                resultIntent.putExtra("android.media.projection.extra.EXTRA_MEDIA_PROJECTION", (Parcelable) binder);

                                Bundle b = new Bundle();
                                b.putParcelable("data", resultIntent);
                                if (callback != null) callback.send(Activity.RESULT_OK, b);
                            }
                        }
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("VideoCallRecording: system_server hook failed: " + t.getMessage());
        }
    }
}
