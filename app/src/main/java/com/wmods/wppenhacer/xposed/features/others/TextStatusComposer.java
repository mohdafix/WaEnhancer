package com.wmods.wppenhacer.xposed.features.others;

import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.NonNull;
import com.wmods.wppenhacer.views.dialog.SimpleColorPickerDialog;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class TextStatusComposer extends Feature {
    private static final ColorData colorData = new ColorData();

    public TextStatusComposer(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("statuscomposer", false)) return;

        // 1. UI Setup (Color Pickers)
        hookUI();

        // 2. Find the Data Class and Upload Methods
        Class<?> textDataClass;
        try {
            textDataClass = Unobfuscator.loadTextStatusDataClass(classLoader);
        } catch (Exception e) {
            log("Failed to find TextData class: " + e.getMessage());
            return;
        }

        Method[] methodsTextStatus;
        try {
            methodsTextStatus = Unobfuscator.loadTextStatusData(classLoader);
        } catch (Exception e) {
            log("Failed to load methods: " + e.getMessage());
            return;
        }

        if (methodsTextStatus == null || methodsTextStatus.length == 0) return;

        // 3. Hook the Upload Methods
        for (Method method : methodsTextStatus) {
            logDebug("Hooking", Unobfuscator.getMethodDescriptor(method));
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Try to find the TextData object in arguments
                    Object textData = findTextData(param.args, textDataClass);

                    if (textData == null) return;

                    // Log available fields to help debug if it fails again
                    logFields(textData);

                    // Apply Colors to ALL integer fields
                    if (colorData.textColor != -1) {
                        setAllIntFields(textData, colorData.textColor);
                    }
                    if (colorData.backgroundColor != -1) {
                        setAllIntFields(textData, colorData.backgroundColor);
                    }

                    // Reset
                    colorData.textColor = -1;
                    colorData.backgroundColor = -1;
                }
            });
        }
    }

    private void hookUI() {
        try {
            var clazz = WppCore.getTextStatusComposerFragmentClass(classLoader);
            var methodOnCreate = ReflectionUtils.findMethodUsingFilter(clazz, method -> method.getParameterCount() == 2 && method.getParameterTypes()[0] == Bundle.class && method.getParameterTypes()[1] == View.class);

            XposedBridge.hookMethod(methodOnCreate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var activity = WppCore.getCurrentActivity();
                    var viewRoot = (View) param.args[1];
                    var pickerColor = viewRoot.findViewById(Utils.getID("color_picker_btn", "id"));
                    var entry = (EditText) viewRoot.findViewById(Utils.getID("entry", "id"));

                    pickerColor.setOnLongClickListener(v -> {
                        var dialog = new SimpleColorPickerDialog(activity, color -> {
                            try {
                                activity.getWindow().setBackgroundDrawable(new ColorDrawable(color));
                                viewRoot.findViewById(Utils.getID("background","id")).setBackgroundColor(color);
                                var controls = viewRoot.findViewById(Utils.getID("controls", "id"));
                                controls.setBackgroundColor(color);
                                colorData.backgroundColor = color;
                            } catch (Exception e) { log(e); }
                        });
                        dialog.create().setCanceledOnTouchOutside(false);
                        dialog.show();
                        return true;
                    });

                    var textColor = viewRoot.findViewById(Utils.getID("font_picker_btn", "id"));
                    textColor.setOnLongClickListener(v -> {
                        var dialog = new SimpleColorPickerDialog(activity, color -> {
                            colorData.textColor = color;
                            entry.setTextColor(color);
                        });
                        dialog.create().setCanceledOnTouchOutside(false);
                        dialog.show();
                        return true;
                    });
                }
            });
        } catch (Exception e) {
            log("UI Hook Failed: " + e.getMessage());
        }
    }

    // Helper to find the object in arguments
    private Object findTextData(Object[] args, Class<?> targetClass) {
        for (Object arg : args) {
            if (arg != null && targetClass.isInstance(arg)) {
                return arg;
            }
            // Sometimes it's inside a list or bundle
            if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item != null && targetClass.isInstance(item)) return item;
                }
            }
        }
        return null;
    }

    // Debug helper
    private void logFields(Object obj) {
        if (obj == null) return;
        log("=== Fields of " + obj.getClass().getName() + " ===");
        for (Field f : obj.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            try {
                log(f.getName() + ": " + f.get(obj));
            } catch (Exception ignored) {}
        }
    }

    // Brute force setter
    private void setAllIntFields(Object obj, int value) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    // Only overwrite if it looks like a color (0, -1, or the default black)
                    int current = f.getInt(obj);
                    if (current == 0 || current == -1 || current == -16777216) {
                        f.setInt(obj, value);
                        log("Forced field " + f.getName() + " = " + value);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Text Status Composer";
    }

    public static class ColorData {
        public int textColor = -1;
        public int backgroundColor = -1;
    }
}
