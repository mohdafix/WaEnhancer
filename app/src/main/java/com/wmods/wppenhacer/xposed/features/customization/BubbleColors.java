package com.wmods.wppenhacer.xposed.features.customization;


import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.MonetColorEngine;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.Objects;
import java.util.Properties;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class BubbleColors extends Feature {
    public BubbleColors(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    /**
     * Resolves bubble color with priority: Manual > Monet > CSS/Properties > Default (0)
     * @param isOutgoing true for right/outgoing bubble, false for left/incoming
     * @param manualColor manual color from preferences (0 if not set)
     * @param propertyColor color from CSS properties
     * @param monetEnabled whether Monet theming is enabled
     * @return resolved color (0 means use WhatsApp default)
     */
    private int resolveBubbleColor(boolean isOutgoing, int manualColor, int propertyColor, boolean monetEnabled) {
        // Priority 1: Manual color always takes precedence
        if (manualColor != 0) return manualColor;

        // Priority 2: CSS/Properties color (Theme Manager)
        if (propertyColor != 0) return propertyColor;

        // Priority 3: Monet colors (if enabled and available)
        if (monetEnabled) {
            if (!isOutgoing) {
                return Color.BLACK;
            }
            try {
                int monetColor = isOutgoing
                    ? MonetColorEngine.getBubbleOutgoingColor(Utils.getApplication())
                    : MonetColorEngine.getBubbleIncomingColor(Utils.getApplication());
                if (monetColor != -1) return monetColor;
            } catch (Exception ignored) {}
        }

        // Priority 4: Default (0 = use WhatsApp default)
        return 0;
    }

    @Override
    public void doHook() throws Exception {

        Properties properties = Utils.getProperties(prefs, "custom_css", "custom_filters");

        boolean bubbleColor = prefs.getBoolean("bubble_color", false);
        boolean monetTheme = prefs.getBoolean("monet_theme", false);

        // Enable hook if any bubble customization is active
        if (!bubbleColor && !Objects.equals(properties.getProperty("bubble_colors"), "true") && !monetTheme)
            return;

        // Get manual colors (0 if not set)
        int manualLeftColor = bubbleColor ? prefs.getInt("bubble_left", 0) : 0;
        int manualRightColor = bubbleColor ? prefs.getInt("bubble_right", 0) : 0;

        // Get CSS/property colors
        int propertyLeftColor = Color.parseColor(DesignUtils.checkSystemColor(properties.getProperty("bubble_left", "#00000000")));
        int propertyRightColor = Color.parseColor(DesignUtils.checkSystemColor(properties.getProperty("bubble_right", "#00000000")));

        // Resolve final colors with priority: Manual > Monet > Properties > Default
        int bubbleLeftColor = resolveBubbleColor(false, manualLeftColor, propertyLeftColor, monetTheme);
        int bubbleRightColor = resolveBubbleColor(true, manualRightColor, propertyRightColor, monetTheme);

        var dateWrapper = Unobfuscator.loadBallonDateDrawable(classLoader);

        XposedBridge.hookMethod(dateWrapper, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (BubbleThemes.isBubbleThemeActive) return;
                var drawable = (Drawable) param.getResult();
                if (drawable == null)return;
                var position = (int) param.args[0];
                if (position == 3) {
                    if (bubbleRightColor == 0) return;
                    drawable.setColorFilter(new PorterDuffColorFilter(bubbleRightColor, PorterDuff.Mode.SRC_IN));
                } else {
                    if (bubbleLeftColor == 0) return;
                    drawable.setColorFilter(new PorterDuffColorFilter(bubbleLeftColor, PorterDuff.Mode.SRC_IN));
                }
            }
        });

        var babblon = Unobfuscator.loadBallonBorderDrawable(classLoader);
        XposedBridge.hookMethod(babblon, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (BubbleThemes.isBubbleThemeActive) return;
                var drawable = (Drawable) param.getResult();
                if (drawable == null)return;
                var position = (int) param.args[1];
                if (position == 3) {
                    if (bubbleRightColor == 0) return;
                    drawable.setColorFilter(new PorterDuffColorFilter(bubbleRightColor, PorterDuff.Mode.SRC_IN));
                } else {
                    if (bubbleLeftColor == 0) return;
                    drawable.setColorFilter(new PorterDuffColorFilter(bubbleLeftColor, PorterDuff.Mode.SRC_IN));
                }
            }
        });


        var bubbleDrawableMethod = Unobfuscator.loadBubbleDrawableMethod(classLoader);

        XposedBridge.hookMethod(bubbleDrawableMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (BubbleThemes.isBubbleThemeActive) return;
                var position = (int) param.args[0];
                var draw = (Drawable) param.getResult();
                var right = position == 3;
                if (right) {
                    if (bubbleRightColor == 0) return;
                    draw.setColorFilter(new PorterDuffColorFilter(bubbleRightColor, PorterDuff.Mode.SRC_IN));
                } else {
                    if (bubbleLeftColor == 0) return;
                    draw.setColorFilter(new PorterDuffColorFilter(bubbleLeftColor, PorterDuff.Mode.SRC_IN));
                }
            }
        });

    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Bubble Colors";
    }
}
