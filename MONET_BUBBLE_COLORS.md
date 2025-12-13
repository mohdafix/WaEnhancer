# Monet Bubble Colors Implementation

## Overview
Added automatic Monet theming support for chat bubbles while preserving all existing manual color options. The implementation uses minimal code changes and follows a clear priority system.

## Implementation Summary

### Priority System (No Breaking Changes)
The bubble color resolution follows this strict priority order:

1. **Manual colors** (highest priority) - User-selected colors via preferences
2. **Monet colors** - System Material You palette (when enabled)
3. **CSS/Properties colors** - Theme file colors
4. **WhatsApp defaults** (lowest priority) - Original WhatsApp colors (color = 0)

### Files Modified

#### 1. `MonetColorEngine.java` (New Methods)
**Location:** `app/src/main/java/com/wmods/wppenhacer/xposed/utils/MonetColorEngine.java`

Added two new methods for bubble-specific Monet colors:

```java
@ColorInt
public static int getBubbleOutgoingColor(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            // Outgoing bubble: accent1_500 (primary accent tone)
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
            // Incoming bubble: accent3_500 (tertiary accent tone, different from outgoing)
            return context.getColor(android.R.color.system_accent3_500);
        } catch (Exception e) {
            return -1;
        }
    }
    return -1;
}
```

**Monet Color Mapping:**
- **Outgoing bubbles** (right/sent): `system_accent1_500` - Primary accent color
- **Incoming bubbles** (left/received): `system_accent3_500` - Tertiary accent color

This ensures incoming and outgoing bubbles have **different colors** from the Monet palette, avoiding same-color confusion.

#### 2. `BubbleColors.java` (Minimal Refactor)
**Location:** `app/src/main/java/com/wmods/wppenhacer/xposed/features/customization/BubbleColors.java`

**Changes:**
1. Added import for `MonetColorEngine`
2. Added `monetTheme` preference check
3. Created single `resolveBubbleColor()` method to eliminate duplicated logic
4. Updated hook activation condition to include Monet

**New Resolver Method:**
```java
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

    // Priority 2: Monet colors (if enabled and available)
    if (monetEnabled) {
        try {
            int monetColor = isOutgoing
                ? MonetColorEngine.getBubbleOutgoingColor(Utils.getApplication())
                : MonetColorEngine.getBubbleIncomingColor(Utils.getApplication());
            if (monetColor != -1) return monetColor;
        } catch (Exception ignored) {}
    }

    // Priority 3: CSS/Properties color
    if (propertyColor != 0) return propertyColor;

    // Priority 4: Default (0 = use WhatsApp default)
    return 0;
}
```

**Updated Hook Activation:**
```java
boolean bubbleColor = prefs.getBoolean("bubble_color", false);
boolean monetTheme = prefs.getBoolean("monet_theme", false);

// Enable hook if any bubble customization is active
if (!bubbleColor && !Objects.equals(properties.getProperty("bubble_colors"), "true") && !monetTheme)
    return;
```

## Key Features

### ✅ Requirements Met

1. **Manual colors take precedence** ✓
   - If user sets manual bubble colors, they are always used
   - Monet is completely bypassed when manual colors are set

2. **Automatic Monet colors** ✓
   - When `monet_theme` is enabled and no manual colors set
   - Automatically derives from system Material You palette
   - Falls back gracefully if Monet unavailable (Android < 12)

3. **WhatsApp defaults preserved** ✓
   - When neither manual nor Monet is active, returns 0
   - Color 0 means "don't apply filter" = WhatsApp default

4. **Different tones for incoming/outgoing** ✓
   - Outgoing: `accent1_500` (primary)
   - Incoming: `accent3_500` (tertiary)
   - Prevents same-color bubbles

5. **No text/icon color changes** ✓
   - Only applies `PorterDuffColorFilter` to bubble drawables
   - Text and icons remain untouched

6. **AMOLED mode compatible** ✓
   - Monet colors are for bubbles only
   - Background handling is separate (in CustomThemeV2)
   - Bubbles remain colored even in AMOLED mode

### ✅ Implementation Quality

1. **Single resolver method** ✓
   - No duplicated logic across 3 hooks
   - Clear priority system in one place
   - Easy to maintain and debug

2. **Backward compatible** ✓
   - No preference key changes
   - No preference reordering
   - Existing users see no changes unless they enable Monet

3. **Minimal code changes** ✓
   - Only 2 new methods in MonetColorEngine
   - Only 1 new resolver method in BubbleColors
   - Existing hook structure unchanged

4. **No double recoloring** ✓
   - Each bubble colored exactly once
   - Priority system prevents conflicts
   - Early returns avoid redundant work

## Usage

### For Users

**Enable Monet Bubble Colors:**
1. Go to WaEnhancer settings
2. Enable "Monet Theme" option
3. Ensure "Bubble Color" manual option is **disabled** (or set to 0)
4. Bubbles will automatically follow your system Material You colors

**Manual Override:**
1. Enable "Bubble Color" option
2. Set custom colors for left/right bubbles
3. Manual colors will override Monet (even if Monet is enabled)

**Disable All Customization:**
1. Disable both "Bubble Color" and "Monet Theme"
2. Bubbles will use WhatsApp defaults

### For Developers

**Testing Scenarios:**

1. **Monet only:**
   - `monet_theme = true`
   - `bubble_color = false`
   - Expected: Monet colors applied

2. **Manual only:**
   - `bubble_color = true`
   - `bubble_left = #FF5733`
   - `bubble_right = #33FF57`
   - Expected: Manual colors applied (Monet ignored)

3. **Both enabled (manual wins):**
   - `monet_theme = true`
   - `bubble_color = true`
   - Expected: Manual colors applied

4. **Neither enabled:**
   - `monet_theme = false`
   - `bubble_color = false`
   - Expected: WhatsApp defaults

5. **CSS properties:**
   - `bubble_colors = true` in theme file
   - `bubble_left = color_system_accent3_500`
   - Expected: CSS colors applied

## Technical Details

### Android Version Support
- **Android 12+ (API 31+):** Full Monet support
- **Android 11 and below:** Graceful fallback to next priority (CSS/Properties or defaults)

### Color Resolution Flow
```
User opens chat
    ↓
Hook triggered (3 hooks: dateWrapper, babblon, bubbleDrawableMethod)
    ↓
For each bubble drawable:
    ↓
resolveBubbleColor(isOutgoing, manualColor, propertyColor, monetEnabled)
    ↓
    ├─ Manual color set? → Use manual color
    ├─ Monet enabled? → Try Monet color
    ├─ CSS color set? → Use CSS color
    └─ None? → Return 0 (WhatsApp default)
    ↓
Apply color filter (if color != 0)
```

### Performance Impact
- **Minimal:** Color resolution happens once per bubble render
- **Cached:** Monet colors are system resources (already cached by Android)
- **No overhead:** Early returns prevent unnecessary work

## Compatibility

### Works With
- ✅ AMOLED mode (bubbles stay colored, backgrounds go black)
- ✅ Custom themes (CSS properties)
- ✅ Manual bubble colors
- ✅ Dark/Light mode
- ✅ All Android versions (graceful degradation)

### Does Not Affect
- ❌ Text colors inside bubbles
- ❌ Icon colors inside bubbles
- ❌ Background colors (handled separately)
- ❌ Status bar colors
- ❌ Navigation bar colors

## Testing Checklist

- [ ] Enable Monet theme → Bubbles change to system colors
- [ ] Disable Monet theme → Bubbles revert to defaults
- [ ] Set manual colors → Manual colors override Monet
- [ ] Remove manual colors with Monet enabled → Monet colors return
- [ ] Test on Android 12+ → Monet colors work
- [ ] Test on Android 11- → Graceful fallback (no crash)
- [ ] Test with AMOLED mode → Bubbles stay colored
- [ ] Test incoming vs outgoing → Different colors
- [ ] Test with CSS theme → CSS colors work
- [ ] Verify no double coloring → Each bubble colored once

## Files Changed
1. `app/src/main/java/com/wmods/wppenhacer/xposed/utils/MonetColorEngine.java`
   - Added `getBubbleOutgoingColor()`
   - Added `getBubbleIncomingColor()`

2. `app/src/main/java/com/wmods/wppenhacer/xposed/features/customization/BubbleColors.java`
   - Added `resolveBubbleColor()` method
   - Updated `doHook()` to support Monet
   - Added Monet preference check

## Migration Notes
- **No database changes required**
- **No preference migration needed**
- **No user action required**
- Existing users will see no changes until they enable Monet theme

---
**Date:** 2025
**Feature:** Monet Bubble Colors
**Status:** ✅ Complete - Ready for testing
