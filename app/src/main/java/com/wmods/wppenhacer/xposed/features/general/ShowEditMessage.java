package com.wmods.wppenhacer.xposed.features.general;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.core.db.MessageStore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

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

        if (!prefs.getBoolean("antieditmessages", false)) return;

        /* ============================================================
         * PART 1 — Capture edited message history
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

                    } catch (Throwable ignored) {}
                }
            });
        }

        /* ============================================================
         * PART 2 — Conversation row UI hook
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

                        ArrayList<MessageHistory.MessageItem> history =
                                getMergedHistory(rowId);

                        if (history == null || history.size() <= 1) return;

                        String currentText =
                                metaView.getText() != null ? metaView.getText().toString() : "";

                        if (currentText.contains("📝")) return;

                        metaView.setClickable(true);
                        metaView.getPaint().setUnderlineText(true);
                        metaView.append(" 📝");
                        metaView.setTag(history);

                        metaView.setOnClickListener(v ->
                                showBottomDialog(
                                        (ArrayList<MessageHistory.MessageItem>) v.getTag()
                                )
                        );

                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    /* ============================================================
     * Merge WA + local history
     * ============================================================ */

    private ArrayList<MessageHistory.MessageItem> getMergedHistory(long rowId) {
        Map<String, MessageHistory.MessageItem> merged = new LinkedHashMap<>();

        List<MessageHistory.MessageItem> wa =
                MessageStore.getInstance().getWAEditHistory(rowId);
        for (var item : wa) {
            if (!TextUtils.isEmpty(item.message)) merged.put(item.message, item);
        }

        ArrayList<MessageHistory.MessageItem> local =
                MessageHistory.getInstance().getMessages(rowId);
        if (local != null) {
            for (var item : local) {
                if (!TextUtils.isEmpty(item.message) && !merged.containsKey(item.message)) {
                    merged.put(item.message, item);
                }
            }
        }

        if (merged.isEmpty()) return null;

        ArrayList<MessageHistory.MessageItem> result =
                new ArrayList<>(merged.values());
        result.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        return result;
    }

    /* ============================================================
     * Bottom Sheet UI
     * ============================================================ */

    @SuppressLint("SetTextI18n")
    private void showBottomDialog(ArrayList<MessageHistory.MessageItem> messages) {
        Activity activity = WppCore.getCurrentActivity();
        if (activity == null) return;

        activity.runOnUiThread(() -> {
            try {
                var dialog = WppCore.createBottomDialog(activity);

                LinearLayout root = new LinearLayout(activity);
                root.setOrientation(LinearLayout.VERTICAL);
                int pad = Utils.dipToPixels(16);
                root.setPadding(pad, pad, pad, pad);
                root.setBackground(
                        DesignUtils.createDrawable(
                                "rc_dialog_bg",
                                DesignUtils.getPrimarySurfaceColor()
                        )
                );

                View handle = new View(activity);
                LinearLayout.LayoutParams hlp =
                        new LinearLayout.LayoutParams(
                                Utils.dipToPixels(48),
                                Utils.dipToPixels(5)
                        );
                hlp.bottomMargin = Utils.dipToPixels(12);
                hlp.gravity = 17;
                handle.setLayoutParams(hlp);
                handle.setBackground(
                        DesignUtils.alphaDrawable(
                                DesignUtils.createDrawable("rc_dotline_dialog", 0),
                                DesignUtils.getPrimaryTextColor(),
                                35
                        )
                );
                root.addView(handle);

                TextView title = new TextView(activity);
                title.setText("✏️ Edit history (" + messages.size() + ")");
                title.setTextSize(16f);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setTextColor(DesignUtils.getPrimaryTextColor());
                title.setPadding(0, 0, 0, Utils.dipToPixels(12));
                root.addView(title);

                NestedScrollView scroll = new NestedScrollView(activity);
                LinearLayout list = new LinearLayout(activity);
                list.setOrientation(LinearLayout.VERTICAL);
                scroll.addView(list);

                for (int i = messages.size() - 1; i >= 0; i--) {
                    list.addView(createHistoryItem(activity, messages.get(i)));
                }

                root.addView(scroll);
                dialog.setContentView(root);
                dialog.setCanceledOnTouchOutside(true);
                dialog.showDialog();

            } catch (Throwable t) {
                Toast.makeText(activity, "Failed to show edit history", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private View createHistoryItem(Activity ctx, MessageHistory.MessageItem item) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Utils.dipToPixels(12);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = Utils.dipToPixels(10);
        card.setLayoutParams(lp);

        card.setBackground(
                DesignUtils.alphaDrawable(
                        DesignUtils.createDrawable("selector_bg", 0),
                        DesignUtils.getPrimaryTextColor(),
                        8
                )
        );

        TextView time = new TextView(ctx);
        time.setTextSize(12f);
        time.setTypeface(Typeface.MONOSPACE);

        int baseColor = DesignUtils.getPrimaryTextColor();
        time.setTextColor(
                android.graphics.Color.argb(
                        160,
                        android.graphics.Color.red(baseColor),
                        android.graphics.Color.green(baseColor),
                        android.graphics.Color.blue(baseColor)
                )
        );



        if (item.timestamp == 0) {
            time.setText("Original");
        } else {
            time.setText(
                    new SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault())
                            .format(new Date(item.timestamp))
            );
        }

        TextView msg = new TextView(ctx);
        msg.setTextSize(14f);
        msg.setTextColor(DesignUtils.getPrimaryTextColor());
        msg.setMaxLines(3);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        msg.setText(item.message != null ? item.message : "[No text]");
        msg.setPadding(0, Utils.dipToPixels(6), 0, 0);

        card.addView(time);
        card.addView(msg);

        card.setOnClickListener(v -> showFullTextMessage(ctx, item));

        return card;
    }

    private void showFullTextMessage(Activity activity, MessageHistory.MessageItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Full message");
        builder.setMessage(item.message);
        builder.setPositiveButton("Copy", (d, w) -> {
            Utils.setToClipboard(item.message);
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Close", null);
        builder.show();
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Show Edit Message";
    }
}
