package com.wmods.wppenhacer.xposed.features.customization;

import android.annotation.SuppressLint;
import android.database.Cursor;
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

public class OwnMessageStatus extends Feature {

    private static final int STATUS_INDICATOR_TEXT_ID = 0xf7ff2002;
    private static final int STATUS_INDICATOR_IMAGE_ID = 0xf7ff2003;

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
                    // Force refresh by resetting views first
                    resetStatusViews(viewGroup);
                    updateOwnStatusView(fMessage, viewGroup);
                } else {
                    resetStatusViews(viewGroup);
                }
            }
        });
    }

    private ViewGroup findDateWrapper(ViewGroup viewGroup) {
        // 1. Try common ID
        int wrapperId = Utils.getID("date_wrapper", "id");
        if (wrapperId != -1) {
            View wrapper = viewGroup.findViewById(wrapperId);
            if (wrapper instanceof ViewGroup) return (ViewGroup) wrapper;
        }

        // 2. Robust fallback: find date TextView first, then its parent
        int[] dateIds = {
                Utils.getID("date", "id"),
                Utils.getID("date_tv", "id"),
                Utils.getID("timestamp", "id"),
                Utils.getID("time", "id")
        };

        for (int id : dateIds) {
            if (id != -1) {
                View dateView = viewGroup.findViewById(id);
                if (dateView != null && dateView.getParent() instanceof ViewGroup) {
                    return (ViewGroup) dateView.getParent();
                }
            }
        }

        return null;
    }

    private void resetStatusViews(ViewGroup viewGroup) {
        ViewGroup dateWrapper = findDateWrapper(viewGroup);
        if (dateWrapper == null) return;

        View textIndicator = dateWrapper.findViewById(STATUS_INDICATOR_TEXT_ID);
        if (textIndicator != null) textIndicator.setVisibility(View.GONE);

        View imageIndicator = dateWrapper.findViewById(STATUS_INDICATOR_IMAGE_ID);
        if (imageIndicator != null) imageIndicator.setVisibility(View.GONE);

        for (int i = 0; i < dateWrapper.getChildCount(); i++) {
            View child = dateWrapper.getChildAt(i);
            if (child instanceof ImageView && child.getId() != STATUS_INDICATOR_TEXT_ID && child.getId() != STATUS_INDICATOR_IMAGE_ID) {
                child.setVisibility(View.VISIBLE);
            }
        }
    }

    @SuppressLint("ResourceType")
    private void updateOwnStatusView(FMessageWpp fMessage, ViewGroup viewGroup) {
        ViewGroup dateWrapper = findDateWrapper(viewGroup);
        if (dateWrapper == null) return;

        // Hide original ticks
        for (int i = 0; i < dateWrapper.getChildCount(); i++) {
            View child = dateWrapper.getChildAt(i);
            if (child instanceof ImageView && child.getId() != STATUS_INDICATOR_TEXT_ID && child.getId() != STATUS_INDICATOR_IMAGE_ID) {
                child.setVisibility(View.GONE);
            }
        }

        int status = getStatus(fMessage);
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

        // Expanded status set:
        // Blue (Read): 13, 5, 8, 9, 10
        // Green (Delivered/Sent): 1, 4, 3, 2
        // White (Pending): 0, 6, -1
        if (status == 13 || status == 5 || status == 8 || status == 9 || status == 10) {
            indicator.setText("\uD83D\uDD35"); // 🔵
            indicator.setVisibility(View.VISIBLE);
        } else if (status == 1 || status == 4 || status == 3 || status == 2) {
            indicator.setText("\uD83D\uDFE2"); // 🟢
            indicator.setVisibility(View.VISIBLE);
        } else if (status == 0 || status == 6 || status == -1) {
            indicator.setText("\u26AA"); // ⚪
            indicator.setVisibility(View.VISIBLE);
        } else {
            indicator.setVisibility(View.GONE);
        }
    }

    private void renderImageIndicator(ViewGroup dateWrapper, int status) {
        ImageView indicator = dateWrapper.findViewById(STATUS_INDICATOR_IMAGE_ID);
        if (indicator == null) {
            indicator = new ImageView(dateWrapper.getContext());
            indicator.setId(STATUS_INDICATOR_IMAGE_ID);
            int size = Utils.dipToPixels(13);
            
            ViewGroup.LayoutParams params = null;
            if (dateWrapper instanceof android.widget.LinearLayout) {
                var lp = new android.widget.LinearLayout.LayoutParams(size, size);
                lp.gravity = android.view.Gravity.CENTER_VERTICAL;
                lp.leftMargin = Utils.dipToPixels(3);
                params = lp;
            } else if (dateWrapper instanceof android.widget.RelativeLayout) {
                var lp = new android.widget.RelativeLayout.LayoutParams(size, size);
                lp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
                lp.leftMargin = Utils.dipToPixels(3);
                params = lp;
            } else {
                params = new ViewGroup.LayoutParams(size, size);
            }
            
            indicator.setLayoutParams(params);
            indicator.setScaleType(ImageView.ScaleType.FIT_CENTER);
            indicator.setAdjustViewBounds(true);
            indicator.setBackground(null);
            indicator.setPadding(0, 0, 0, 0);
            dateWrapper.addView(indicator);
        }

        int resId = 0;
        if (status == 13 || status == 5 || status == 8 || status == 9 || status == 10) {
            resId = ResId.drawable.ic_status_read_fb;
        } else if (status == 1 || status == 4 || status == 3 || status == 2) {
            resId = ResId.drawable.ic_status_delivered_fb;
        } else if (status == 0 || status == 6 || status == -1) {
            resId = ResId.drawable.ic_status_pending_fb;
        }

        if (resId > 0) {
            indicator.setImageResource(resId);
            indicator.setVisibility(View.VISIBLE);
        } else {
            indicator.setVisibility(View.GONE);
        }
    }

    private int getStatus(FMessageWpp fMessage) {
        // 1. Try real-time memory
        int status = fMessage.getStatus();
        
        // In group chats, if status is 1, it might stay 1 even if partially read.
        // But for own UI ticks, WA usually handles it correctly via background updates.
        
        if (status != -1) return status;

        // 2. Fallback to DB
        return getMessageStatusFromDB(fMessage.getRowId());
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
