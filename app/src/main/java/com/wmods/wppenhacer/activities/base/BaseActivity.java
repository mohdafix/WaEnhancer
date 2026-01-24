package com.wmods.wppenhacer.activities.base;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.wmods.wppenhacer.App;
import com.wmods.wppenhacer.R;
import android.util.TypedValue;

public class BaseActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static int lastCustomColor = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        applyAppTheme();
        super.onCreate(savedInstanceState);
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        applyCustomHeaderColor();
        
        var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        var themeColor = sharedPreferences.getString("app_theme_color", "green");
        if ("custom".equals(themeColor)) {
            int customColor = sharedPreferences.getInt("app_custom_color", Color.parseColor("#4CAF50"));
            lastCustomColor = customColor;
            applyColorToViews(getWindow().getDecorView(), customColor);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    private void applyAppTheme() {
        var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        var themeColor = sharedPreferences.getString("app_theme_color", "green");
        var isAmoled = sharedPreferences.getBoolean("monet_theme", false);

        setTheme(R.style.AppTheme);
        getTheme().applyStyle(rikka.material.preference.R.style.ThemeOverlay_Rikka_Material3_Preference, true);
        getTheme().applyStyle(R.style.ThemeOverlay, true);

        if (isAmoled) {
            getTheme().applyStyle(R.style.ThemeOverlay_Black, true);
        }

        if (!"custom".equals(themeColor)) {
            switch (themeColor) {
                case "blue":
                    getTheme().applyStyle(R.style.ThemeOverlay_MaterialBlue, true);
                    break;
                case "red":
                    getTheme().applyStyle(R.style.ThemeOverlay_MaterialRed, true);
                    break;
                case "yellow":
                    getTheme().applyStyle(R.style.ThemeOverlay_MaterialYellow, true);
                    break;
                case "purple":
                    getTheme().applyStyle(R.style.ThemeOverlay_MaterialPurple, true);
                    break;
                case "green":
                default:
                    getTheme().applyStyle(R.style.ThemeOverlay_MaterialGreen, true);
                    break;
            }
        }
    }

    private void applyCustomHeaderColor() {
        var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        var themeColor = sharedPreferences.getString("app_theme_color", "green");

        int primaryColor;
        int secondaryColor;

        if ("custom".equals(themeColor)) {
            primaryColor = sharedPreferences.getInt("app_custom_color", Color.parseColor("#4CAF50"));
            float[] hsv = new float[3];
            Color.colorToHSV(primaryColor, hsv);
            hsv[2] *= 0.6f;
            secondaryColor = Color.HSVToColor(hsv);
        } else {
            TypedValue typedValue = new TypedValue();
            int primaryAttr = getResources().getIdentifier("colorPrimary", "attr", getPackageName());
            int secondaryAttr = getResources().getIdentifier("colorSecondary", "attr", getPackageName());

            if (primaryAttr != 0 && getTheme().resolveAttribute(primaryAttr, typedValue, true)) {
                primaryColor = typedValue.data;
            } else {
                primaryColor = Color.parseColor("#4CAF50");
            }

            if (secondaryAttr != 0 && getTheme().resolveAttribute(secondaryAttr, typedValue, true)) {
                secondaryColor = typedValue.data;
            } else {
                float[] hsv = new float[3];
                Color.colorToHSV(primaryColor, hsv);
                hsv[2] *= 0.8f;
                secondaryColor = Color.HSVToColor(hsv);
            }
        }

        Window window = getWindow();
        window.setStatusBarColor(secondaryColor);

        View toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{primaryColor, secondaryColor});
            toolbar.setBackground(gradient);
        }

        if ("custom".equals(themeColor)) {
            int customColor = primaryColor;

            View navView = findViewById(R.id.nav_view);
            if (navView instanceof BottomNavigationView bottomNav) {
                ColorStateList colorStateList = new ColorStateList(
                        new int[][]{
                                new int[]{android.R.attr.state_checked},
                                new int[]{-android.R.attr.state_checked}
                        },
                        new int[]{
                                customColor,
                                Color.GRAY
                        }
                );
                bottomNav.setItemIconTintList(colorStateList);
                bottomNav.setItemTextColor(colorStateList);
                bottomNav.setItemRippleColor(ColorStateList.valueOf(customColor).withAlpha(30));
            }
        }
    }

    public static void applyColorToViews(View view, int color) {
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                applyColorToViews(group.getChildAt(i), color);
            }
        }
        
        if (view instanceof MaterialButton button) {
            if (button.getId() == R.id.resetBtn) return;
            button.setBackgroundTintList(ColorStateList.valueOf(color));
        } else if (view instanceof FloatingActionButton fab) {
            fab.setBackgroundTintList(ColorStateList.valueOf(color));
        } else if (view instanceof Button button) {
            button.setBackgroundTintList(ColorStateList.valueOf(color));
        } else if (view instanceof MaterialSwitch materialSwitch) {
            materialSwitch.setThumbTintList(createSwitchColorStateList(color));
            materialSwitch.setTrackTintList(createSwitchColorStateList(color));
        } else if (view instanceof SwitchCompat switchCompat) {
            switchCompat.setThumbTintList(createSwitchColorStateList(color));
            switchCompat.setTrackTintList(createSwitchColorStateList(color));
        } else if (view.getId() == R.id.status || view.getId() == R.id.status2 || view.getId() == R.id.status3) {
            // Cards handled via HomeFragment and getStatusBackground
        }
    }

    private static ColorStateList createSwitchColorStateList(int color) {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{
                        color,
                        Color.LTGRAY
                }
        );
    }

    public static GradientDrawable getStatusBackground(Context context, boolean success) {
        if (!success) {
            return (GradientDrawable) context.getDrawable(R.drawable.gradient_error);
        }

        var sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        var themeColor = sharedPreferences.getString("app_theme_color", "green");
        
        if ("custom".equals(themeColor)) {
            int customColor = sharedPreferences.getInt("app_custom_color", Color.parseColor("#4CAF50"));
            float[] hsv = new float[3];
            Color.colorToHSV(customColor, hsv);
            hsv[2] *= 0.6f;
            int darkerColor = Color.HSVToColor(hsv);
            
            GradientDrawable gd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{customColor, darkerColor});
            gd.setCornerRadius(context.getResources().getDimension(R.dimen.card_radius_large));
            return gd;
        } else {
             return (GradientDrawable) context.getDrawable(R.drawable.gradient_success);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if ("app_theme_color".equals(key) || "changecolor".equals(key) || "monet_theme".equals(key)) {
            recreate();
        } else if ("app_custom_color".equals(key)) {
            int newColor = sharedPreferences.getInt(key, 0);
            if (newColor != lastCustomColor) {
                lastCustomColor = newColor;
                new Handler(Looper.getMainLooper()).postDelayed(this::recreate, 200);
            }
        }
    }
}
