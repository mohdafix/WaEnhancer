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
 * This class adds ImageView-level hooks to:
 *   1. Prevent tinting at the View level (setImageTintList, setColorFilter)
 *   2. Swap between delivered/read tick drawables when WhatsApp applies a tint to
 *      indicate read status (instead of just blocking the tint).
 *
 * WhatsApp uses a single drawable for both delivered and read double-ticks, and applies
 * a blue color tint (via setImageTintList) to indicate read status. Since we block all
 * tinting, we instead swap the underlying drawable in TintProofDrawable between the
 * delivered and read variants.
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
     * Get the TintProofDrawable from an ImageView, if present.
     * Returns null if the ImageView doesn't hold a TintProofDrawable.
     */
    private static WppXposed.TintProofDrawable getTintProofDrawable(ImageView imageView) {
        Drawable d = imageView.getDrawable();
        if (d instanceof WppXposed.TintProofDrawable) {
            return (WppXposed.TintProofDrawable) d;
        }
        return null;
    }

    @Override
    public void doHook() throws Throwable {
        String tickStyle = prefs.getString("tick_style", "default");
        if (tickStyle == null || tickStyle.equals("default")) {
            return;
        }

        XposedBridge.log("[Tick Styles] Feature active: style '" + tickStyle + "' — hooking ImageView tint methods");

        // Hook ImageView.setImageTintList to:
        // 1. Swap between delivered/read drawables when WhatsApp applies a tint
        // 2. Block the actual tint from being applied
        //
        // WhatsApp calls setImageTintList(dateView.getTextColors()) on the tick ImageView.
        // For read messages, the text color is blue; for delivered, it's grey.
        // Instead of tinting, we swap to our separate read/delivered PNG.
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageTintList", ColorStateList.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                WppXposed.TintProofDrawable tpd = getTintProofDrawable(iv);
                if (tpd == null) return;

                ColorStateList tint = (ColorStateList) param.args[0];

                if (tpd.hasReadVariant()) {
                    if (tint != null) {
                        // WhatsApp is applying a tint — this happens for both delivered and read.
                        // We need to determine if this is a "read" tint (blue) or "delivered" tint (grey).
                        int tintColor = tint.getDefaultColor();
                        if (isBlueishColor(tintColor)) {
                            tpd.switchToRead();
                        } else {
                            tpd.switchToDelivered();
                        }
                    } else {
                        // Tint cleared — revert to delivered state
                        tpd.switchToDelivered();
                    }
                }

                // Block the actual tint in all cases
                param.args[0] = null;
            }
        });

        // Hook ImageView.setColorFilter(ColorFilter) to prevent coloring on tick drawables
        XposedHelpers.findAndHookMethod(ImageView.class, "setColorFilter", ColorFilter.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                if (getTintProofDrawable(iv) != null) {
                    param.setResult(null); // block the call entirely
                }
            }
        });

        // Hook ImageView.setColorFilter(int, PorterDuff.Mode) variant
        XposedHelpers.findAndHookMethod(ImageView.class, "setColorFilter", int.class, PorterDuff.Mode.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                if (getTintProofDrawable(iv) != null) {
                    param.setResult(null); // block the call entirely
                }
            }
        });

        // Also hook setImageDrawable to clear any pre-existing tint when a TintProofDrawable is set,
        // and reset the delivered/read state when a new drawable is assigned (view recycling).
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

    /**
     * Determine if a color is "blue-ish" — used to detect WhatsApp's read receipt blue tint.
     * WhatsApp's read receipt blue is typically in the range of #53bdeb or similar.
     * We check if the blue channel is dominant over red and green.
     */
    private static boolean isBlueishColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        // Blue channel must be significantly higher than red, and at least as high as green.
        // This catches WhatsApp's read blue (#53bdeb: r=83, g=189, b=235) and similar blues/cyans
        // while excluding greys (where r ≈ g ≈ b) and other non-blue colors.
        return b > r + 30 && b >= g;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Tick Styles";
    }
}
