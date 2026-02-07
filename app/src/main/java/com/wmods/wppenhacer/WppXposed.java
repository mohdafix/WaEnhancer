package com.wmods.wppenhacer;

import android.annotation.SuppressLint;
import android.content.ContextWrapper;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.wmods.wppenhacer.activities.MainActivity;
import com.wmods.wppenhacer.xposed.AntiUpdater;
import com.wmods.wppenhacer.xposed.bridge.ScopeHook;
import com.wmods.wppenhacer.xposed.core.FeatureLoader;
import com.wmods.wppenhacer.xposed.downgrade.Patch;
import com.wmods.wppenhacer.xposed.utils.ResId;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WppXposed implements IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {

    private static XSharedPreferences pref;
    private static String MODULE_PATH;
    public static XC_InitPackageResources.InitPackageResourcesParam ResParam;

    public static String getModulePath() {
        return MODULE_PATH;
    }

    @NonNull
    public static XSharedPreferences getPref() {
        if (pref == null) {
            pref = new XSharedPreferences(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID + "_preferences");
            pref.makeWorldReadable();
            pref.reload();
        }
        return pref;
    }

    @SuppressLint("WorldReadableFiles")
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        var packageName = lpparam.packageName;
        var classLoader = lpparam.classLoader;

        if (packageName.equals(BuildConfig.APPLICATION_ID)) {
            XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lpparam.classLoader, "isXposedEnabled", XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(PreferenceManager.class.getName(), lpparam.classLoader, "getDefaultSharedPreferencesMode", XC_MethodReplacement.returnConstant(ContextWrapper.MODE_WORLD_READABLE));
            return;
        }

        AntiUpdater.hookSession(lpparam);

        Patch.handleLoadPackage(lpparam, getPref());

        ScopeHook.hook(lpparam);

        //  AndroidPermissions.hook(lpparam); in tests
        if ((packageName.equals(FeatureLoader.PACKAGE_WPP) && App.isOriginalPackage()) || packageName.equals(FeatureLoader.PACKAGE_BUSINESS)) {
            XposedBridge.log("[•] This package: " + lpparam.packageName);

            // Load features
            FeatureLoader.start(classLoader, getPref(), lpparam.appInfo.sourceDir, lpparam);

            disableSecureFlag();
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        var packageName = resparam.packageName;

        if (!packageName.equals(FeatureLoader.PACKAGE_WPP) && !packageName.equals(FeatureLoader.PACKAGE_BUSINESS))
            return;

        XModuleResources modRes = XModuleResources.createInstance(MODULE_PATH, resparam.res);
        ResParam = resparam;

        for (var field : ResId.string.class.getFields()) {
            var field1 = R.string.class.getField(field.getName());
            field.set(null, resparam.res.addResource(modRes, field1.getInt(null)));
        }

        for (var field : ResId.array.class.getFields()) {
            var field1 = R.array.class.getField(field.getName());
            field.set(null, resparam.res.addResource(modRes, field1.getInt(null)));
        }

        for (var field : ResId.drawable.class.getFields()) {
            var field1 = R.drawable.class.getField(field.getName());
            field.set(null, resparam.res.addResource(modRes, field1.getInt(null)));
        }

        // Tick Styles: replace WhatsApp's stock tick drawables at the resource level.
        // This works regardless of how WhatsApp loads the drawable (setImageResource,
        // setImageDrawable, getDrawable, etc.).
        applyTickStyleReplacements(resparam, modRes);
    }

    /**
     * WhatsApp's 8 stock tick drawable names used for message status indicators.
     */
    private static final String[] TICK_DRAWABLE_NAMES = {
            "message_unsent",
            "message_unsent_onmedia",
            "message_got_receipt_from_server",
            "message_got_receipt_from_server_onmedia",
            "message_got_receipt_from_target",
            "message_got_receipt_from_target_onmedia",
            "message_got_read_receipt_from_target",
            "message_got_read_receipt_from_target_onmedia"
    };

    /**
     * Mapping from delivered tick drawable names to their read counterparts.
     * WhatsApp reuses the delivered drawable and applies a blue tint for read status.
     * We need to know the read counterpart so we can swap drawables instead of tinting.
     */
    private static final String[][] DELIVERED_TO_READ_MAPPING = {
            {"message_got_receipt_from_target", "message_got_read_receipt_from_target"},
            {"message_got_receipt_from_target_onmedia", "message_got_read_receipt_from_target_onmedia"}
    };

    /**
     * Replace WhatsApp's stock tick drawables with custom style variants using
     * Xposed resource replacement. This intercepts at the resource table level
     * so it works no matter how WhatsApp resolves/loads the drawable.
     * The returned drawables ignore color filters and tints so custom tick PNGs
     * display their original colors (not tinted by Monet/custom color themes).
     *
     * For the delivered tick drawables (message_got_receipt_from_target), the
     * TintProofDrawable is created with both delivered and read variants. When
     * WhatsApp tries to tint the delivered tick blue (to indicate read status),
     * OwnMessageStatus will call switchToRead() instead, swapping to our custom
     * read tick PNG.
     */
    private void applyTickStyleReplacements(
            XC_InitPackageResources.InitPackageResourcesParam resparam,
            XModuleResources modRes) {
        try {
            var prefs = getPref();
            prefs.reload();
            String tickStyle = prefs.getString("tick_style", "default");

            if (tickStyle == null || tickStyle.equals("default")) {
                return;
            }

            int replacedCount = 0;
            for (String tickName : TICK_DRAWABLE_NAMES) {
                // Find the module's replacement drawable resource ID
                String moduleFieldName = tickStyle + "_" + tickName;
                int moduleResId;
                try {
                    var field = R.drawable.class.getField(moduleFieldName);
                    moduleResId = field.getInt(null);
                } catch (NoSuchFieldException e) {
                    XposedBridge.log("[Tick Styles] No module drawable: " + moduleFieldName);
                    continue;
                }

                if (moduleResId == 0) continue;

                // Check if this is a delivered tick that has a read counterpart
                String readCounterpart = null;
                for (String[] mapping : DELIVERED_TO_READ_MAPPING) {
                    if (mapping[0].equals(tickName)) {
                        readCounterpart = mapping[1];
                        break;
                    }
                }

                // Resolve the read counterpart resource ID if applicable
                int readResId = 0;
                if (readCounterpart != null) {
                    String readFieldName = tickStyle + "_" + readCounterpart;
                    try {
                        var readField = R.drawable.class.getField(readFieldName);
                        readResId = readField.getInt(null);
                    } catch (NoSuchFieldException e) {
                        XposedBridge.log("[Tick Styles] No read variant drawable: " + readFieldName);
                    }
                }

                // Create a DrawableLoader that returns a tint-proof wrapper.
                // For delivered ticks, include the read variant so we can swap instead of tint.
                final int finalModuleResId = moduleResId;
                final int finalReadResId = readResId;
                final XModuleResources finalModRes = modRes;
                final boolean hasReadPair = readResId != 0;
                try {
                    resparam.res.setReplacement(resparam.packageName, "drawable", tickName,
                            new XResources.DrawableLoader() {
                                @Override
                                public Drawable newDrawable(XResources res, int id) throws Throwable {
                                    Drawable deliveredDrawable = finalModRes.getDrawable(finalModuleResId);
                                    if (hasReadPair) {
                                        Drawable readDrawable = finalModRes.getDrawable(finalReadResId);
                                        return new TintProofDrawable(deliveredDrawable, deliveredDrawable, readDrawable);
                                    }
                                    return new TintProofDrawable(deliveredDrawable);
                                }
                            });
                    replacedCount++;
                    XposedBridge.log("[Tick Styles] Replaced: " + tickName + " -> " + moduleFieldName
                            + (hasReadPair ? " (with read variant)" : ""));
                } catch (Exception e) {
                    XposedBridge.log("[Tick Styles] Failed to replace " + tickName + ": " + e.getMessage());
                }
            }

            if (replacedCount > 0) {
                XposedBridge.log("[Tick Styles] Style '" + tickStyle + "' applied with " + replacedCount + " resource replacements.");
            }
        } catch (Exception e) {
            XposedBridge.log("[Tick Styles] Error applying tick replacements: " + e.getMessage());
        }
    }

    /**
     * A Drawable wrapper that ignores all color filter, tint, and tintList calls.
     * This ensures custom tick PNGs display their original colors exactly as designed,
     * regardless of WhatsApp's internal tinting or WaEnhancer's Monet/custom color features.
     *
     * For the delivered/read tick pair (message_got_receipt_from_target), this drawable
     * holds both the "delivered" and "read" variants. WhatsApp uses a single drawable
     * and applies a blue tint for "read" status — we instead swap between two separate
     * PNGs to show visually distinct delivered vs read ticks without any tinting.
     */
    public static class TintProofDrawable extends Drawable {
        private Drawable wrapped;
        private final Drawable deliveredDrawable;
        private final Drawable readDrawable;
        private boolean isReadState = false;

        /** Simple constructor for tick drawables that have no delivered/read pair (e.g. unsent, server receipt). */
        public TintProofDrawable(Drawable wrapped) {
            this(wrapped, null, null);
        }

        /**
         * Constructor for tick drawables that have a delivered/read pair.
         * @param wrapped The initial drawable to display (delivered variant).
         * @param deliveredDrawable The delivered tick drawable (grey double tick).
         * @param readDrawable The read tick drawable (blue double tick). May be null if no read variant exists.
         */
        public TintProofDrawable(Drawable wrapped, Drawable deliveredDrawable, Drawable readDrawable) {
            this.wrapped = wrapped;
            this.deliveredDrawable = deliveredDrawable;
            this.readDrawable = readDrawable;
            setBounds(wrapped.getBounds());
        }

        /**
         * Check if this drawable has separate delivered and read variants.
         */
        public boolean hasReadVariant() {
            return readDrawable != null;
        }

        /**
         * Switch to the "read" variant drawable (blue double tick).
         * Called by OwnMessageStatus when WhatsApp tries to apply a tint to indicate read status.
         */
        public void switchToRead() {
            if (readDrawable != null && !isReadState) {
                isReadState = true;
                wrapped = readDrawable;
                wrapped.setBounds(getBounds());
                invalidateSelf();
            }
        }

        /**
         * Switch to the "delivered" variant drawable (grey double tick).
         * Called by OwnMessageStatus when the tint is cleared (view recycled to delivered state).
         */
        public void switchToDelivered() {
            if (deliveredDrawable != null && isReadState) {
                isReadState = false;
                wrapped = deliveredDrawable;
                wrapped.setBounds(getBounds());
                invalidateSelf();
            }
        }

        /**
         * Whether this drawable is currently showing the "read" variant.
         */
        public boolean isReadState() {
            return isReadState;
        }

        @Override
        public void draw(Canvas canvas) {
            wrapped.draw(canvas);
        }

        @Override
        public int getIntrinsicWidth() {
            return wrapped.getIntrinsicWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return wrapped.getIntrinsicHeight();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            wrapped.setBounds(bounds);
        }

        @Override
        public void setAlpha(int alpha) {
            wrapped.setAlpha(alpha);
        }

        @Override
        public int getOpacity() {
            return wrapped.getOpacity();
        }

        // Ignore all color filter / tint calls
        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // no-op: preserve original tick colors
        }

        @Override
        public void setColorFilter(int color, PorterDuff.Mode mode) {
            // no-op: preserve original tick colors
        }

        @Override
        public void setTint(int tintColor) {
            // no-op: preserve original tick colors
        }

        @Override
        public void setTintMode(PorterDuff.Mode tintMode) {
            // no-op: preserve original tick colors
        }

        @Override
        public void setTintList(android.content.res.ColorStateList tint) {
            // no-op: preserve original tick colors
        }

        @Override
        public Drawable.ConstantState getConstantState() {
            return wrapped.getConstantState();
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        MODULE_PATH = startupParam.modulePath;
    }


    public void disableSecureFlag() {
        XposedHelpers.findAndHookMethod(Window.class, "setFlags", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = (int) param.args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
                param.args[1] = (int) param.args[1] & ~WindowManager.LayoutParams.FLAG_SECURE;
            }
        });

        XposedHelpers.findAndHookMethod(Window.class, "addFlags", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = (int) param.args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
                if ((int) param.args[0] == 0) {
                    param.setResult(null);
                }
            }
        });
    }

}
