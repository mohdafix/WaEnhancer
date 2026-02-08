package com.wmods.wppenhacer;

import android.annotation.SuppressLint;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.XModuleResources;
import android.content.res.XResources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.InputStream;

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
            XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lpparam.classLoader, "isXposedEnabled",
                    XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(PreferenceManager.class.getName(), lpparam.classLoader,
                    "getDefaultSharedPreferencesMode",
                    XC_MethodReplacement.returnConstant(ContextWrapper.MODE_WORLD_READABLE));
            return;
        }

        AntiUpdater.hookSession(lpparam);

        Patch.handleLoadPackage(lpparam, getPref());

        ScopeHook.hook(lpparam);

        // AndroidPermissions.hook(lpparam); in tests
        if ((packageName.equals(FeatureLoader.PACKAGE_WPP) && App.isOriginalPackage())
                || packageName.equals(FeatureLoader.PACKAGE_BUSINESS)) {
            // Load features
            FeatureLoader.start(classLoader, getPref(), lpparam.appInfo.sourceDir, lpparam);

            disableSecureFlag();
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam)
            throws Throwable {
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

        // Notification Icon: replace WhatsApp's "notifybar" drawable at the resource
        // level.
        applyNotificationIconReplacement(resparam);
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
            "message_got_read_receipt_from_target_onmedia",
            "pip_message_got_receipt_from_target",
            "pip_message_got_receipt_from_target_onmedia",
            "pip_message_got_read_receipt_from_target",
            "pip_message_got_read_receipt_from_target_onmedia"
    };

    /**
     * Replace WhatsApp's stock tick drawables with custom style variants using
     * Xposed resource replacement. This intercepts at the resource table level
     * so it works no matter how WhatsApp resolves/loads the drawable.
     * The returned drawables ignore color filters and tints so custom tick PNGs
     * display their original colors (not tinted by Monet/custom color themes).
     *
     * WhatsApp has SEPARATE resources for delivered
     * (message_got_receipt_from_target)
     * and read (message_got_read_receipt_from_target) ticks, so each gets its own
     * TintProofDrawable with the correct PNG. No drawable swapping needed — just
     * tint blocking.
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
                    continue;
                }

                if (moduleResId == 0)
                    continue;

                // Check if the target resource exists in the app before attempting replacement
                int targetResId = resparam.res.getIdentifier(tickName, "drawable", resparam.packageName);
                if (targetResId == 0) {
                    XposedBridge.log("[Tick Styles] Resource not found in WhatsApp: " + tickName);
                    continue; // Skip if resource doesn't exist in this WhatsApp version
                }

                final int finalModuleResId = moduleResId;
                final XModuleResources finalModRes = modRes;
                final String finalTickName = tickName;
                try {
                    resparam.res.setReplacement(resparam.packageName, "drawable", tickName,
                            new XResources.DrawableLoader() {
                                @Override
                                public Drawable newDrawable(XResources res, int id) throws Throwable {
                                    Drawable drawable = finalModRes.getDrawable(finalModuleResId);
                                    return new TintProofDrawable(drawable, finalTickName);
                                }
                            });
                    replacedCount++;
                } catch (Exception e) {
                    // Silently fail if replacement fails - avoids spamming logs for minor resource
                    // mismatches
                }
            }

            if (replacedCount > 0) {
                // Summary log instead of per-item logs
            }
        } catch (Exception e) {
            XposedBridge.log("[Tick Styles] Error applying tick replacements: " + e.getMessage());
        }
    }

    /**
     * Replace WhatsApp's "notifybar" drawable with a custom notification icon
     * loaded from the module APK's assets. Uses the same Xposed resource
     * replacement technique as tick styles, so it intercepts ALL 60+ code paths
     * that reference WhatsApp's notification icon resource.
     *
     * The icons are stored in assets/notifybar/notifybar_{0-23}.png rather than
     * as compiled drawable resources, so we load them via AssetManager and wrap
     * in a BitmapDrawable.
     */
    private void applyNotificationIconReplacement(
            XC_InitPackageResources.InitPackageResourcesParam resparam) {
        try {
            var prefs = getPref();
            prefs.reload();
            String iconStyle = prefs.getString("notification_icon", "default");

            if (iconStyle == null || iconStyle.equals("default")) {
                return;
            }

            if (MODULE_PATH == null) {
                XposedBridge.log("[NotificationIcon] Module path is null, skipping");
                return;
            }

            // Pre-load the bitmap from assets so we can verify it works
            final String assetPath = "notifybar/notifybar_" + iconStyle + ".png";
            Bitmap testBitmap = loadAssetBitmap(assetPath);
            if (testBitmap == null) {
                XposedBridge.log("[NotificationIcon] Failed to load: " + assetPath);
                return;
            }
            XposedBridge.log("[NotificationIcon] Loaded icon: " + assetPath +
                    " (" + testBitmap.getWidth() + "x" + testBitmap.getHeight() + ")");

            // Replace "notifybar" drawable in WhatsApp's resources
            // Cache the bitmap to avoid re-creating AssetManager on every load
            final Bitmap cachedBitmap = testBitmap;
            resparam.res.setReplacement(resparam.packageName, "drawable", "notifybar",
                    new XResources.DrawableLoader() {
                        @Override
                        public Drawable newDrawable(XResources res, int id) throws Throwable {
                            return new BitmapDrawable(res, cachedBitmap);
                        }
                    });

            XposedBridge.log("[NotificationIcon] Replaced 'notifybar' drawable with style '" + iconStyle + "'");
        } catch (Exception e) {
            XposedBridge.log("[NotificationIcon] Error: " + e.getMessage());
        }
    }

    /**
     * Load a bitmap from the module APK's assets directory.
     */
    @SuppressWarnings("deprecation")
    private Bitmap loadAssetBitmap(String assetPath) {
        try {
            AssetManager assetManager = AssetManager.class.newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class)
                    .invoke(assetManager, MODULE_PATH);
            InputStream is = assetManager.open(assetPath);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            return bitmap;
        } catch (Throwable t) {
            XposedBridge.log("[NotificationIcon] Error loading asset: " + t.getMessage());
            return null;
        }
    }

    /**
     * A Drawable wrapper that ignores all color filter, tint, and tintList calls.
     * This ensures custom tick PNGs display their original colors exactly as
     * designed,
     * regardless of WhatsApp's internal tinting or WaEnhancer's Monet/custom color
     * features.
     *
     * WhatsApp has separate resources for delivered and read ticks, so each gets
     * its
     * own TintProofDrawable wrapping the correct PNG. No mutable state needed.
     */
    public static class TintProofDrawable extends Drawable {
        private final Drawable wrapped;
        public final String tickName;

        public TintProofDrawable(Drawable wrapped, String tickName) {
            this.wrapped = wrapped;
            this.tickName = tickName;
            setBounds(wrapped.getBounds());
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
