package com.wmods.wppenhacer.xposed.features.general;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ShowEditMessage extends Feature {

    public ShowEditMessage(
            @NonNull ClassLoader loader,
            @NonNull XSharedPreferences preferences
    ) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {

        if (!prefs.getBoolean("antieditmessages", false)) {
            return;
        }

        logDebug("ShowEditMessage: Feature enabled!");

        /* ============================================================
         * PART 1 — Capture edited message history (DATA)
         * ============================================================ */

        var onMessageEdit = Unobfuscator.loadMessageEditMethod(classLoader);
        var callerMessageEditMethod = Unobfuscator.loadCallerMessageEditMethod(classLoader);
        var getEditMessage = Unobfuscator.loadGetEditMessageMethod(classLoader);

        if (onMessageEdit != null) {
            XposedBridge.hookMethod(onMessageEdit, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object rawMsg = param.args[0];
                        if (rawMsg == null) return;

                        Object editMsg = getEditMessage.invoke(null, rawMsg);
                        if (editMsg == null) return;

                        Object invoked = callerMessageEditMethod.invoke(null, rawMsg);
                        long timestamp = XposedHelpers.getLongField(invoked, "A00");

                        FMessageWpp fMessage = new FMessageWpp(rawMsg);
                        long rowId = fMessage.getRowId();
                        if (rowId <= 0) return;

                        String newText = fMessage.getMessageStr();
                        if (newText == null) {
                            for (Method m : ReflectionUtils.findAllMethodsUsingFilter(
                                    rawMsg.getClass(),
                                    mm -> mm.getReturnType() == String.class
                            )) {
                                newText = (String) m.invoke(rawMsg);
                                if (newText != null) break;
                            }
                        }

                        if (newText == null) return;

                        var history = MessageHistory.getInstance().getMessages(rowId);
                        if (history == null) {
                            String original = MessageStore
                                    .getInstance()
                                    .getCurrentMessageByID(rowId);

                            if (original != null && !original.isEmpty()) {
                                MessageHistory.getInstance()
                                        .insertMessage(rowId, original, 0);
                            }
                        }

                        MessageHistory.getInstance()
                                .insertMessage(rowId, newText, timestamp);

                    } catch (Throwable t) {
                        logDebug("Error in onMessageEdit: " + t.getMessage());
                    }
                }
            });
        }

        /* ============================================================
         * PART 2 — UI: Conversation Row Bind
         * ============================================================ */

        Method bindMethod = Unobfuscator.loadConversationRowBindMethod(classLoader);
        if (bindMethod != null) {
            XposedBridge.hookMethod(bindMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 3) return;
                        if (!(param.args[1] instanceof TextView)) return;

                        TextView metaView = (TextView) param.args[1];
                        Object msgObj = param.args[2];
                        if (msgObj == null) return;

                        long rowId = new FMessageWpp(msgObj).getRowId();
                        if (rowId <= 0) return;

                        // Get merged history
                        ArrayList<MessageHistory.MessageItem> history = getMergedHistory(rowId);

                        // ✅ Only edited messages (need at least 2 versions)
                        if (history == null || history.size() <= 1) {
                            return;
                        }

                        CharSequence cs = metaView.getText();
                        String currentText = cs != null ? cs.toString() : "";

                        if (currentText.contains("📝")) {
                            return;
                        }

                        metaView.setClickable(true);
                        metaView.setFocusable(true);
                        metaView.setVisibility(View.VISIBLE);
                        metaView.getPaint().setUnderlineText(true);
                        metaView.append(" 📝");

                        // Store history in view tag
                        metaView.setTag(history);

                        metaView.setOnClickListener(v -> {
                            try {
                                ArrayList<MessageHistory.MessageItem> clickedHistory =
                                        (ArrayList<MessageHistory.MessageItem>) v.getTag();
                                if (clickedHistory == null) return;

                                showBottomDialog(clickedHistory);
                            } catch (Throwable t) {
                                logDebug("Click error: " + t.getMessage());
                            }
                        });

                    } catch (Throwable t) {
                        logDebug("Bind method error: " + t.getMessage());
                    }
                }
            });
        }
    }

    private ArrayList<MessageHistory.MessageItem> getMergedHistory(long rowId) {
        // Use a LinkedHashMap to de-duplicate by message text while preserving order
        Map<String, MessageHistory.MessageItem> merged = new LinkedHashMap<>();

        // 1. Get from WhatsApp's own message_add_on table (Official history)
        List<MessageHistory.MessageItem> waHistory = MessageStore.getInstance().getWAEditHistory(rowId);
        for (var item : waHistory) {
            if (item.message != null && !item.message.isEmpty()) {
                merged.put(item.message, item);
            }
        }

        // 2. Get from our local database (Captured history)
        ArrayList<MessageHistory.MessageItem> localHistory = MessageHistory.getInstance().getMessages(rowId);
        if (localHistory != null) {
            for (var item : localHistory) {
                if (item.message != null && !item.message.isEmpty()) {
                    // Only add if not already present or if it's the first one (original)
                    if (!merged.containsKey(item.message)) {
                        merged.put(item.message, item);
                    }
                }
            }
        }

        if (merged.isEmpty()) return null;

        ArrayList<MessageHistory.MessageItem> result = new ArrayList<>(merged.values());
        // Sort by timestamp if needed, but LinkedHashMap usually keeps insertion order
        result.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        
        return result;
    }

    /* ============================================================
     * Edited History Bottom Dialog
     * ============================================================ */

    @SuppressLint("SetTextI18n")
    private void showBottomDialog(ArrayList<MessageHistory.MessageItem> messages) {
        try {
            Activity activity = WppCore.getCurrentActivity();
            if (activity == null) return;

            activity.runOnUiThread(() -> {
                try {
                    showSimpleAlertDialog(activity, messages);
                } catch (Throwable t) {
                    Toast.makeText(activity,
                            "Edit History: " + messages.size() + " versions",
                            Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void showSimpleAlertDialog(Activity activity, ArrayList<MessageHistory.MessageItem> messages) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("✏️ Edit History (" + messages.size() + " versions)");

            ArrayList<String> itemsList = new ArrayList<>();
            // Show newest first
            for (int i = messages.size() - 1; i >= 0; i--) {
                MessageHistory.MessageItem item = messages.get(i);
                String message = item.message;
                long timestamp = item.timestamp;

                String timeStr;
                if (timestamp == 0) {
                    timeStr = "Original";
                } else {
                    timeStr = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
                            .format(new Date(timestamp));
                }

                if (message == null) message = "[No text]";
                String preview = message.length() > 100 ?
                        message.substring(0, 100) + "..." : message;

                itemsList.add(timeStr + "\n" + preview);
            }

            String[] items = itemsList.toArray(new String[0]);
            builder.setItems(items, (dialog, which) -> {
                // Show full text on click
                int index = messages.size() - 1 - which;
                showFullTextMessage(activity, messages.get(index));
            });
            builder.setPositiveButton("Close", (dialog, which) -> dialog.dismiss());

            AlertDialog dialog = builder.create();
            dialog.show();
        } catch (Throwable t) {
            showAlternativeDialog(activity, messages);
        }
    }

    private void showFullTextMessage(Activity activity, MessageHistory.MessageItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Full Message Text");
        builder.setMessage(item.message);
        builder.setPositiveButton("Copy", (dialog, which) -> {
            com.wmods.wppenhacer.xposed.utils.Utils.setToClipboard(item.message);
            Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Back", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void showAlternativeDialog(Activity activity, ArrayList<MessageHistory.MessageItem> messages) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = messages.size() - 1; i >= 0; i--) {
                MessageHistory.MessageItem item = messages.get(i);
                String time = item.timestamp == 0 ? "Original" : 
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(item.timestamp));

                sb.append("[").append(time).append("]:\n")
                        .append(item.message != null ? item.message : "[No text]")
                        .append("\n\n");
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("✏️ Edit History");
            builder.setMessage(sb.toString());
            builder.setPositiveButton("Close", (dialog, which) -> dialog.dismiss());
            builder.show();
        } catch (Throwable t) {}
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Show Edit Message";
    }
}
