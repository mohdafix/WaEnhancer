package com.wmods.wppenhacer.xposed.features.media;

import android.graphics.Bitmap;
import android.graphics.RecordingCanvas;
import android.os.Build;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.features.general.Others;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;

import org.json.JSONObject;

import java.util.List;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MediaQuality extends Feature {
    public MediaQuality(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        var videoQuality = prefs.getBoolean("videoquality", false);
        var imageQuality = prefs.getBoolean("imagequality", false);
        var maxSize = (int) prefs.getFloat("video_limit_size", 110);
        var realResolution = prefs.getBoolean("video_real_resolution", false);

        // Max video size
        Others.propsInteger.put(3185, maxSize);
        Others.propsInteger.put(3656, maxSize);
        Others.propsInteger.put(4155, maxSize);
        Others.propsInteger.put(3659, maxSize);
        Others.propsInteger.put(4685, maxSize);
        Others.propsInteger.put(596, maxSize);

        // Enable Media Quality selection for Stories
        var hookMediaQualitySelection = Unobfuscator.loadMediaQualitySelectionMethod(classLoader);
        XposedBridge.hookMethod(hookMediaQualitySelection, XC_MethodReplacement.returnConstant(true));

        if (videoQuality) {

            Others.propsBoolean.put(5549, true); // Use bitrate from json to force video high quality

            var jsonProperty = Unobfuscator.loadPropsJsonMethod(classLoader);

            AtomicReference<XC_MethodHook.Unhook> jsonPropertyHook = new AtomicReference<>();

            var unhooked = XposedBridge.hookMethod(jsonProperty, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var value = ReflectionUtils.getArg(param.args, Integer.class, 0);
                    if (value == 5550) {
                        JSONObject videoBitrateData = new JSONObject();
                        String[] resolutions = {"360", "480", "720", "1080"};
                        for (String resolution : resolutions) {
                            JSONObject resolutionData = new JSONObject();
                            resolutionData.put("min_bitrate", 3000);
                            resolutionData.put("max_bitrate", 96000);
                            resolutionData.put("null_bitrate", 96000);
                            resolutionData.put("min_bandwidth", 1);
                            resolutionData.put("max_bandwidth", 1);
                            videoBitrateData.put(resolution, resolutionData);
                        }
                        param.setResult(videoBitrateData);
                    } else if (value == 9705) {
                        param.setResult(new JSONObject());
                    }
                }
            });
            jsonPropertyHook.set(unhooked);

            // Hook for Dimension Swapping (Fix Horizontal Video)
            var videoMethod = Unobfuscator.loadMediaQualityVideoMethod2(classLoader);
            final var mediaFields = Unobfuscator.loadMediaQualityOriginalVideoFields(classLoader);
            final var mediaTranscodeParams = Unobfuscator.loadMediaQualityVideoFields(classLoader);
            
            XposedBridge.hookMethod(videoMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object resizeVideo = param.getResult(); // The transcode params object
                    
                    // Logic to determine if high resolution is active
                    boolean isHighResolution;
                    boolean isEnum = false;
                    Enum enumObj = ReflectionUtils.getArg(param.args, Enum.class, 0);
                    
                    if (enumObj == null) {
                         // Fallback logic usually index 1 is resolution type int?
                         Object arg1 = param.args.length > 1 ? param.args[1] : null;
                         isHighResolution = arg1 instanceof Integer && (Integer) arg1 == 3; 
                    } else {
                        isEnum = true;
                        // Check if enum matches RESOLUTION_1080P or similar high res constant
                        // We do a loose check here or strictly match name
                        isHighResolution = enumObj.name().contains("1080") || enumObj.name().contains("HD");
                    }

                    // Only apply swap if we are tampering with resolution (realResolution is active)
                    // or if we just want to enforce corectness for safety. 
                    // The user code snippet implies: if (isHighResolution && realResolution) 
                    // But actually, the swap logic is useful regardless if the default logic fails.
                    // We will stick to the logic provided in the user's snippet which seems to be:
                    if (isHighResolution && realResolution) { 
                        int width, height, rotation;

                        if (mediaFields.isEmpty()) {
                            // Newer/different version logic using JSON or map?
                            if (isEnum) {
                                // Fallback reading from args?
                                // The user code snippet reads from index 0, 1, 2 of found integers?
                                var intParams = ReflectionUtils.findInstancesOfType(param.args, Integer.class);
                                if (intParams.size() >= 3) {
                                    width = intParams.get(0).second;
                                    height = intParams.get(1).second; // Actually might be intParams.get(1)
                                    // The snippet had: width=0.second, height2=1.second, height=2.second (rotation?)
                                    // height here means rotation
                                    rotation = intParams.get(2).second;
                                } else {
                                    return; // Cannot determine
                                }
                            } else {
                                JSONObject fields = (JSONObject) XposedHelpers.callMethod(param.args[0], "A00"); // Obfuscated call
                                width = fields.getInt("widthPx");
                                height = fields.getInt("heightPx");
                                rotation = fields.getInt("rotationAngle");
                            }
                        } else {
                            // Standard reflection field access
                            width = mediaFields.get("widthPx").getInt(param.args[0]);
                            height = mediaFields.get("heightPx").getInt(param.args[0]);
                            rotation = mediaFields.get("rotationAngle").getInt(param.args[0]);
                        }

                        var targetWidthField = mediaTranscodeParams.get("targetWidth");
                        var targetHeightField = mediaTranscodeParams.get("targetHeight");
                        
                        // Swap logic
                        boolean swap = (rotation == 90 || rotation == 270);
                        
                        // The user snippet says:
                        // targetHeightField.setInt(out, inverted ? width : height2);
                        // targetWidthField.setInt(out, inverted ? height2 : width);
                        // So if swapped (inverted), targetHeight = width (large), targetWidth = height (small)
                        // This makes it portrait.
                        
                        targetWidthField.setInt(resizeVideo, swap ? height : width);
                        targetHeightField.setInt(resizeVideo, swap ? width : height);
                    }

                    if (MediaQuality.this.prefs.getBoolean("video_maxfps", false)) {
                       var frameRateField = mediaTranscodeParams.get("frameRate");
                       if (frameRateField != null) frameRateField.setInt(resizeVideo, 60);
                    }
                }
            });


            // HD video must be sent in maximum resolution (up to 4K)
            if (realResolution) {
                Others.propsInteger.put(594, 8000);
                Others.propsInteger.put(12852, 8000);
            } else {
                Others.propsInteger.put(594, 0);
                Others.propsInteger.put(12852, 0);
            }

             // Non-HD video must be sent in HD resolution
             Others.propsInteger.put(4686, 5000);
             Others.propsInteger.put(3654, 5000);
             Others.propsInteger.put(3183, 5000); // Stories
             Others.propsInteger.put(4685, 5000); // Stories

            // Max bitrate
            Others.propsInteger.put(3755, 96000);
            Others.propsInteger.put(3756, 96000);
            Others.propsInteger.put(3757, 96000);
            Others.propsInteger.put(3758, 96000);

        }

        if (imageQuality) {

            // Image Max Size
            int maxImageSize = 50 * 1024; // 50MB
            Others.propsInteger.put(1577, maxImageSize);
            Others.propsInteger.put(6030, maxImageSize);
            Others.propsInteger.put(2656, maxImageSize);

            // Image Quality
            int imageMaxQuality = 100;
            Others.propsInteger.put(1581, imageMaxQuality);
            Others.propsInteger.put(1575, imageMaxQuality);
            Others.propsInteger.put(1578, imageMaxQuality);
            Others.propsInteger.put(6029, imageMaxQuality);
            Others.propsInteger.put(2655, imageMaxQuality);

            // HD image must be sent in maximum 4K resolution
            Others.propsBoolean.put(6033, true);
            Others.propsInteger.put(2654, 6000); // Only HD images
            Others.propsInteger.put(6032, 6000); // Only HD images

            // Non-HD image must be sent in HD resolution
            Others.propsInteger.put(1580, 4160);
            Others.propsInteger.put(1574, 4160);
            Others.propsInteger.put(1576, 4160);
            Others.propsInteger.put(12902, 4160);

            // Prevent crashes in Media preview
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                XposedHelpers.findAndHookMethod(RecordingCanvas.class, "throwIfCannotDraw", Bitmap.class, XC_MethodReplacement.DO_NOTHING);
            }

        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Media Quality";
    }

}