package com.wmods.wppenhacer.xposed.features.customization;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.NinePatch;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.WppXposed;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.MonetColorEngine;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class BubbleThemes extends Feature {

    public static volatile boolean isBubbleThemeActive = false;

    /** Cache loaded bubble bitmaps (keyed by file name). */
    private final Map<String, Bitmap> bitmapCache = new HashMap<>();
    /** Track which bitmaps are grayscale templates that need tinting. */
    private final Set<String> grayscaleBitmaps = new HashSet<>();
    /** Cache extracted bubble colors per direction. */
    private volatile int cachedOutgoingColor = 0;
    private volatile int cachedIncomingColor = 0;
    private AssetManager moduleAssets;

    public BubbleThemes(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        String bubbleStyle = prefs.getString("bubble_style", "stock");
        if (bubbleStyle == null || bubbleStyle.equals("stock")) {
            isBubbleThemeActive = false;
            return;
        }

        isBubbleThemeActive = true;

        // Build asset file names for the selected style
        String incomingFile = bubbleStyle + "_balloon_incoming_normal.9.png";
        String incomingExtFile = bubbleStyle + "_balloon_incoming_normal_ext.9.png";
        String outgoingFile = bubbleStyle + "_balloon_outgoing_normal.9.png";
        String outgoingExtFile = bubbleStyle + "_balloon_outgoing_normal_ext.9.png";

        // Initialize module AssetManager to load from module APK
        initModuleAssets();

        // Pre-verify that at least the basic assets exist
        Bitmap testIncoming = loadBubbleBitmap(incomingFile);
        Bitmap testOutgoing = loadBubbleBitmap(outgoingFile);
        if (testIncoming == null && testOutgoing == null) {
            log("No bubble assets found for style: " + bubbleStyle);
            isBubbleThemeActive = false;
            return;
        }

        // Hook 1: loadBallonDateDrawable - args[0] is position (3=outgoing)
        // This provides the bubble drawable used for the date/timestamp area.
        var dateWrapper = Unobfuscator.loadBallonDateDrawable(classLoader);
        XposedBridge.hookMethod(dateWrapper, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var position = (int) param.args[0];
                boolean isOutgoing = position == 3;
                String file = isOutgoing ? outgoingFile : incomingFile;
                Drawable original = (Drawable) param.getResult();
                Drawable replacement = rebuildDrawableWithBubbleBitmap(original, file, isOutgoing);
                if (replacement != null) {
                    param.setResult(replacement);
                }
            }
        });

        // Hook 2: loadBallonBorderDrawable - args[1] is position (3=outgoing)
        // The border/ext drawable is an overlay rendered ON TOP of the bubble content.
        // For media messages (images, videos, stickers), this overlay would cover the
        // media area — causing the white rectangle issue seen with attachments.
        //
        // Fouad handles this in native code (BubbleStyleExt) with careful transparency
        // management. Until we can replicate that properly, we skip replacing the border
        // drawable entirely. The main bubble shape (hook 3) already provides the full
        // bubble appearance. We only suppress the original border so it doesn't clash
        // with our custom bubble shape.
        var borderMethod = Unobfuscator.loadBallonBorderDrawable(classLoader);
        XposedBridge.hookMethod(borderMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // Set the original border to fully transparent so it doesn't
                // visually conflict with our custom bubble from hook 3.
                Drawable original = (Drawable) param.getResult();
                if (original != null) {
                    original.setAlpha(0);
                }
            }
        });

        // Hook 3: loadBubbleDrawableMethod - args[0] is position (3=outgoing)
        // This is the primary bubble shape drawable (the main background).
        var bubbleDrawableMethod = Unobfuscator.loadBubbleDrawableMethod(classLoader);
        XposedBridge.hookMethod(bubbleDrawableMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var position = (int) param.args[0];
                boolean isOutgoing = position == 3;
                String file = isOutgoing ? outgoingFile : incomingFile;
                Drawable original = (Drawable) param.getResult();
                Drawable replacement = rebuildDrawableWithBubbleBitmap(original, file, isOutgoing);
                if (replacement != null) {
                    param.setResult(replacement);
                }
            }
        });
    }

    /**
     * Initialize the AssetManager for the module APK.
     */
    private void initModuleAssets() {
        try {
            String modulePath = WppXposed.getModulePath();
            if (modulePath == null) {
                log("Module path is null, cannot load bubble assets");
                return;
            }
            moduleAssets = AssetManager.class.newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class)
                    .invoke(moduleAssets, modulePath);
        } catch (Exception e) {
            log("Failed to init module AssetManager: " + e.getMessage());
            moduleAssets = null;
        }
    }

    /**
     * Load a bubble bitmap from the module's assets/bubbles/ folder.
     * Returns null if not found. Cached for reuse.
     * Also detects grayscale (LA mode) bitmaps and tracks them for tinting.
     */
    private Bitmap loadBubbleBitmap(String fileName) {
        Bitmap cached = bitmapCache.get(fileName);
        if (cached != null) return cached;

        if (moduleAssets == null) return null;

        try (InputStream is = moduleAssets.open("bubbles/" + fileName)) {
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap == null) {
                logDebug("Failed to decode bitmap: " + fileName);
                return null;
            }
            bitmapCache.put(fileName, bitmap);

            // Detect grayscale templates: if the bitmap has no color information
            // (all visible pixels are gray/white), it's a template that needs tinting.
            // Fouad's native code tints these; we need to do it in Java.
            if (isGrayscaleBitmap(bitmap)) {
                grayscaleBitmaps.add(fileName);
            }

            return bitmap;
        } catch (Exception e) {
            logDebug("Failed to load bubble asset: " + fileName + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if a bitmap is a grayscale template (no color, just luminance + alpha).
     * These are the LA-mode PNGs from Fouad that need to be tinted with the
     * appropriate bubble color.
     *
     * We sample a few pixels to determine this efficiently.
     */
    private boolean isGrayscaleBitmap(Bitmap bitmap) {
        if (bitmap == null) return false;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Sample pixels across the image
        int sampleCount = 0;
        int grayCount = 0;
        int step = Math.max(1, Math.min(width, height) / 8);

        for (int y = step; y < height - step; y += step) {
            for (int x = step; x < width - step; x += step) {
                int pixel = bitmap.getPixel(x, y);
                int a = Color.alpha(pixel);
                if (a < 10) continue; // skip transparent pixels

                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                sampleCount++;
                // A pixel is "gray" if R, G, B are all within 5 of each other
                if (Math.abs(r - g) <= 5 && Math.abs(g - b) <= 5 && Math.abs(r - b) <= 5) {
                    grayCount++;
                }
            }
        }

        // If >90% of sampled visible pixels are gray, it's a grayscale template
        return sampleCount > 0 && (grayCount * 100 / sampleCount) > 90;
    }

    /**
     * Minimum padding value (in pixels) to use when the original drawable
     * or the bubble's own chunk has zero padding in any direction.
     * This prevents text/media from being drawn with no inset.
     */
    private static final int MIN_PADDING_PX = 4;

    /**
     * Replace the original WhatsApp bubble drawable with our custom bubble.
     *
     * The approach: use the bubble's OWN nine-patch chunk (so the xDivs/yDivs
     * stretch regions match the bitmap) but PATCH the padding bytes in the chunk
     * to use the original WhatsApp drawable's padding. This ensures:
     * - The bubble shape/stretching is correct (our chunk matches our bitmap)
     * - Text/media content is inset correctly (original padding preserved)
     *
     * @param original The original WhatsApp bubble drawable
     * @param fileName The bubble asset file name
     * @param isOutgoing true for outgoing (right) bubbles, false for incoming (left)
     * @return A new NinePatchDrawable with our bubble's appearance and correct padding
     */
    private Drawable rebuildDrawableWithBubbleBitmap(Drawable original, String fileName, boolean isOutgoing) {
        if (original == null) return null;

        Bitmap bubbleBitmap = loadBubbleBitmap(fileName);
        if (bubbleBitmap == null) return null;

        try {
            // Get the bubble's own nine-patch chunk — this has correct xDivs/yDivs
            // for our bitmap's dimensions.
            byte[] bubbleChunk = bubbleBitmap.getNinePatchChunk();
            if (bubbleChunk == null || !NinePatch.isNinePatchChunk(bubbleChunk)) {
                logDebug("Bubble bitmap has no valid nine-patch chunk: " + fileName
                        + " (chunk=" + (bubbleChunk == null ? "null" : "len=" + bubbleChunk.length) + ")");
                return null;
            }

            // Get the original WhatsApp drawable's padding.
            // This tells us how much inset WhatsApp expects for text/media.
            Rect originalPadding = new Rect();
            if (original instanceof NinePatchDrawable) {
                original.getPadding(originalPadding);
            }

            // Clone the chunk so we don't mutate the cached bitmap's chunk
            byte[] patchedChunk = bubbleChunk.clone();

            // Patch the padding in the chunk.
            patchPaddingInChunk(patchedChunk, originalPadding);

            // Build the Rect that matches what we wrote into the chunk
            Rect finalPadding = readPaddingFromChunk(patchedChunk);

            NinePatchDrawable npd = new NinePatchDrawable(
                    Utils.getApplication().getResources(),
                    bubbleBitmap, patchedChunk, finalPadding, null);

            // If this is a grayscale template, tint it with the original bubble's color.
            // Fouad's native BubbleStyle() does this internally; we replicate it here.
            if (grayscaleBitmaps.contains(fileName)) {
                int tintColor = getTintColor(original, isOutgoing);
                if (tintColor != 0) {
                    // Use SRC_IN: the result color comes from the filter, the alpha
                    // comes from the drawable. This matches BubbleColors' approach
                    // and works cleanly on white/gray templates — the template's
                    // luminance variations still show through via alpha channel,
                    // while the color is entirely replaced by our tint.
                    npd.setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));
                }
            }

            return npd;
        } catch (Exception e) {
            logDebug("Failed to rebuild bubble drawable: " + fileName + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the tint color for a grayscale bubble template.
     *
     * Priority:
     * 1. User-configured manual bubble colors (from BubbleColors preferences)
     * 2. Monet dynamic colors (if monet_theme is enabled)
     * 3. Color extracted from the original WhatsApp drawable (per-direction)
     * 4. WhatsApp's known default bubble colors based on dark/light mode
     *
     * The original drawable extraction is key: the `original` parameter IS the
     * actual WhatsApp bubble for that specific direction, so rendering it and
     * sampling its dominant color gives us the exact color WhatsApp intended.
     * This is version-independent and direction-specific.
     */
    private int getTintColor(Drawable original, boolean isOutgoing) {
        // Priority 1: User-configured manual bubble colors
        if (prefs.getBoolean("bubble_color", false)) {
            int manual = isOutgoing ? prefs.getInt("bubble_right", 0) : prefs.getInt("bubble_left", 0);
            if (manual != 0) return manual;
        }

        // Priority 2: Monet dynamic colors
        if (prefs.getBoolean("monet_theme", false)) {
            try {
                var app = Utils.getApplication();
                if (app != null) {
                    if (!isOutgoing) {
                        // BubbleColors returns BLACK for incoming when Monet is enabled
                        // but that doesn't look right on bubble templates.
                        // Use MonetColorEngine's incoming color instead.
                        int monetIncoming = MonetColorEngine.getBubbleIncomingColor(app);
                        if (monetIncoming != -1) return monetIncoming;
                    } else {
                        int monetOutgoing = MonetColorEngine.getBubbleOutgoingColor(app);
                        if (monetOutgoing != -1) return monetOutgoing;
                    }
                }
            } catch (Exception ignored) {}
        }

        // Priority 3: Use cached extracted color if available
        int cached = isOutgoing ? cachedOutgoingColor : cachedIncomingColor;
        if (cached != 0) return cached;

        // Priority 3b: Extract color from the original WhatsApp drawable
        if (original != null) {
            int extracted = extractDominantColor(original);
            if (extracted != 0) {
                if (isOutgoing) {
                    cachedOutgoingColor = extracted;
                } else {
                    cachedIncomingColor = extracted;
                }
                log("Extracted bubble color for " + (isOutgoing ? "outgoing" : "incoming")
                        + ": #" + Integer.toHexString(extracted));
                return extracted;
            }
        }

        // Priority 4: Hardcoded WhatsApp default colors
        boolean isDark = DesignUtils.isNightMode();
        int fallback;
        if (isOutgoing) {
            fallback = isDark ? 0xFF005C4B : 0xFFD9FDD3;
        } else {
            fallback = isDark ? 0xFF202C33 : 0xFFFFFFFF;
        }
        if (isOutgoing) {
            cachedOutgoingColor = fallback;
        } else {
            cachedIncomingColor = fallback;
        }
        log("Using fallback bubble color for " + (isOutgoing ? "outgoing" : "incoming")
                + ": #" + Integer.toHexString(fallback) + " nightMode=" + isDark);
        return fallback;
    }

    /**
     * Extract the dominant (most common) non-transparent color from a Drawable
     * by rendering it to a small bitmap and counting pixel colors.
     *
     * We render at a small fixed size (24x24) to keep this fast — we only need
     * the overall color, not fine details. The drawable is the original WhatsApp
     * bubble which is a solid-colored nine-patch, so even a small render captures
     * the correct fill color.
     *
     * @return the dominant color with full alpha, or 0 if extraction fails
     */
    private int extractDominantColor(Drawable drawable) {
        try {
            // Render the drawable to a small bitmap
            int size = 24;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);

            // Count color occurrences, ignoring transparent/near-transparent pixels
            Map<Integer, Integer> colorCounts = new HashMap<>();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    int alpha = Color.alpha(pixel);
                    if (alpha < 50) continue; // skip transparent pixels

                    // Quantize to reduce noise: round RGB to nearest 8
                    int r = (Color.red(pixel) >> 3) << 3;
                    int g = (Color.green(pixel) >> 3) << 3;
                    int b = (Color.blue(pixel) >> 3) << 3;
                    int quantized = Color.rgb(r, g, b);

                    colorCounts.merge(quantized, 1, Integer::sum);
                }
            }

            bitmap.recycle();

            if (colorCounts.isEmpty()) return 0;

            // Find the most common color
            int bestColor = 0;
            int bestCount = 0;
            for (Map.Entry<Integer, Integer> entry : colorCounts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestCount = entry.getValue();
                    bestColor = entry.getKey();
                }
            }

            // Return with full alpha
            return 0xFF000000 | bestColor;
        } catch (Exception e) {
            logDebug("Failed to extract color from drawable: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Patch the padding fields inside a nine-patch chunk byte array.
     *
     * For each direction (left, right, top, bottom):
     *   - If the original WhatsApp padding is > 0, use it (best fit for layout)
     *   - Else if the chunk already has a non-zero value, keep it
     *   - Else write MIN_PADDING_PX as a safety minimum
     *
     * IMPORTANT: After BitmapFactory decodes a nine-patch PNG, the chunk stored
     * in the Bitmap is in NATIVE byte order (little-endian on ARM/x86), NOT the
     * big-endian format used in the PNG file. The wasDeserialized flag (byte 0)
     * indicates this: 0 = file format (big-endian), 1 = deserialized (native/LE).
     *
     * Padding is at offsets 12, 16, 20, 24 (int32 each).
     */
    private void patchPaddingInChunk(byte[] chunk, Rect originalPadding) {
        if (chunk.length < 32) return; // chunk too small, shouldn't happen

        boolean deserialized = chunk[0] != 0;

        int chunkLeft = readInt32(chunk, 12, deserialized);
        int chunkRight = readInt32(chunk, 16, deserialized);
        int chunkTop = readInt32(chunk, 20, deserialized);
        int chunkBottom = readInt32(chunk, 24, deserialized);

        int finalLeft = originalPadding.left > 0 ? originalPadding.left : (chunkLeft > 0 ? chunkLeft : MIN_PADDING_PX);
        int finalRight = originalPadding.right > 0 ? originalPadding.right : (chunkRight > 0 ? chunkRight : MIN_PADDING_PX);
        int finalTop = originalPadding.top > 0 ? originalPadding.top : (chunkTop > 0 ? chunkTop : MIN_PADDING_PX);
        int finalBottom = originalPadding.bottom > 0 ? originalPadding.bottom : (chunkBottom > 0 ? chunkBottom : MIN_PADDING_PX);

        writeInt32(chunk, 12, finalLeft, deserialized);
        writeInt32(chunk, 16, finalRight, deserialized);
        writeInt32(chunk, 20, finalTop, deserialized);
        writeInt32(chunk, 24, finalBottom, deserialized);
    }

    /**
     * Read the padding from a nine-patch chunk as a Rect.
     */
    private Rect readPaddingFromChunk(byte[] chunk) {
        if (chunk.length < 32) return new Rect(MIN_PADDING_PX, MIN_PADDING_PX, MIN_PADDING_PX, MIN_PADDING_PX);
        boolean deserialized = chunk[0] != 0;
        int left = readInt32(chunk, 12, deserialized);
        int right = readInt32(chunk, 16, deserialized);
        int top = readInt32(chunk, 20, deserialized);
        int bottom = readInt32(chunk, 24, deserialized);
        return new Rect(left, top, right, bottom);
    }

    /**
     * Read a 32-bit integer from a byte array at the given offset.
     * @param nativeOrder if true, read as little-endian (deserialized/native);
     *                    if false, read as big-endian (file format).
     */
    private static int readInt32(byte[] data, int offset, boolean nativeOrder) {
        if (nativeOrder) {
            // Little-endian (native order on ARM/x86)
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        } else {
            // Big-endian (file/network order)
            return ((data[offset] & 0xFF) << 24)
                    | ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);
        }
    }

    /**
     * Write a 32-bit integer into a byte array at the given offset.
     * @param nativeOrder if true, write as little-endian (deserialized/native);
     *                    if false, write as big-endian (file format).
     */
    private static void writeInt32(byte[] data, int offset, int value, boolean nativeOrder) {
        if (nativeOrder) {
            // Little-endian
            data[offset] = (byte) (value & 0xFF);
            data[offset + 1] = (byte) ((value >> 8) & 0xFF);
            data[offset + 2] = (byte) ((value >> 16) & 0xFF);
            data[offset + 3] = (byte) ((value >> 24) & 0xFF);
        } else {
            // Big-endian
            data[offset] = (byte) ((value >> 24) & 0xFF);
            data[offset + 1] = (byte) ((value >> 16) & 0xFF);
            data[offset + 2] = (byte) ((value >> 8) & 0xFF);
            data[offset + 3] = (byte) (value & 0xFF);
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Bubble Themes";
    }
}
