package com.wmods.wppenhacer.xposed.features.general;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import com.wmods.wppenhacer.adapter.MessageAdapter;
import com.wmods.wppenhacer.views.NoScrollListView;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

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

        logDebug("=== ShowEditMessage: Starting hook ===");

        if (!prefs.getBoolean("antieditmessages", false)) {
            logDebug("ShowEditMessage: Feature disabled in settings");
            return;
        }

        logDebug("ShowEditMessage: Feature enabled!");

        /* ============================================================
         * PART 1 — Capture edited message history (DATA)
         * ============================================================ */

        Method onMessageEdit = Unobfuscator.loadMessageEditMethod(classLoader);
        Method callerMessageEdit = Unobfuscator.loadCallerMessageEditMethod(classLoader);
        Method getEditMessage = Unobfuscator.loadGetEditMessageMethod(classLoader);

        logDebug("ShowEditMessage PART 1 methods:");
        logDebug("  onMessageEdit: " + (onMessageEdit != null ? "Found" : "NULL"));
        logDebug("  callerMessageEdit: " + (callerMessageEdit != null ? "Found" : "NULL"));
        logDebug("  getEditMessage: " + (getEditMessage != null ? "Found" : "NULL"));

        if (onMessageEdit != null) {
            XposedBridge.hookMethod(onMessageEdit, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        logDebug("=== onMessageEdit hook triggered ===");

                        Object rawMsg = param.args[0];
                        if (rawMsg == null) {
                            logDebug("rawMsg is null");
                            return;
                        }

                        logDebug("Raw message class: " + rawMsg.getClass().getName());

                        Object editMsg = getEditMessage.invoke(null, rawMsg);
                        if (editMsg == null) {
                            logDebug("editMsg is null");
                            return;
                        }

                        Object invoked = callerMessageEdit.invoke(null, rawMsg);
                        long timestamp = XposedHelpers.getLongField(invoked, "A00");
                        logDebug("Edit timestamp: " + timestamp);

                        FMessageWpp fMessage = new FMessageWpp(rawMsg);
                        long rowId = fMessage.getRowId();
                        logDebug("Row ID: " + rowId);

                        String newText = fMessage.getMessageStr();
                        if (newText == null) {
                            logDebug("Getting message via reflection...");
                            for (Method m : ReflectionUtils.findAllMethodsUsingFilter(
                                    rawMsg.getClass(),
                                    mm -> mm.getReturnType() == String.class
                            )) {
                                newText = (String) m.invoke(rawMsg);
                                if (newText != null) {
                                    logDebug("Found message via method: " + m.getName());
                                    break;
                                }
                            }
                            if (newText == null) {
                                logDebug("Could not find message text");
                                return;
                            }
                        }

                        logDebug("New message text (first 50 chars): " +
                                (newText.length() > 50 ? newText.substring(0, 50) + "..." : newText));

                        var history = MessageHistory.getInstance().getMessages(rowId);
                        logDebug("Existing history: " + (history != null ? history.size() + " items" : "null"));

                        if (history == null) {
                            String original = MessageStore
                                    .getInstance()
                                    .getCurrentMessageByID(rowId);
                            logDebug("Original message: " +
                                    (original != null && original.length() > 50 ?
                                            original.substring(0, 50) + "..." : original));

                            if (original != null) {
                                MessageHistory.getInstance()
                                        .insertMessage(rowId, original, 0);
                                logDebug("Inserted original message");
                            }
                        }

                        MessageHistory.getInstance()
                                .insertMessage(rowId, newText, timestamp);
                        logDebug("Inserted edited message into history");

                    } catch (Throwable t) {
                        logDebug("Error in onMessageEdit: " + t.getMessage());
                    }
                }
            });
        } else {
            logDebug("WARNING: onMessageEdit method not found! Edit capture may not work.");
        }

        /* ============================================================
         * PART 2 — UI: Conversation Row Bind (A2K)
         * ============================================================ */

        Method bindMethod = Unobfuscator.loadConversationRowBindMethod(classLoader);
        logDebug("ShowEditMessage bind method: " +
                (bindMethod != null ? Unobfuscator.getMethodDescriptor(bindMethod) : "NULL"));

        if (bindMethod != null) {
            XposedBridge.hookMethod(bindMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        logDebug("=== ShowEditMessage: Bind method triggered ===");

                        if (param.args == null || param.args.length < 3) {
                            logDebug("Invalid args length: " + (param.args != null ? param.args.length : "null"));
                            return;
                        }

                        if (!(param.args[1] instanceof TextView)) {
                            logDebug("Arg[1] is not TextView: " + param.args[1].getClass().getName());
                            return;
                        }

                        TextView metaView = (TextView) param.args[1];
                        Object msgObj = param.args[2];

                        if (msgObj == null) {
                            logDebug("msgObj is null");
                            return;
                        }

                        logDebug("Message object class: " + msgObj.getClass().getName());

                        long rowId = new FMessageWpp(msgObj).getRowId();
                        logDebug("Row ID for UI: " + rowId);

                        var history = MessageHistory.getInstance().getMessages(rowId);
                        logDebug("History size: " + (history != null ? history.size() : "null"));

                        // Debug: Show history details
                        if (history != null) {
                            logDebug("History details:");
                            for (int i = 0; i < history.size(); i++) {
                                var item = history.get(i);
                                // Use reflection to get message and timestamp
                                String message = null;
                                long timestamp = 0;

                                try {
                                    // Try to get message using reflection
                                    Method getMessageMethod = item.getClass().getMethod("getMessage");
                                    if (getMessageMethod != null) {
                                        message = (String) getMessageMethod.invoke(item);
                                    }
                                } catch (Exception e) {
                                    // Try field access
                                    try {
                                        message = (String) XposedHelpers.getObjectField(item, "message");
                                    } catch (Exception e2) {
                                        // Try common field names
                                        try {
                                            message = (String) XposedHelpers.getObjectField(item, "mMessage");
                                        } catch (Exception e3) {
                                            try {
                                                message = (String) XposedHelpers.getObjectField(item, "text");
                                            } catch (Exception e4) {
                                                // Give up
                                            }
                                        }
                                    }
                                }

                                try {
                                    // Try to get timestamp using reflection
                                    Method getTimestampMethod = item.getClass().getMethod("getTimestamp");
                                    if (getTimestampMethod != null) {
                                        timestamp = (long) getTimestampMethod.invoke(item);
                                    }
                                } catch (Exception e) {
                                    // Try field access
                                    try {
                                        timestamp = XposedHelpers.getLongField(item, "timestamp");
                                    } catch (Exception e2) {
                                        // Try common field names
                                        try {
                                            timestamp = XposedHelpers.getLongField(item, "mTimestamp");
                                        } catch (Exception e3) {
                                            try {
                                                timestamp = XposedHelpers.getLongField(item, "time");
                                            } catch (Exception e4) {
                                                // Give up
                                            }
                                        }
                                    }
                                }

                                logDebug("  [" + i + "] " + timestamp + ": " +
                                        (message != null && message.length() > 30 ?
                                                message.substring(0, 30) + "..." : message));
                            }
                        }

                        // ✅ Only edited messages (need at least 2 versions)
                        if (history == null || history.size() <= 1) {
                            logDebug("No edit history or only 1 version");
                            return;
                        }

                        CharSequence cs = metaView.getText();
                        String currentText = cs != null ? cs.toString() : "";
                        logDebug("Current meta text: " + currentText);

                        if (currentText.contains("📝")) {
                            logDebug("Already has edit icon");
                            return;
                        }

                        // Make text view clickable
                        metaView.setClickable(true);
                        metaView.setFocusable(true);

                        metaView.setVisibility(View.VISIBLE);
                        metaView.getPaint().setUnderlineText(true);
                        metaView.append(" 📝");
                        logDebug("Added edit icon to message");

                        // Store history in view tag for click handler
                        metaView.setTag(history);

                        // Set click listener
                        metaView.setOnClickListener(v -> {
                            logDebug("=== EDIT ICON CLICKED! ===");
                            logDebug("Click listener triggered");

                            try {
                                // Get history from tag
                                ArrayList<MessageHistory.MessageItem> clickedHistory =
                                        (ArrayList<MessageHistory.MessageItem>) v.getTag();

                                if (clickedHistory == null) {
                                    logDebug("No history in tag!");
                                    return;
                                }

                                logDebug("History size in click: " + clickedHistory.size());

                                // Test with Toast first
                                Activity activity = WppCore.getCurrentActivity();
                                if (activity != null) {
                                    activity.runOnUiThread(() -> {
                                        Toast.makeText(activity,
                                                "Edit History: " + clickedHistory.size() + " versions",
                                                Toast.LENGTH_SHORT).show();
                                    });

                                    // Wait a bit then show dialog
                                    new Thread(() -> {
                                        try {
                                            Thread.sleep(500); // Wait for toast
                                            showBottomDialog(clickedHistory);
                                        } catch (Exception e) {
                                            logDebug("Thread error: " + e.getMessage());
                                        }
                                    }).start();
                                } else {
                                    logDebug("No activity found!");
                                }

                            } catch (Throwable t) {
                                logDebug("Click error: " + t.getMessage());
                                t.printStackTrace();
                            }
                        });

                        logDebug("Edit icon setup complete for row: " + rowId);

                    } catch (Throwable t) {
                        logDebug("Bind method error: " + t.getMessage());
                        t.printStackTrace();
                    }
                }
            });

            logDebug("ShowEditMessage: Hook setup complete");

        } else {
            logDebug("ERROR: bindMethod not found! UI won't work.");
        }
    }

    /* ============================================================
     * Edited History Bottom Dialog
     * ============================================================ */

    @SuppressLint("SetTextI18n")
    private void showBottomDialog(ArrayList<MessageHistory.MessageItem> messages) {
        logDebug("showBottomDialog called with " + messages.size() + " messages");

        try {
            // Get current activity
            Activity activity = WppCore.getCurrentActivity();
            if (activity == null) {
                logDebug("ERROR: No activity found!");
                return;
            }

            logDebug("Found activity: " + activity.getClass().getName());

            activity.runOnUiThread(() -> {
                try {
                    logDebug("Creating dialog on UI thread...");

                    // First try simple AlertDialog
                    showSimpleAlertDialog(activity, messages);

                } catch (Throwable t) {
                    logDebug("Dialog creation error: " + t.getMessage());
                    t.printStackTrace();

                    // Last resort: show toast
                    Toast.makeText(activity,
                            "Edit History: " + messages.size() + " versions",
                            Toast.LENGTH_LONG).show();
                }
            });

        } catch (Throwable t) {
            logDebug("showBottomDialog error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    private void showSimpleAlertDialog(Activity activity, ArrayList<MessageHistory.MessageItem> messages) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("✏️ Edit History (" + messages.size() + " versions)");

            // Create items array (show newest first)
            ArrayList<String> itemsList = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0; i--) {
                MessageHistory.MessageItem item = messages.get(i);

                // Get message text using reflection
                String message = getMessageFromItem(item);
                long timestamp = getTimestampFromItem(item);

                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date(timestamp));

                if (message == null) message = "[No text]";

                String preview = message.length() > 100 ?
                        message.substring(0, 100) + "..." : message;

                itemsList.add("Version " + (messages.size() - i) + " • " + time + "\n" + preview);
            }

            String[] items = itemsList.toArray(new String[0]);
            builder.setItems(items, null);
            builder.setPositiveButton("Close", (dialog, which) -> dialog.dismiss());

            AlertDialog dialog = builder.create();
            dialog.show();

            logDebug("Simple AlertDialog shown successfully");

        } catch (Throwable t) {
            logDebug("Simple AlertDialog error: " + t.getMessage());
            // Try alternative approach
            showAlternativeDialog(activity, messages);
        }
    }

    private String getMessageFromItem(MessageHistory.MessageItem item) {
        if (item == null) return null;

        try {
            // Try method
            Method getMessageMethod = item.getClass().getMethod("getMessage");
            if (getMessageMethod != null) {
                return (String) getMessageMethod.invoke(item);
            }
        } catch (Exception e) {
            // Try fields
            try {
                return (String) XposedHelpers.getObjectField(item, "message");
            } catch (Exception e2) {
                try {
                    return (String) XposedHelpers.getObjectField(item, "mMessage");
                } catch (Exception e3) {
                    try {
                        return (String) XposedHelpers.getObjectField(item, "text");
                    } catch (Exception e4) {
                        return "Unknown message";
                    }
                }
            }
        }
        return "Unknown message";
    }

    private long getTimestampFromItem(MessageHistory.MessageItem item) {
        if (item == null) return 0;

        try {
            // Try method
            Method getTimestampMethod = item.getClass().getMethod("getTimestamp");
            if (getTimestampMethod != null) {
                return (long) getTimestampMethod.invoke(item);
            }
        } catch (Exception e) {
            // Try fields
            try {
                return XposedHelpers.getLongField(item, "timestamp");
            } catch (Exception e2) {
                try {
                    return XposedHelpers.getLongField(item, "mTimestamp");
                } catch (Exception e3) {
                    try {
                        return XposedHelpers.getLongField(item, "time");
                    } catch (Exception e4) {
                        return System.currentTimeMillis();
                    }
                }
            }
        }
        return System.currentTimeMillis();
    }

    private void showAlternativeDialog(Activity activity, ArrayList<MessageHistory.MessageItem> messages) {
        try {
            // Build message text
            StringBuilder sb = new StringBuilder();
            sb.append("Edit History (").append(messages.size()).append(" versions):\n\n");

            for (int i = messages.size() - 1; i >= 0; i--) {
                MessageHistory.MessageItem item = messages.get(i);
                String message = getMessageFromItem(item);
                long timestamp = getTimestampFromItem(item);

                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date(timestamp));

                sb.append("Version ").append(messages.size() - i)
                        .append(" (").append(time).append("):\n")
                        .append(message != null ? message : "[No text]")
                        .append("\n\n");
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle("✏️ Edit History");
            builder.setMessage(sb.toString());
            builder.setPositiveButton("Close", (dialog, which) -> dialog.dismiss());
            builder.show();

        } catch (Throwable t) {
            logDebug("Alternative dialog error: " + t.getMessage());
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Show Edit Message";
    }
}