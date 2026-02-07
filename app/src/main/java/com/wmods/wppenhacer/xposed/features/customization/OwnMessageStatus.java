package com.wmods.wppenhacer.xposed.features.customization;

import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.WppXposed;
import com.wmods.wppenhacer.xposed.core.Feature;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Custom tick styles for message status indicators.
 *
 * The actual drawable replacement is done in WppXposed.handleInitPackageResources()
 * using Xposed resource replacement (resparam.res.setReplacement). This replaces
 * WhatsApp's stock tick drawables at the resource table level, which works regardless
 * of how WhatsApp loads them (setImageResource, setImageDrawable, getDrawable, etc.).
 *
 * The TintProofDrawable wrapper in WppXposed prevents tinting at the Drawable level.
 * This class adds ImageView-level hooks to also prevent tinting at the View level
 * (setImageTintList, setColorFilter) when custom tick drawables are displayed.
 *
 * Available styles: ab, alien, allo, bbm, bbm2, bpg, circheck, feet, gabface,
 *   gabiflo, gifcon, google, hd, heart, ios, joker, messenger, minions, pacman, twitter
 *
 * WhatsApp's 8 tick drawables replaced:
 *   message_unsent / message_unsent_onmedia
 *   message_got_receipt_from_server / message_got_receipt_from_server_onmedia
 *   message_got_receipt_from_target / message_got_receipt_from_target_onmedia
 *   message_got_read_receipt_from_target / message_got_read_receipt_from_target_onmedia
 */
public class OwnMessageStatus extends Feature {

    public OwnMessageStatus(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    /**
     * Check if the ImageView is currently displaying one of our TintProofDrawable tick icons.
     */
    private static boolean hasTintProofDrawable(ImageView imageView) {
        Drawable d = imageView.getDrawable();
        return d instanceof WppXposed.TintProofDrawable;
    }

    @Override
    public void doHook() throws Throwable {
        String tickStyle = prefs.getString("tick_style", "default");
        if (tickStyle == null || tickStyle.equals("default")) {
            return;
        }

        XposedBridge.log("[Tick Styles] Feature active: style '" + tickStyle + "' — hooking ImageView tint methods");

        // Hook ImageView.setImageTintList to prevent View-level tinting on tick drawables
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageTintList", ColorStateList.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                if (hasTintProofDrawable(iv)) {
                    // Clear any existing tint and block new one
                    param.args[0] = null;
                }
            }
        });

        // Hook ImageView.setColorFilter(ColorFilter) to prevent coloring on tick drawables
        XposedHelpers.findAndHookMethod(ImageView.class, "setColorFilter", ColorFilter.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                if (hasTintProofDrawable(iv)) {
                    param.setResult(null); // block the call entirely
                }
            }
        });

        // Hook ImageView.setColorFilter(int, PorterDuff.Mode) variant
        XposedHelpers.findAndHookMethod(ImageView.class, "setColorFilter", int.class, PorterDuff.Mode.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                if (hasTintProofDrawable(iv)) {
                    param.setResult(null); // block the call entirely
                }
            }
        });

        // Also hook setImageDrawable to clear any pre-existing tint when a TintProofDrawable is set
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable", Drawable.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args[0] instanceof WppXposed.TintProofDrawable) {
                    ImageView iv = (ImageView) param.thisObject;
                    iv.clearColorFilter();
                    iv.setImageTintList(null);
                }
            }
        });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Tick Styles";
    }
}
