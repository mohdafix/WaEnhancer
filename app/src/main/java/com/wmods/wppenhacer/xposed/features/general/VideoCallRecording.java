package com.wmods.wppenhacer.xposed.features.general;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.BuildConfig;
import com.wmods.wppenhacer.services.VideoRecordingService;
import com.wmods.wppenhacer.xposed.core.Feature;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class VideoCallRecording extends Feature {

    public VideoCallRecording(@NonNull ClassLoader loader, @NonNull XSharedPreferences pref) {
        super(loader, pref);
    }

    @Override
    public void doHook() throws Throwable {
        String voipActivityClass = "com.whatsapp.voipcalling.VoipActivityV2";
        
        try {
            XposedHelpers.findAndHookMethod(voipActivityClass, classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    Intent intent = activity.getIntent();
                    
                    // Detect video call
                    boolean isVideoCall = intent.getBooleanExtra("is_video_call", true); 
                    
                    if (isVideoCall) {
                        prefs.reload();
                        boolean useRoot = prefs.getBoolean("screen_recording_use_root", false);
                        
                        if (useRoot) {
                            XposedBridge.log("VideoCallRecording: Starting in Root Mode");
                            VideoRecordingService.startService(activity, Activity.RESULT_OK, new Intent());
                        } else {
                            XposedBridge.log("VideoCallRecording: Starting in Non-Root Mode");
                            Intent starter = new Intent();
                            starter.setClassName(BuildConfig.APPLICATION_ID, "com.wmods.wppenhacer.activities.ScreenRecordingStarterActivity");
                            starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(starter);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(voipActivityClass, classLoader, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    VideoRecordingService.stopService(activity);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("VideoCallRecording: Failed to hook VoipActivity: " + t.getMessage());
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "VideoCallRecording";
    }

    public static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("android")) return;

        try {
            XposedHelpers.findAndHookMethod("com.android.server.media.projection.MediaProjectionManagerService", lpparam.classLoader,
                    "createScreenCaptureIntent", String.class, int.class, ResultReceiver.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String callingPackage = (String) param.args[0];
                            if (callingPackage != null && (callingPackage.equals("com.whatsapp") || callingPackage.equals("com.whatsapp.w4b"))) {
                                XposedBridge.log("VideoCallRecording: Bypassing MediaProjection permission for " + callingPackage);
                                
                                ResultReceiver messenger = (ResultReceiver) param.args[2];
                                param.setResult(null);

                                Object service = param.thisObject;
                                int uid = (int) param.args[1];
                                
                                Object projection = XposedHelpers.callMethod(service, "createProjection", 
                                        uid, callingPackage, 0, false);
                                
                                Intent resultData = new Intent();
                                XposedHelpers.callMethod(resultData, "putExtra", "android.media.projection.extra.EXTRA_MEDIA_PROJECTION", 
                                        XposedHelpers.callMethod(projection, "asBinder"));
                                
                                Bundle b = new Bundle();
                                b.putParcelable("data", resultData);
                                
                                messenger.send(Activity.RESULT_OK, b);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("VideoCallRecording: Failed to hook system_server: " + t.getMessage());
        }
    }
}
