package com.wmods.wppenhacer.xposed.features.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

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
    public static final String ACTION_START_ROOT_INTERNAL = "com.wmods.wppenhacer.action.START_VIDEO_RECORDING_ROOT";

    public VideoCallRecording(@NonNull ClassLoader loader, @NonNull XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("video_call_screen_rec", false)) {
            return;
        }
        
        registerRootReceiver();
        hookCallEvents();
    }

    private void registerRootReceiver() {
        try {
            IntentFilter filter = new IntentFilter(ACTION_START_ROOT_INTERNAL);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (rootMediaProjection != null) {
                        XposedBridge.log("WaEnhancer: Root bridge received, starting service");
                        VideoRecordingService.startServiceRoot(context);
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FeatureLoader.mApp.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                FeatureLoader.mApp.registerReceiver(receiver, filter);
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Root receiver error: " + e.getMessage());
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
                        if (callInfo != null) {
                            boolean isVideoCall = false;
                            try {
                                isVideoCall = (boolean) XposedHelpers.callMethod(callInfo, "isVideoCall");
                            } catch (Throwable t) {
                                try {
                                    isVideoCall = XposedHelpers.getBooleanField(callInfo, "isVideoCall");
                                } catch (Throwable ignored) {}
                            }
                            if (isVideoCall) {
                                XposedBridge.log("WaEnhancer: Video call started, triggering recorder");
                                startVideoRecorder(FeatureLoader.mApp);
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(clsCallEventCallback, "fieldstatsReady", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // Stop recording safely
                        VideoRecordingService.stopService(FeatureLoader.mApp);
                    }
                });
            }
        } catch (Exception e) {
            XposedBridge.log("WaEnhancer: Video hooks failed: " + e.getMessage());
        }

        try {
            var voipActivityClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.Contains, "VoipActivity");
            if (voipActivityClass != null) {
                XposedHelpers.findAndHookMethod(voipActivityClass, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        VideoRecordingService.stopService(FeatureLoader.mApp);
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private void startVideoRecorder(Context context) {
        prefs.reload();
        boolean isRootMode = prefs.getBoolean("screen_recording_use_root", false);

        if (isRootMode) {
            XposedBridge.log("WaEnhancer: Video - Root Mode flow active");
        } else {
            XposedBridge.log("WaEnhancer: Video - Non-Root Mode launching permission activity");
            Intent intent = new Intent();
            intent.setClassName(BuildConfig.APPLICATION_ID, "com.wmods.wppenhacer.activities.ScreenRecordingStarterActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "VideoCallRecording";
    }

    public static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !lpparam.packageName.equals("android")) return;

        try {
            XposedHelpers.findAndHookMethod("com.android.server.media.projection.MediaProjectionManagerService", lpparam.classLoader,
                    "createScreenCaptureIntent", String.class, int.class, ResultReceiver.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String callingPackage = (String) param.args[0];
                            if (callingPackage != null && (callingPackage.equals("com.whatsapp") || callingPackage.equals("com.whatsapp.w4b"))) {
                                XposedBridge.log("VideoCallRecording: Bypassing permission for " + callingPackage);
                                
                                ResultReceiver callback = (ResultReceiver) param.args[2];
                                param.setResult(null);

                                Object service = param.thisObject;
                                int uid = (int) param.args[1];
                                
                                Object projection = XposedHelpers.callMethod(service, "createProjection", 
                                        uid, callingPackage, 0, false);
                                
                                Intent resultIntent = new Intent();
                                XposedHelpers.callMethod(resultIntent, "putExtra", "android.media.projection.extra.EXTRA_MEDIA_PROJECTION", 
                                        XposedHelpers.callMethod(projection, "asBinder"));
                                
                                Bundle b = new Bundle();
                                b.putParcelable("data", resultIntent);
                                
                                callback.send(Activity.RESULT_OK, b);

                                try {
                                    rootMediaProjection = (MediaProjection) projection;
                                    Intent broadcast = new Intent(ACTION_START_ROOT_INTERNAL);
                                    broadcast.setPackage(BuildConfig.APPLICATION_ID);
                                    Context systemContext = (Context) XposedHelpers.getObjectField(service, "mContext");
                                    systemContext.sendBroadcast(broadcast);
                                } catch (Exception e) {
                                    XposedBridge.log("VideoCallRecording: System bridge error: " + e.getMessage());
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("VideoCallRecording: system_server hook failed: " + t.getMessage());
        }
    }
}
