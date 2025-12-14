package com.wmods.wppenhacer.xposed.features.general;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.Objects;

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

        /* ------------------------------------------------------------
         * PART 1: Capture edit history (DATA)
         * ------------------------------------------------------------ */

        Method onMessageEdit = Unobfuscator.loadMessageEditMethod(classLoader);
        Method callerMessageEdit = Unobfuscator.loadCallerMessageEditMethod(classLoader);
        Method getEditMessage = Unobfuscator.loadGetEditMessageMethod(classLoader);

        XposedBridge.hookMethod(onMessageEdit, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object raw = param.args[0];
                    if (raw == null) return;

                    if (getEditMessage.invoke(null, raw) == null) return;

                    Object invoked = callerMessageEdit.invoke(null, raw);
                    long ts = XposedHelpers.getLongField(invoked, "A00");

                    FMessageWpp f = new FMessageWpp(raw);
                    long rowId = f.getRowId();

                    String newMsg = f.getMessageStr();
                    if (newMsg == null) {
                        for (var m : ReflectionUtils.findAllMethodsUsingFilter(
                                raw.getClass(),
                                mm -> mm.getReturnType() == String.class
                        )) {
                            newMsg = (String) m.invoke(raw);
                            if (newMsg != null) break;
                        }
                        if (newMsg == null) return;
                    }

                    var history = MessageHistory.getInstance().getMessages(rowId);
                    if (history == null) {
                        String original = MessageStore
                                .getInstance()
                                .getCurrentMessageByID(rowId);
                        MessageHistory.getInstance()
                                .insertMessage(rowId, original, 0);
                    }

                    MessageHistory.getInstance()
                            .insertMessage(rowId, newMsg, ts);

                } catch (Throwable ignored) {}
            }
        });

        /* ------------------------------------------------------------
         * PART 2: UI — Edited label (STABLE)
         * ------------------------------------------------------------ */

        Method showMethod = Unobfuscator.loadEditMessageShowMethod(classLoader);
        var textViewField = Unobfuscator.loadEditMessageViewField(classLoader);

        XposedBridge.hookMethod(showMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    TextView tv = (TextView) textViewField.get(param.thisObject);
                    if (tv == null) return;

                    Object msgObj = XposedHelpers.callMethod(param.thisObject, "getFMessage");
                    if (msgObj == null) return;

                    long rowId = new FMessageWpp(msgObj).getRowId();
                    var history = MessageHistory
                            .getInstance()
                            .getMessages(rowId);

                    // 🔑 THIS is why 📝 no longer lies
                    if (history == null || history.size() < 2) return;

                    if (tv.getTag() != null) return;
                    tv.setTag("edited");

                    tv.getPaint().setUnderlineText(true);
                    tv.append(" \uD83D\uDCDD");

                    tv.setOnClickListener(v ->
                            showBottomDialog(history)
                    );

                } catch (Throwable ignored) {}
            }
        });
    }

    /* ------------------------------------------------------------
     * Bottom dialog
     * ------------------------------------------------------------ */
    @SuppressLint("SetTextI18n")
    private void showBottomDialog(
            ArrayList<MessageHistory.MessageItem> messages
    ) {

        Objects.requireNonNull(WppCore.getCurrentConversation())
                .runOnUiThread(() -> {

                    Context ctx = WppCore.getCurrentConversation();
                    var dialog = WppCore.createBottomDialog(ctx);

                    NestedScrollView scroll = new NestedScrollView(ctx);
                    scroll.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    ));
                    scroll.setFillViewport(true);

                    LinearLayout root = new LinearLayout(ctx);
                    root.setOrientation(LinearLayout.VERTICAL);
                    root.setPadding(
                            Utils.dipToPixels(20),
                            Utils.dipToPixels(20),
                            Utils.dipToPixels(20),
                            0
                    );
                    root.setBackground(
                            DesignUtils.createDrawable(
                                    "rc_dialog_bg",
                                    DesignUtils.getPrimarySurfaceColor()
                            )
                    );

                    TextView title = new TextView(ctx);
                    title.setText(ResId.string.edited_history);
                    title.setTypeface(null, Typeface.BOLD);
                    title.setTextSize(16f);
                    title.setTextColor(DesignUtils.getPrimaryTextColor());

                    ListView list = new NoScrollListView(ctx);
                    list.setAdapter(new MessageAdapter(ctx, messages));

                    Button ok = new Button(ctx);
                    ok.setText("OK");
                    ok.setOnClickListener(v -> dialog.dismissDialog());

                    root.addView(title);
                    root.addView(list);
                    root.addView(ok);

                    scroll.addView(root);
                    dialog.setContentView(scroll);
                    dialog.setCanceledOnTouchOutside(true);
                    dialog.showDialog();
                });
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Show Edit Message";
    }
}
