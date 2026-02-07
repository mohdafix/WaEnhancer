package com.wmods.wppenhacer.xposed.features.customization;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class OwnMessageStatus extends Feature {

    private static final int STATUS_INDICATOR_TEXT_ID = 0xf7ff2002;
    private static final int STATUS_INDICATOR_IMAGE_ID = 0xf7ff2003;

    // Common IDs for status icons in WA
    private static final String[] STATUS_VIEW_IDS = {"status", "fail_icon", "retry_btn", "re-send", "pending_indicator"};

    public OwnMessageStatus(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("own_status_indicator", false)) return;

        ConversationItemListener.conversationListeners.add(new ConversationItemListener.OnConversationItemListener() {
            @Override
            public void onItemBind(FMessageWpp fMessage, ViewGroup viewGroup) {
                if (fMessage == null || !fMessage.isValid()) return;

                FMessageWpp.Key key = fMessage.getKey();
                if (key != null && key.isFromMe) {
                    // RUN IMMEDIATELY - No post to avoid "briefly visible" icons
                    applyOwnStatus(fMessage, viewGroup);
                } else {
                    resetOurIndicators(viewGroup);
                }
            }
        });
    }

    private void applyOwnStatus(FMessageWpp fMessage, ViewGroup viewGroup) {
        // Hiding must be aggressive and recursive
        hideOriginalStatusViews(viewGroup);
        
        // Add or update our custom indicator
        updateOwnStatusView(fMessage, viewGroup);
    }

    private void hideOriginalStatusViews(ViewGroup root) {
        // Broad range of possible status-related IDs
        String[] ids = {
            "status", "msg_status", "status_indicator", "fail_icon", "retry_btn", 
            "re-send", "pending_indicator", "error_indicator", "status_icon",
            "progress_bar", "cancel_button"
        };
        
        for (String idName : ids) {
            int id = Utils.getID(idName, "id");
            if (id != -1) {
                View v = root.findViewById(id);
                if (v != null) v.setVisibility(View.GONE);
            }
        }

        // Deep recursive hide by content description and common patterns
        hideStatusByPattern(root);
    }

    private void hideStatusByPattern(View view) {
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                hideStatusByPattern(group.getChildAt(i));
            }
        }
        
        // Don't hide our own indicators
        if (view.getId() == STATUS_INDICATOR_TEXT_ID || view.getId() == STATUS_INDICATOR_IMAGE_ID) return;

        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase();
            // Match keywords for read receipts and failure UI
            if (s.contains("read") || s.contains("delivered") || s.contains("sent") || 
                s.contains("played") || s.contains("failure") || s.contains("retry") ||
                s.contains("pending") || s.contains("error")) {
                view.setVisibility(View.GONE);
            }
        }
        
        // Specific check for image views that might be the "arrow" or "ticks"
        if (view instanceof ImageView iv) {
            // Usually, these indicators are small icons
            if (view.getWidth() < Utils.dipToPixels(24) && view.getHeight() < Utils.dipToPixels(24)) {
                // If it's inside date wrapper or near the end of the message
                // This is risky, but contentDescription handles it mostly.
            }
        }
    }

    private ViewGroup findDateWrapper(ViewGroup viewGroup) {
        int wrapperId = Utils.getID("date_wrapper", "id");
        if (wrapperId != -1) {
            View wrapper = viewGroup.findViewById(wrapperId);
            if (wrapper instanceof ViewGroup) return (ViewGroup) wrapper;
        }

        // Fallback: find date TextView
        int dateId = Utils.getID("date", "id");
        if (dateId != -1) {
            View dateView = viewGroup.findViewById(dateId);
            if (dateView != null && dateView.getParent() instanceof ViewGroup) {
                return (ViewGroup) dateView.getParent();
            }
        }
        return null;
    }

    private void resetOurIndicators(ViewGroup viewGroup) {
        ViewGroup dateWrapper = findDateWrapper(viewGroup);
        if (dateWrapper == null) return;

        View textIndicator = dateWrapper.findViewById(STATUS_INDICATOR_TEXT_ID);
        if (textIndicator != null) textIndicator.setVisibility(View.GONE);

        View imageIndicator = dateWrapper.findViewById(STATUS_INDICATOR_IMAGE_ID);
        if (imageIndicator != null) imageIndicator.setVisibility(View.GONE);
    }

    @SuppressLint("ResourceType")
    private void updateOwnStatusView(FMessageWpp fMessage, ViewGroup viewGroup) {
        ViewGroup dateWrapper = findDateWrapper(viewGroup);
        if (dateWrapper == null) return;

        int status = getEnhancedStatus(fMessage);
        int style = Utils.tryParseInt(prefs.getString("own_status_style", "0"), 0);

        if (style == 0) {
            renderTextIndicator(dateWrapper, status);
        } else {
            renderImageIndicator(dateWrapper, status);
        }
    }

    private void renderTextIndicator(ViewGroup dateWrapper, int status) {
        TextView indicator = dateWrapper.findViewById(STATUS_INDICATOR_TEXT_ID);
        if (indicator == null) {
            indicator = new TextView(dateWrapper.getContext());
            indicator.setId(STATUS_INDICATOR_TEXT_ID);
            indicator.setTextSize(10);
            dateWrapper.addView(indicator);
        }

        indicator.setVisibility(View.VISIBLE);
        
        // REFINED MAPPING (Based on common WA internal codes)
        // 13: Read (Group/Broadcast)
        // 5: Read (Individual)
        // 4: Delivered (Individual/Group)
        // 1: Sent
        // 100: Custom "Read by someone in group"
        
        switch (status) {
            case 13:
            case 5:
            case 100:
                indicator.setText("\uD83D\uDD35"); // 🔵 Blue (Read)
                break;
            case 4:
                indicator.setText("\uD83D\uDFE2"); // 🟢 Green (Delivered)
                break;
            case 1:
                indicator.setText("\u26AA"); // ⚪ White (Sent)
                break;
            case 6:
                indicator.setText("\u274C"); // ❌ Red (Error)
                break;
            default:
                if (status > 13) {
                    indicator.setText("\uD83D\uDD35");
                } else if (status > 0) {
                    indicator.setText("\u26AA");
                } else {
                    indicator.setVisibility(View.GONE);
                }
                break;
        }
    }

    private void renderImageIndicator(ViewGroup dateWrapper, int status) {
        ImageView indicator = dateWrapper.findViewById(STATUS_INDICATOR_IMAGE_ID);
        if (indicator == null) {
            indicator = new ImageView(dateWrapper.getContext());
            indicator.setId(STATUS_INDICATOR_IMAGE_ID);
            int size = Utils.dipToPixels(13);
            
            ViewGroup.LayoutParams params;
            if (dateWrapper instanceof android.widget.LinearLayout) {
                var lp = new android.widget.LinearLayout.LayoutParams(size, size);
                lp.gravity = android.view.Gravity.CENTER_VERTICAL;
                lp.leftMargin = Utils.dipToPixels(3);
                params = lp;
            } else {
                params = new ViewGroup.LayoutParams(size, size);
            }
            
            indicator.setLayoutParams(params);
            indicator.setScaleType(ImageView.ScaleType.FIT_CENTER);
            dateWrapper.addView(indicator);
        }

        int resId = 0;
        // Consistent mapping with text indicators
        if (status == 13 || status == 5 || status == 100) {
            resId = ResId.drawable.ic_status_read_fb; // Blue
        } else if (status == 4) {
            resId = ResId.drawable.ic_status_delivered_fb; // Green/Double Grey
        } else if (status >= 1) {
            resId = ResId.drawable.ic_status_pending_fb; // Single Tick
        }

        if (resId > 0) {
            indicator.setImageResource(resId);
            indicator.setVisibility(View.VISIBLE);
        } else {
            indicator.setVisibility(View.GONE);
        }
    }

    private int getEnhancedStatus(FMessageWpp fMessage) {
        int status = fMessage.getStatus();
        
        // DEBUG LOGGING
        XposedBridge.log("OwnMessageStatus: Message " + fMessage.getKey().messageID + " status=" + status + " isGroup=" + fMessage.getKey().remoteJid.isGroup());

        // Handle group chats 
        if (fMessage.getKey().remoteJid.isGroup() && (status == 1 || status == 4)) {
            int readCount = getGroupReadCount(fMessage.getRowId());
            if (readCount > 0) {
                return 100; // Custom status for "Read by someone"
            }
        }
        
        if (status != -1) return status;

        return getMessageStatusFromDB(fMessage.getRowId());
    }

    private int getGroupReadCount(long rowId) {
        if (rowId <= 0) return 0;
        var db = MessageStore.getInstance().getDatabase();
        if (db == null || !db.isOpen()) return 0;
        
        // Improved query check for read receipts
        String sql = "SELECT COUNT(*) FROM receipt_user WHERE message_row_id = ? AND (read_timestamp > 0 OR played_timestamp > 0)";
        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(rowId)})) {
            if (cursor != null && cursor.moveToFirst()) {
                int count = cursor.getInt(0);
                if (count > 0) return count;
            }
        } catch (Exception e) {
            // Fallback: try receipt_device if receipt_user is missing/empty in new versions
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM receipt_device WHERE message_row_id = ? AND read_timestamp > 0", new String[]{String.valueOf(rowId)})) {
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getInt(0);
                }
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private int getMessageStatusFromDB(long rowId) {
        if (rowId <= 0) return -1;
        var db = MessageStore.getInstance().getDatabase();
        if (db == null || !db.isOpen()) return -1;
        try (Cursor cursor = db.rawQuery("SELECT status FROM message WHERE _id = ?", new String[]{String.valueOf(rowId)})) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Own Message Status Indicator";
    }
}

