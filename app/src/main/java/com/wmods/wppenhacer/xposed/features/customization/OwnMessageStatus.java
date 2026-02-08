package com.wmods.wppenhacer.xposed.features.customization;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.WppXposed;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.utils.ResId;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Custom tick styles for message status indicators.
 *
 * The actual drawable replacement is done in
 * WppXposed.handleInitPackageResources()
 * using Xposed resource replacement (resparam.res.setReplacement). This
 * replaces
 * WhatsApp's stock tick drawables at the resource table level, which works
 * regardless
 * of how WhatsApp loads them (setImageResource, setImageDrawable, getDrawable,
 * etc.).
 *
 * WhatsApp has SEPARATE resources for delivered
 * (message_got_receipt_from_target) and
 * read (message_got_read_receipt_from_target) ticks. It loads the correct
 * drawable via
 * setImageDrawable() for each status type, then applies a tint via
 * setImageTintList().
 *
 * The TintProofDrawable wrapper in WppXposed prevents tinting at the Drawable
 * level.
 * This class adds ImageView-level hooks to block tinting at the View level too
 * (setImageTintList, setColorFilter), ensuring custom tick PNGs display as-is.
 *
 * Available styles: ab, alien, allo, bbm, bbm2, bpg, circheck, feet, gabface,
 * gabiflo, gifcon, google, hd, heart, ios, joker, messenger, minions, pacman,
 * twitter
 *
 * WhatsApp's 8 tick drawables replaced:
 * message_unsent / message_unsent_onmedia
 * message_got_receipt_from_server / message_got_receipt_from_server_onmedia
 * message_got_receipt_from_target / message_got_receipt_from_target_onmedia
 * message_got_read_receipt_from_target /
 * message_got_read_receipt_from_target_onmedia
 */
public class OwnMessageStatus extends Feature {

    public OwnMessageStatus(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    /**
     * Check if an ImageView holds a TintProofDrawable.
     */
    private static boolean hasTintProofDrawable(ImageView imageView) {
        return imageView.getDrawable() instanceof WppXposed.TintProofDrawable;
    }

    @Override
    public void doHook() throws Throwable {
        String tickStyle = prefs.getString("tick_style", "default");
        if (tickStyle == null || tickStyle.equals("default")) {
            return;
        }

        XposedBridge.log("[Tick Styles] Feature active: style '" + tickStyle + "' — hooking ImageView tint methods");

        // Hook Resources.getDrawable to wrap our forwarded ticks in TintProofDrawable
        // Used to create TintProofDrawable wrapper for XResForwarder-replaced resources
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getDrawable", int.class, Resources.Theme.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            int id = (int) param.args[0];
                            if (WppXposed.tickResourceMap.containsKey(id)) {
                                Drawable d = (Drawable) param.getResult();
                                if (d != null && !(d instanceof WppXposed.TintProofDrawable)) {
                                    param.setResult(
                                            new WppXposed.TintProofDrawable(d, WppXposed.tickResourceMap.get(id)));
                                }
                            }
                        }
                    });
        } catch (Throwable e) {
            XposedBridge.log("[Tick Styles] Error hooking Resources.getDrawable: " + e.getMessage());
        }

        // Debug hook REMOVED to prevent log spam - verified resource names found:
        // msg_status_client, msg_status_server_receive, ic_read
        /*
         * try {
         * XposedHelpers.findAndHookMethod(Resources.class, "getDrawable", int.class,
         * Resources.Theme.class,
         * new XC_MethodHook() {
         * 
         * @Override
         * protected void afterHookedMethod(MethodHookParam param) throws Throwable {
         * int id = (int) param.args[0];
         * try {
         * Resources res = (Resources) param.thisObject;
         * String name = res.getResourceEntryName(id);
         * if (name != null) {
         * String lowerName = name.toLowerCase();
         * if (lowerName.contains("mark") || lowerName.contains("status") ||
         * lowerName.contains("indicator") || lowerName.contains("read") ||
         * lowerName.contains("receipt") || lowerName.contains("check") ||
         * lowerName.contains("tick") || lowerName.contains("msg") ||
         * lowerName.startsWith("ic_")) {
         * 
         * // Filter out some common noise if needed, but for now keep it broad
         * XposedBridge.log("[Tick Styles] DEBUG: getDrawable(" + id + ") -> " + name);
         * }
         * }
         * } catch (Exception e) {
         * // ignore
         * }
         * }
         * });
         * } catch (Throwable e) {
         * XposedBridge.log("[Tick Styles] Error hooking debug getDrawable: " +
         * e.getMessage());
         * }
         */

        // Hook ImageView.setImageTintList to block tinting on custom tick drawables.
        // WhatsApp calls this after setImageDrawable to apply color tinting.
        // In modern WhatsApp (2.26.x+), they often use a single resource
        // (message_got_receipt_from_target)
        // for both Delivered and Read states, distinguishing them ONLY by tint (Grey vs
        // Blue).
        // Since we wrap drawables in TintProofDrawable to preserve custom PNG colors,
        // we must manually detect the blue tint and swap to our custom 'read' PNG.
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageTintList", ColorStateList.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                Drawable d = iv.getDrawable();
                if (d instanceof WppXposed.TintProofDrawable) {
                    WppXposed.TintProofDrawable tpd = (WppXposed.TintProofDrawable) d;
                    ColorStateList csl = (ColorStateList) param.args[0];
                    if (csl != null) {
                        int color = csl.getDefaultColor();
                        detectAndSwapStatus(iv, tpd, color, tickStyle);
                    }
                    param.args[0] = null;
                }
            }
        });

        // Hook ImageView.setColorFilter(ColorFilter) to prevent coloring on tick
        // drawables
        XposedHelpers.findAndHookMethod(ImageView.class, "setColorFilter", ColorFilter.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ImageView iv = (ImageView) param.thisObject;
                if (hasTintProofDrawable(iv)) {
                    param.setResult(null);
                }
            }
        });

        // Hook ImageView.setColorFilter(int, PorterDuff.Mode) variant
        XposedHelpers.findAndHookMethod(ImageView.class, "setColorFilter", int.class, PorterDuff.Mode.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        ImageView iv = (ImageView) param.thisObject;
                        Drawable d = iv.getDrawable();
                        if (d instanceof WppXposed.TintProofDrawable) {
                            WppXposed.TintProofDrawable tpd = (WppXposed.TintProofDrawable) d;
                            int color = (int) param.args[0];
                            detectAndSwapStatus(iv, tpd, color, tickStyle);
                            param.setResult(null);
                        }
                    }
                });

        // Hook setImageDrawable to clear any pre-existing tint when a TintProofDrawable
        // is set.
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable", Drawable.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args[0] instanceof WppXposed.TintProofDrawable) {
                    ImageView iv = (ImageView) param.thisObject;
                    WppXposed.TintProofDrawable tpd = (WppXposed.TintProofDrawable) param.args[0];

                    // Check if there's already a tint active that should have swapped this
                    ColorStateList csl = iv.getImageTintList();
                    if (csl != null && isWhatsAppBlue(csl.getDefaultColor())) {
                        detectAndSwapStatus(iv, tpd, csl.getDefaultColor(), tickStyle);
                    }

                    iv.clearColorFilter();
                    iv.setImageTintList(null);
                }
            }
        });
    }

    private void detectAndSwapStatus(ImageView iv, WppXposed.TintProofDrawable tpd, int color, String style) {
        String name = tpd.tickName;
        if (name == null)
            return;

        // Status transition detection based on color tint
        if (isWhatsAppBlue(color)) {
            // If it's a Delivered icon but we see a Blue tint -> Swap to Read icon
            if (name.contains("receipt_from_target") && !name.contains("read")) {
                String readName = name.replace("receipt_from_target", "read_receipt_from_target");
                swapTo(iv, style, readName);
            }
        } else if (isWhatsAppGray(color)) {
            // If it's a Read icon but we see a Gray tint -> Swap to Delivered icon
            // (fallback)
            if (name.contains("read_receipt_from_target")) {
                String delName = name.replace("read_receipt_from_target", "receipt_from_target");
                swapTo(iv, style, delName);
            }
        }
    }

    private final ThreadLocal<Boolean> isSwapping = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };

    private void swapTo(ImageView iv, String style, String tickName) {
        if (isSwapping.get())
            return;
        isSwapping.set(true);
        try {
            Drawable oldD = iv.getDrawable();
            if (oldD instanceof WppXposed.TintProofDrawable) {
                if (tickName.equals(((WppXposed.TintProofDrawable) oldD).tickName)) {
                    return; // Already correct
                }
            }

            Rect bounds = (oldD != null) ? new Rect(oldD.getBounds()) : null;
            String fieldName = style + "_" + tickName;
            int resId = ResId.drawable.class.getField(fieldName).getInt(null);
            if (resId != 0) {
                Drawable newD = iv.getContext().getResources().getDrawable(resId);
                WppXposed.TintProofDrawable tpd = new WppXposed.TintProofDrawable(newD, tickName);
                if (bounds != null && !bounds.isEmpty()) {
                    tpd.setBounds(bounds);
                }
                iv.setImageDrawable(tpd);
                iv.invalidate();
                logDebug("Immediate tick swap: " + tickName);
            }
        } catch (Exception e) {
            logDebug("Swap failed: " + e.getMessage());
        } finally {
            isSwapping.set(false);
        }
    }

    private boolean isWhatsAppBlue(int color) {
        // WhatsApp Read Receipt Blue is #34B7F1 (H=200, S=79, V=95)
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0] >= 180 && hsv[0] <= 220 && hsv[1] > 0.3f;
    }

    private boolean isWhatsAppGray(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1] < 0.2f; // Low saturation = Gray
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Tick Styles";
    }
}
