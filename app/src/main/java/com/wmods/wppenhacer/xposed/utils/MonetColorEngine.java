package com.wmods.wppenhacer.xposed.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.RequiresApi;

import androidx.core.graphics.ColorUtils;
import de.robv.android.xposed.XposedBridge;

public class MonetColorEngine {

    @ColorInt
    public static int getSystemAccentColor(Context context) {
        if (Utils.xprefs != null)
            Utils.xprefs.reload();

        boolean customEnabled = Utils.xprefs != null && Utils.xprefs.getBoolean("monet_custom_color_enabled", false);
        if (customEnabled) {
            int customColor = Utils.xprefs.getInt("monet_custom_color", 0xFF25D366);
            int shade = isNightMode(context) ? 200 : 600;
            return generateShade(customColor, shade);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                String palette = Utils.xprefs != null ? Utils.xprefs.getString("monet_palette", "accent1") : "accent1";
                String colorName = "system_" + palette + (isNightMode(context) ? "_200" : "_600");
                int resId = context.getResources().getIdentifier(colorName, "color", "android");
                if (resId != 0) {
                    return context.getColor(resId);
                }

                // Fallback to legacy logic if dynamic lookup fails
                int fallbackResId = isNightMode(context)
                        ? android.R.color.system_accent1_200
                        : android.R.color.system_accent1_600;
                return context.getColor(fallbackResId);
            } catch (Exception e) {
                return getColorFromAttr(context, android.R.attr.colorAccent);
            }
        }
        return -1;
    }

    @ColorInt
    public static int getSystemPrimaryColor(Context context) {
        return getSystemAccentColor(context); // Primarily uses the same accent logic for consistency
    }

    @ColorInt
    public static int getSystemSecondaryColor(Context context) {
        if (Utils.xprefs != null)
            Utils.xprefs.reload();
        if (Utils.xprefs != null && Utils.xprefs.getBoolean("monet_custom_color_enabled", false)) {
            int customColor = Utils.xprefs.getInt("monet_custom_color", 0xFF25D366);
            // Secondary is desaturated and slightly shifted
            float[] hsv = new float[3];
            Color.colorToHSV(customColor, hsv);
            hsv[1] *= 0.5f; // Desaturate
            int secondaryBase = Color.HSVToColor(hsv);
            return generateShade(secondaryBase, isNightMode(context) ? 200 : 500);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                String palette = Utils.xprefs != null ? Utils.xprefs.getString("monet_palette", "accent1") : "accent1";
                // If main is accent1, secondary is accent2. Otherwise follow a shift.
                String secondaryPalette = palette.equals("accent1") ? "accent2" : "accent1";

                String colorName = "system_" + secondaryPalette + (isNightMode(context) ? "_200" : "_500");
                int resId = context.getResources().getIdentifier(colorName, "color", "android");
                if (resId != 0)
                    return context.getColor(resId);

                int fallbackResId = isNightMode(context)
                        ? android.R.color.system_accent2_200
                        : android.R.color.system_accent2_500;
                return context.getColor(fallbackResId);
            } catch (Exception e) {
                return getColorFromAttr(context, android.R.attr.colorSecondary);
            }
        }
        return -1;
    }

    @ColorInt
    public static int getSystemBackgroundColor(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (isNightMode(context)) {
                    return Color.BLACK;
                } else {
                    return context.getColor(android.R.color.system_neutral1_50);
                }
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    @ColorInt
    public static int getBubbleOutgoingColor(Context context) {
        if (Utils.xprefs != null)
            Utils.xprefs.reload();
        if (Utils.xprefs != null && Utils.xprefs.getBoolean("monet_custom_color_enabled", false)) {
            int customColor = Utils.xprefs.getInt("monet_custom_color", 0xFF25D366);
            return generateShade(customColor, 500);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                String palette = Utils.xprefs != null ? Utils.xprefs.getString("monet_palette", "accent1") : "accent1";
                String colorName = "system_" + palette + "_500";
                int resId = context.getResources().getIdentifier(colorName, "color", "android");
                if (resId != 0)
                    return context.getColor(resId);

                return context.getColor(android.R.color.system_accent1_500);
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    @ColorInt
    public static int getBubbleIncomingColor(Context context) {
        if (Utils.xprefs != null)
            Utils.xprefs.reload();
        if (Utils.xprefs != null && Utils.xprefs.getBoolean("monet_custom_color_enabled", false)) {
            int customColor = Utils.xprefs.getInt("monet_custom_color", 0xFF25D366);
            float[] hsv = new float[3];
            Color.colorToHSV(customColor, hsv);
            hsv[0] = (hsv[0] + 45) % 360; // Less drastic hue shift
            hsv[1] = Math.max(hsv[1], 0.8f); // High saturation for incoming bubbles
            int incomingBase = Color.HSVToColor(hsv);
            return generateShade(incomingBase, isNightMode(context) ? 200 : 500);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                String palette = Utils.xprefs != null ? Utils.xprefs.getString("monet_palette", "accent1") : "accent1";
                // Shift palette for incoming color (accent3 if primary is accent1)
                String incomingPalette = palette.equals("accent1") ? "accent3" : "neutral2";

                String colorName = "system_" + incomingPalette + (isNightMode(context) ? "_200" : "_500");
                int resId = context.getResources().getIdentifier(colorName, "color", "android");
                if (resId != 0)
                    return context.getColor(resId);

                int fallbackResId = isNightMode(context)
                        ? android.R.color.system_accent3_200
                        : android.R.color.system_accent3_500;
                return context.getColor(fallbackResId);
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    @ColorInt
    private static int generateShade(int baseColor, int shade) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(baseColor, hsl);

        // Boost saturation to 90% of original or min 85% for high vibrancy
        hsl[1] = Math.max(hsl[1], 0.85f);

        switch (shade) {
            case 200 -> {
                // Dark Mode Accent: Lowered to 50% lightness.
                // This ensures the color is "deep" and provides excellent contrast for white
                // icons.
                hsl[2] = 0.50f;
            }
            case 500 -> {
                // Outgoing Bubbles: 45% lightness for a solid, premium look.
                hsl[2] = 0.45f;
            }
            case 600 -> {
                // Light Mode Accent: 45% lightness.
                hsl[2] = 0.45f;
                hsl[1] = 1.0f; // Pure saturation for light mode punch
            }
        }
        return ColorUtils.HSLToColor(hsl);
    }

    private static boolean isNightMode(Context context) {
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    @ColorInt
    private static int getColorFromAttr(Context context, int attr) {
        TypedValue typedValue = new TypedValue();
        try {
            // Attempt to use the activity context if possible, or the app context
            // But here we receive a context. Ensure it has a theme.
            if (context.getTheme().resolveAttribute(attr, typedValue, true)) {
                // Check if it's a color resource or raw color
                if (typedValue.resourceId != 0) {
                    return context.getColor(typedValue.resourceId);
                }
                return typedValue.data;
            }
        } catch (Exception e) {
            Log.e("MonetEngine", "Failed to resolve attr: " + e.getMessage());
        }
        return -1;
    }
}