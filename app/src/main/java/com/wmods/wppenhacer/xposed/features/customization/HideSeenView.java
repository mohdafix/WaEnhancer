package com.wmods.wppenhacer.xposed.features.customization;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.WppXposed;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class HideSeenView extends Feature {

    // Tick style indicator configuration
    private static final int TICK_INDICATOR_ID = 0xf7ff2002;
    private static String tickStyle = null;
    private static int tickDeliveredResId = 0;  // {style}_message_got_receipt_from_target
    private static int tickReadResId = 0;       // {style}_message_got_read_receipt_from_target

    // Cache configuration
    private static final int JID_CACHE_SIZE = 30;
    private static final long REFRESH_DEBOUNCE_MS = 80;
    private static final Object CACHE_LOCK = new Object();

    // LRU cache for JID-based message status
    private static final LruCache<String, JidSeenCache> jidCache = new LruCache<String, JidSeenCache>(JID_CACHE_SIZE) {
        @Override
        protected void entryRemoved(boolean evicted, String key, JidSeenCache oldValue, JidSeenCache newValue) {
            loadedMessageType.remove(key);
            loadedViewOnceType.remove(key);
        }
    };

    // Threading and refresh control
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean refreshScheduled = new AtomicBoolean(false);
    private static final ExecutorService cacheExecutor = Executors.newFixedThreadPool(2);

    // Loading state trackers
    private static final ConcurrentHashMap<String, Boolean> loadingMessageType = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> loadingViewOnceType = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> loadedMessageType = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> loadedViewOnceType = new ConcurrentHashMap<>();

    // Cache data structure
    private static class JidSeenCache {
        Map<String, Boolean> messageStatus = new HashMap<>();
        Map<String, Boolean> viewOnceStatus = new HashMap<>();
    }

    public HideSeenView(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("hide_seen_view", false)) return;

        // Resolve tick style and cache drawable resource IDs for seen/not-seen indicator
        initTickStyle();

        // Hook into MessageHistory.updateViewedMessage to invalidate cache when DB changes
        try {
            var updateMethod = MessageHistory.class.getDeclaredMethod(
                "updateViewedMessage", 
                String.class, 
                String.class, 
                MessageHistory.MessageType.class, 
                boolean.class
            );
            
            XposedBridge.hookMethod(updateMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String jid = (String) param.args[0];
                    String messageId = (String) param.args[1];
                    MessageHistory.MessageType type = (MessageHistory.MessageType) param.args[2];
                    boolean viewed = (boolean) param.args[3];
                    
                    // Update cache and refresh UI
                    handleHideSeenChanged(jid, messageId, type, viewed);
                }
            });
        } catch (Exception e) {
            logDebug("Failed to hook updateViewedMessage: " + e.getMessage());
        }

        // Register conversation listener
        ConversationItemListener.conversationListeners.add(new ConversationItemListener.OnConversationItemListener() {
            @Override
            public void onItemBind(FMessageWpp fMessage, ViewGroup viewGroup) {
                if (fMessage == null || !fMessage.isValid()) return;
                
                FMessageWpp.Key key = fMessage.getKey();
                if (key == null || key.isFromMe) return;

                updateBubbleView(fMessage, viewGroup);
            }
        });
    }

    // ================= TICK STYLE INIT =================

    /**
     * Read the tick_style preference and resolve the two ResId.drawable fields
     * needed for the seen/not-seen indicator:
     *   - Not seen: {style}_message_got_receipt_from_target  (delivered = gray double tick)
     *   - Seen:     {style}_message_got_read_receipt_from_target  (read = blue double tick)
     *
     * These ResId.drawable fields hold WhatsApp-context resource IDs populated in
     * handleInitPackageResources via resparam.res.addResource().
     */
    private void initTickStyle() {
        try {
            String style = prefs.getString("tick_style", "default");
            if (style == null || style.equals("default")) {
                tickStyle = null;
                tickDeliveredResId = 0;
                tickReadResId = 0;
                return;
            }

            // Resolve delivered tick: {style}_message_got_receipt_from_target
            String deliveredField = style + "_message_got_receipt_from_target";
            String readField = style + "_message_got_read_receipt_from_target";

            int deliveredId = 0;
            int readId = 0;

            try {
                deliveredId = ResId.drawable.class.getField(deliveredField).getInt(null);
            } catch (NoSuchFieldException e) {
                logDebug("No ResId field for: " + deliveredField);
            }

            try {
                readId = ResId.drawable.class.getField(readField).getInt(null);
            } catch (NoSuchFieldException e) {
                logDebug("No ResId field for: " + readField);
            }

            if (deliveredId != 0 && readId != 0) {
                tickStyle = style;
                tickDeliveredResId = deliveredId;
                tickReadResId = readId;
                logDebug("Tick indicator using style '" + style
                        + "' delivered=" + deliveredId + " read=" + readId);
            } else {
                // Fall back to emoji if we can't resolve both drawables
                tickStyle = null;
                tickDeliveredResId = 0;
                tickReadResId = 0;
                logDebug("Tick style '" + style + "' missing drawable fields, falling back to emoji");
            }
        } catch (Exception e) {
            logDebug("Failed to init tick style: " + e.getMessage());
            tickStyle = null;
        }
    }

    // ================= CACHE MANAGEMENT =================

    private static void ensureCacheLoaded(String jid, MessageHistory.MessageType type) {
        ConcurrentHashMap<String, Boolean> loadingMap = 
            (type == MessageHistory.MessageType.MESSAGE_TYPE) ? loadingMessageType : loadingViewOnceType;
        ConcurrentHashMap<String, Boolean> loadedMap = 
            (type == MessageHistory.MessageType.MESSAGE_TYPE) ? loadedMessageType : loadedViewOnceType;

        // Only load if not already loaded or loading
        if (!loadedMap.containsKey(jid) && loadingMap.putIfAbsent(jid, Boolean.TRUE) == null) {
            cacheExecutor.execute(() -> {
                try {
                    Map<String, Boolean> statusMap = loadStatusMap(jid, type);

                    synchronized (CACHE_LOCK) {
                        JidSeenCache cache = jidCache.get(jid);
                        if (cache == null) {
                            cache = new JidSeenCache();
                            jidCache.put(jid, cache);
                        }

                        if (type == MessageHistory.MessageType.MESSAGE_TYPE) {
                            cache.messageStatus = statusMap;
                        } else {
                            cache.viewOnceStatus = statusMap;
                        }
                    }

                    loadedMap.put(jid, Boolean.TRUE);
                    requestRefresh();
                } finally {
                    loadingMap.remove(jid);
                }
            });
        }
    }

    private static Map<String, Boolean> loadStatusMap(String jid, MessageHistory.MessageType type) {
        Map<String, Boolean> map = new HashMap<>();

        // Load hidden messages
        List<MessageHistory.MessageSeenItem> hiddenMessages = 
            MessageHistory.getInstance().getHideSeenMessages(jid, type, true);
        if (hiddenMessages != null) {
            for (MessageHistory.MessageSeenItem item : hiddenMessages) {
                map.put(item.message, Boolean.TRUE);
            }
        }

        // Load viewed messages
        List<MessageHistory.MessageSeenItem> viewedMessages = 
            MessageHistory.getInstance().getHideSeenMessages(jid, type, false);
        if (viewedMessages != null) {
            for (MessageHistory.MessageSeenItem item : viewedMessages) {
                map.put(item.message, Boolean.FALSE);
            }
        }

        return map;
    }

    private static Boolean getCachedStatus(String jid, String messageId, MessageHistory.MessageType type) {
        synchronized (CACHE_LOCK) {
            JidSeenCache cache = jidCache.get(jid);
            if (cache == null) return null;

            Map<String, Boolean> statusMap = 
                (type == MessageHistory.MessageType.MESSAGE_TYPE) ? cache.messageStatus : cache.viewOnceStatus;
            return statusMap.get(messageId);
        }
    }

    private static void handleHideSeenChanged(String jid, String messageId, MessageHistory.MessageType type, boolean viewed) {
        synchronized (CACHE_LOCK) {
            JidSeenCache cache = jidCache.get(jid);
            if (cache == null) {
                cache = new JidSeenCache();
                jidCache.put(jid, cache);
            }

            if (type == MessageHistory.MessageType.MESSAGE_TYPE) {
                cache.messageStatus.put(messageId, viewed);
            } else {
                cache.viewOnceStatus.put(messageId, viewed);
            }
        }
        requestRefresh();
    }

    // ================= UI UPDATE =================

    private static void requestRefresh() {
        if (refreshScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(() -> {
                refreshScheduled.set(false);
                updateAllBubbleViews();
            }, REFRESH_DEBOUNCE_MS);
        }
    }

    public static void updateAllBubbleViews() {
        var adapter = ConversationItemListener.getAdapter();
        if (adapter instanceof CursorAdapter cursorAdapter) {
            WppCore.getCurrentActivity().runOnUiThread(cursorAdapter::notifyDataSetChanged);
        }
    }

    @SuppressLint("ResourceType")
    private static void updateBubbleView(FMessageWpp fMessage, View viewGroup) {
        var userJid = fMessage.getKey().remoteJid;
        var messageId = fMessage.getKey().messageID;

        if (userJid.isNull()) return;

        String jid = userJid.getPhoneRawString();

        // Update view-once indicator
        ImageView viewOnceIcon = viewGroup.findViewById(Utils.getID("view_once_control_icon", "id"));
        if (viewOnceIcon != null) {
            MessageHistory.MessageType viewOnceType = MessageHistory.MessageType.VIEW_ONCE_TYPE;
            Boolean cachedStatus = getCachedStatus(jid, messageId, viewOnceType);

            if (cachedStatus == null) {
                ensureCacheLoaded(jid, viewOnceType);
                viewOnceIcon.setColorFilter(null);
            } else {
                viewOnceIcon.setColorFilter(cachedStatus ? Color.GREEN : Color.RED);
            }
        }

        // Update message status indicator
        ViewGroup dateWrapper = viewGroup.findViewById(Utils.getID("date_wrapper", "id"));
        if (dateWrapper != null) {
            MessageHistory.MessageType messageType = MessageHistory.MessageType.MESSAGE_TYPE;
            Boolean cachedStatus = getCachedStatus(jid, messageId, messageType);

            if (tickStyle != null && tickDeliveredResId != 0 && tickReadResId != 0) {
                // --- Tick style mode: use ImageView with custom tick drawables ---

                // Hide the emoji TextView if it exists
                TextView emojiStatus = dateWrapper.findViewById(0xf7ff2001);
                if (emojiStatus != null) {
                    emojiStatus.setVisibility(View.GONE);
                }

                // Find or create the tick ImageView
                ImageView tickIndicator = dateWrapper.findViewById(TICK_INDICATOR_ID);
                if (tickIndicator == null) {
                    tickIndicator = new ImageView(viewGroup.getContext());
                    tickIndicator.setId(TICK_INDICATOR_ID);
                    tickIndicator.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    // Size: match the date text height approximately
                    int size = Utils.dipToPixels(17);
                    ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(size, size);
                    lp.setMarginEnd(Utils.dipToPixels(2));
                    tickIndicator.setLayoutParams(lp);
                    dateWrapper.addView(tickIndicator);
                }

                if (cachedStatus == null) {
                    ensureCacheLoaded(jid, messageType);
                    tickIndicator.setVisibility(View.GONE);
                } else {
                    tickIndicator.setVisibility(View.VISIBLE);
                    int resId = cachedStatus ? tickReadResId : tickDeliveredResId;
                    try {
                        Drawable tickDrawable = viewGroup.getContext().getResources().getDrawable(resId);
                        tickIndicator.setImageDrawable(
                                new WppXposed.TintProofDrawable(tickDrawable));
                    } catch (Exception e) {
                        // Fallback: show emoji if drawable loading fails
                        tickIndicator.setVisibility(View.GONE);
                        if (emojiStatus != null) {
                            emojiStatus.setVisibility(View.VISIBLE);
                            emojiStatus.setText(cachedStatus ? "\uD83D\uDFE2" : "\uD83D\uDD34");
                        }
                    }
                }
            } else {
                // --- Emoji mode: original behavior ---

                // Hide the tick ImageView if it exists
                ImageView tickIndicator = dateWrapper.findViewById(TICK_INDICATOR_ID);
                if (tickIndicator != null) {
                    tickIndicator.setVisibility(View.GONE);
                }

                TextView status = dateWrapper.findViewById(0xf7ff2001);
                if (status == null) {
                    status = new TextView(viewGroup.getContext());
                    status.setId(0xf7ff2001);
                    status.setTextSize(8);
                    dateWrapper.addView(status);
                }

                if (cachedStatus == null) {
                    ensureCacheLoaded(jid, messageType);
                    status.setVisibility(View.GONE);
                } else {
                    status.setVisibility(View.VISIBLE);
                    status.setText(cachedStatus ? "\uD83D\uDFE2" : "\uD83D\uDD34");
                }
            }
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Hide Seen View";
    }
}
