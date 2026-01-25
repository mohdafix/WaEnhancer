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

public class MonetColorEngine {

    @ColorInt
    public static int getSystemAccentColor(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Dark mode: lighter pastel (200) for better contrast
                // Light mode: vibrant mid-tone (600)
                int resId = isNightMode(context) 
                    ? android.R.color.system_accent1_200 
                    : android.R.color.system_accent1_600;
                return context.getColor(resId);
            } catch (Exception e) {
                return getColorFromAttr(context, android.R.attr.colorAccent);
            }
        }
        return -1;
    }

    @ColorInt
    public static int getSystemPrimaryColor(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Dark mode: lighter primary (200)
                // Light mode: darker primary (600)
                int resId = isNightMode(context)
                    ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_600;
                return context.getColor(resId);
            } catch (Exception e) {
                return getColorFromAttr(context, android.R.attr.colorPrimary);
            }
        }
        return -1;
    }

    @ColorInt
    public static int getSystemSecondaryColor(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Dark mode: lighter secondary (200)
                // Light mode: standard secondary (500)
                int resId = isNightMode(context)
                    ? android.R.color.system_accent2_200
                    : android.R.color.system_accent2_500;
                return context.getColor(resId);
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Outgoing bubble: accent1_500 (primary accent tone) used consistently
                // for better contrast/readability as per user preference.
                return context.getColor(android.R.color.system_accent1_500);
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    @ColorInt
    public static int getBubbleIncomingColor(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Incoming: Tertiary/Secondary logic
                // Dark mode: 200 (Pastel)
                // Light mode: 500 (Vibrant) - changed to accent2 for better distinction if desired, 
                // but sticking to accent3 as per original implementation intent
                int resId = isNightMode(context)
                    ? android.R.color.system_accent3_200
                    : android.R.color.system_accent3_500;
                return context.getColor(resId);
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
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