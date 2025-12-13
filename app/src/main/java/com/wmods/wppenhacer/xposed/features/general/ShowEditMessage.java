package com.wmods.wppenhacer.xposed.features.general;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
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

import java.lang.reflect.Field;
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
            @NonNull XSharedPreferences prefs
    ) {
        super(loader, prefs);
    }

    @Override
    public void doHook() throws Throwable {

        if (!prefs.getBoolean("antieditmessages", false)) return;

        /*
         * ------------------------------------------------------------
         * 1) Capture edited message history
         * ------------------------------------------------------------
         */
        Method onMessageEdit = Unobfuscator.loadMessageEditMethod(classLoader);
        Method callerMessageEditMethod = Unobfuscator.loadCallerMessageEditMethod(classLoader);
        Method getEditMessage = Unobfuscator.loadGetEditMessageMethod(classLoader);

        XposedBridge.hookMethod(onMessageEdit, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object raw = param.args[0];
                    if (raw == null) return;

                    Object edit = getEditMessage.invoke(null, raw);
                    if (edit == null) return;

                    Object invoked = callerMessageEditMethod.invoke(null, raw);
                    long timestamp = XposedHelpers.getLongField(invoked, "A00");

                    FMessageWpp f = new FMessageWpp(raw);
                    long id = f.getRowId();

                    String newText = f.getMessageStr();
                    if (newText == null) {
                        for (var m : ReflectionUtils.findAllMethodsUsingFilter(
                                raw.getClass(),
                                mm -> mm.getReturnType() == String.class
                        )) {
                            try {
                                newText = (String) m.invoke(raw);
                                if (newText != null) break;
                            } catch (Throwable ignored) {}
                        }
                    }

                    if (newText == null) return;

                    var history = MessageHistory.getInstance().getMessages(id);
                    if (history == null) {
                        MessageHistory.getInstance()
                                .insertMessage(
                                        id,
                                        MessageStore.getInstance().getCurrentMessageByID(id),
                                        0
                                );
                    }

                    MessageHistory.getInstance().insertMessage(id, newText, timestamp);

                } catch (Throwable t) {
                    logDebug(t);
                }
            }
        });

        /*
         * ------------------------------------------------------------
         * 2) Decorate the existing "Edited" label (SAFE PATH)
         * ------------------------------------------------------------
         */
        Method showMethod = Unobfuscator.loadEditMessageShowMethod(classLoader);
        Field labelField = Unobfuscator.loadEditMessageViewField(classLoader);

        XposedBridge.hookMethod(showMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {

                try {
                    TextView tv = (TextView) labelField.get(param.thisObject);
                    if (tv == null) return;

                    CharSequence text = tv.getText();
                    if (text == null) return;

                    // WhatsApp already decided this is edited
                    if (!text.toString().contains("Edited")) return;

                    // Prevent duplication on recycled views
                    if (text.toString().contains("📝")) return;

                    tv.setVisibility(View.VISIBLE);
                    tv.getPaint().setUnderlineText(true);
                    tv.append(" 📝");

                    tv.setOnClickListener(v -> {
                        try {
                            Object msg = XposedHelpers.callMethod(param.thisObject, "getFMessage");
                            if (msg == null) return;

                            long id = new FMessageWpp(msg).getRowId();
                            var messages = MessageHistory.getInstance().getMessages(id);
                            if (messages == null) messages = new ArrayList<>();

                            showBottomDialog(messages);

                        } catch (Throwable ignored) {}
                    });

                } catch (Throwable ignored) {}
            }
        });
    }

    /*
     * ------------------------------------------------------------
     * Bottom dialog (unchanged & safe)
     * ------------------------------------------------------------
     */
    @SuppressLint("SetTextI18n")
    private void showBottomDialog(ArrayList<MessageHistory.MessageItem> messages) {

        Objects.requireNonNull(WppCore.getCurrentConversation()).runOnUiThread(() -> {

            Context ctx = (Context) WppCore.getCurrentConversation();
            var dialog = WppCore.createBottomDialog(ctx);

            NestedScrollView scroll = new NestedScrollView(ctx);
            scroll.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

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
            title.setTextColor(DesignUtils.getPrimaryTextColor());
            title.setTextSize(16f);

            ListView list = new NoScrollListView(ctx);
            list.setAdapter(new MessageAdapter(ctx, messages));

            ImageView handle = new ImageView(ctx);
            handle.setLayoutParams(new LinearLayout.LayoutParams(
                    Utils.dipToPixels(70),
                    Utils.dipToPixels(8)
            ));
            handle.setBackground(
                    DesignUtils.alphaDrawable(
                            DesignUtils.createDrawable("rc_dotline_dialog", Color.BLACK),
                            DesignUtils.getPrimaryTextColor(),
                            33
                    )
            );

            Button ok = new Button(ctx);
            ok.setText("OK");
            ok.setOnClickListener(v -> dialog.dismissDialog());

            root.addView(handle);
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
