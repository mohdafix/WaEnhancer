package com.wmods.wppenhacer.xposed.features.general;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ShowEditMessage extends Feature {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public ShowEditMessage(
            @NonNull ClassLoader loader,
            @NonNull XSharedPreferences preferences
    ) {
        super(loader, preferences);
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Show Edit Message";
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("antieditmessages", false)) return;

        hookEditCapture();
        hookConversationRow();
    }

    /* ============================================================
     * PART 1 — Capture edit history (RESTART SAFE + DEDUPED)
     * ============================================================ */

    private void hookEditCapture() throws Throwable {
        Method onEdit = Unobfuscator.loadMessageEditMethod(classLoader);
        Method caller = Unobfuscator.loadCallerMessageEditMethod(classLoader);
        Method getEdit = Unobfuscator.loadGetEditMessageMethod(classLoader);

        if (onEdit == null || caller == null || getEdit == null) return;

        XposedBridge.hookMethod(onEdit, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object rawMsg = param.args[0];
                    if (rawMsg == null) return;

                    // Only edits
                    if (getEdit.invoke(null, rawMsg) == null) return;

                    FMessageWpp fMsg = new FMessageWpp(rawMsg);
                    long rowId = fMsg.getRowId();
                    if (rowId <= 0) return;

                    String newText = fMsg.getMessageStr();

                    if (TextUtils.isEmpty(newText)) {
                        for (Method m : ReflectionUtils.findAllMethodsUsingFilter(
                                rawMsg.getClass(),
                                mm -> mm.getReturnType() == String.class
                        )) {
                            newText = (String) m.invoke(rawMsg);
                            if (!TextUtils.isEmpty(newText)) break;
                        }
                    }

                    if (TextUtils.isEmpty(newText)) return;

                    Object invoked = caller.invoke(null, rawMsg);
                    long timestamp = XposedHelpers.getLongField(invoked, "A00");

                    if (timestamp <= 0) return;

                    MessageHistory db = MessageHistory.getInstance();
                    ArrayList<MessageHistory.MessageItem> history =
                            db.getMessages(rowId);

                    /* ---------- Insert ORIGINAL once ---------- */
                    if (history == null || history.isEmpty()) {
                        String original = MessageStore.getInstance()
                                .getCurrentMessageByID(rowId);

                        if (!TextUtils.isEmpty(original)) {
                            db.insertMessage(rowId, original, 0);
                        }
                    }

                    /* ---------- Strong dedupe ---------- */
                    history = db.getMessages(rowId);
                    if (history != null && !history.isEmpty()) {
                        MessageHistory.MessageItem last =
                                history.get(history.size() - 1);

                        if (newText.equals(last.message)) {
                            if (Math.abs(timestamp - last.timestamp) <= 2000) {
                                return; // duplicate edit
                            }
                        }
                    }

                    db.insertMessage(rowId, newText, timestamp);

                } catch (Throwable ignored) {}
            }
        });
    }

    /* ============================================================
     * PART 2 — Conversation row indicator
     * ============================================================ */

    private void hookConversationRow() throws Throwable {
        Method bindMethod = Unobfuscator.loadConversationRowBindMethod(classLoader);
        if (bindMethod == null) return;

        XposedBridge.hookMethod(bindMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args == null || param.args.length < 3) return;
                    if (!(param.args[1] instanceof TextView)) return;

                    TextView metaView = (TextView) param.args[1];
                    Object msgObj = param.args[2];

                    long rowId = new FMessageWpp(msgObj).getRowId();
                    if (rowId <= 0) return;

                    ArrayList<MessageHistory.MessageItem> history =
                            MessageHistory.getInstance().getMessages(rowId);

                    if (history == null || history.size() <= 1) return;

                    metaView.post(() -> {
                        String txt = metaView.getText() != null
                                ? metaView.getText().toString()
                                : "";

                        if (txt.contains("📝")) return;

                        metaView.setClickable(true);
                        metaView.getPaint().setUnderlineText(true);
                        metaView.append(" 📝");
                        metaView.setOnClickListener(v ->
                                showHistoryDialog(rowId)
                        );
                    });

                } catch (Throwable ignored) {}
            }
        });
    }

    /* ============================================================
     * PART 3 — Dialog loader
     * ============================================================ */

    private void showHistoryDialog(long rowId) {
        Activity activity = WppCore.getCurrentActivity();
        if (activity == null) return;

        new Thread(() -> {
            ArrayList<MessageHistory.MessageItem> list =
                    MessageHistory.getInstance().getMessages(rowId);

            if (list == null || list.size() <= 1) return;

            Collections.sort(list, (a, b) ->
                    Long.compare(a.timestamp, b.timestamp));

            MAIN.post(() -> showBottomDialog(activity, list));
        }).start();
    }

    /* ============================================================
     * PART 4 — UI
     * ============================================================ */

    @SuppressLint("SetTextI18n")
    private void showBottomDialog(
            Activity activity,
            ArrayList<MessageHistory.MessageItem> messages
    ) {
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
            Toast.makeText(activity,
                    "Failed to show edit history",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private View createHistoryItem(
            Activity ctx,
            MessageHistory.MessageItem item
    ) {
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

        int base = DesignUtils.getPrimaryTextColor();
        time.setTextColor(android.graphics.Color.argb(
                160,
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base)
        ));

        if (item.timestamp == 0) {
            time.setText("Original");
        } else {
            time.setText(
                    new SimpleDateFormat(
                            "dd MMM · HH:mm",
                            Locale.getDefault()
                    ).format(new Date(item.timestamp))
            );
        }

        TextView msg = new TextView(ctx);
        msg.setTextSize(14f);
        msg.setTextColor(DesignUtils.getPrimaryTextColor());
        msg.setMaxLines(3);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        msg.setText(item.message != null
                ? item.message
                : "[No text]"
        );
        msg.setPadding(0, Utils.dipToPixels(6), 0, 0);

        card.addView(time);
        card.addView(msg);

        card.setOnClickListener(v ->
                showFullTextMessage(ctx, item)
        );

        return card;
    }

    private void showFullTextMessage(
            Activity activity,
            MessageHistory.MessageItem item
    ) {
        new AlertDialog.Builder(activity)
                .setTitle("Full message")
                .setMessage(item.message)
                .setPositiveButton("Copy", (d, w) -> {
                    Utils.setToClipboard(item.message);
                    Toast.makeText(activity,
                            "Copied",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
