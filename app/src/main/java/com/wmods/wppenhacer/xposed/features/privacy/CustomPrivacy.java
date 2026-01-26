package com.wmods.wppenhacer.xposed.features.privacy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.adapter.CustomPrivacyAdapter;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.features.others.MenuHome;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.json.JSONObject;
import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;

public class CustomPrivacy extends Feature {
    private static final List<PrivacyOption> OPTIONS = Arrays.asList(
            new PrivacyOption("HideSeen", ResId.string.hideread, ResId.string.hideread_sum, "hideread", false),
            new PrivacyOption("HideViewStatus", ResId.string.hidestatusview, ResId.string.hidestatusview_sum, "hidestatusview", false),
            new PrivacyOption("HideReceipt", ResId.string.hidereceipt, ResId.string.hidereceipt_sum, "hidereceipt", false),
            new PrivacyOption("HideTyping", ResId.string.ghostmode, ResId.string.ghostmode_sum, "ghostmode_t", false),
            new PrivacyOption("HideRecording", ResId.string.ghostmode_r, ResId.string.ghostmode_sum_r, "ghostmode_r", false),
            new PrivacyOption("BlockCall", ResId.string.block_call, ResId.string.call_blocker_sum, "call_privacy", true),
            new PrivacyOption("BlueOnReply", ResId.string.blueonreply, ResId.string.blueonreply_sum, "blueonreply", false)
    );

    private Method chatUserJidMethod;
    private Method groupUserJidMethod;

    public CustomPrivacy(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    public static JSONObject getJSON(String number) {
        if (Objects.equals(Utils.xprefs.getString("custom_privacy_type", "0"), "0") || TextUtils.isEmpty(number))
            return new JSONObject();
        return WppCore.getPrivJSON(number + "_privacy", new JSONObject());
    }

    @Override
    public void doHook() throws Throwable {
        if (Objects.equals(Utils.xprefs.getString("custom_privacy_type", "0"), "0")) return;

        Class<?> ContactInfoActivityClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, ".ContactInfoActivity");
        Class<?> GroupInfoActivityClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, ".GroupChatInfoActivity");
        Class<?> userJidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.UserJid");
        Class<?> groupJidClass = Unobfuscator.findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.GroupJid");

        chatUserJidMethod = ReflectionUtils.findMethodUsingFilter(ContactInfoActivityClass, method -> method.getParameterCount() == 0 && userJidClass.isAssignableFrom(method.getReturnType()));
        groupUserJidMethod = ReflectionUtils.findMethodUsingFilter(GroupInfoActivityClass, method -> method.getParameterCount() == 0 && groupJidClass.isAssignableFrom(method.getReturnType()));

        var type = Integer.parseInt(Utils.xprefs.getString("custom_privacy_type", "0"));

        if (type == 1) {
            var hooker = new WppCore.ActivityChangeState() {
                @SuppressLint("ResourceType")
                @Override
                public void onChange(Activity activity, ChangeType type) {
                    try {
                        if (type != ChangeType.STARTED) return;
                        if (!ContactInfoActivityClass.isInstance(activity) && !GroupInfoActivityClass.isInstance(activity))
                            return;
                        if (activity.findViewById(0x7f0a9999) != null) return;
                        int id = Utils.getID("contact_info_security_card_layout", "id");
                        ViewGroup infoLayout = activity.getWindow().findViewById(id);
                        Drawable icon = activity.getDrawable(ResId.drawable.ic_privacy);
                        View itemView = createItemView(activity, activity.getString(ResId.string.custom_privacy), activity.getString(ResId.string.custom_privacy_sum), icon);
                        itemView.setId(0x7f0a9999);
                        itemView.setOnClickListener((v) -> showPrivacyDialog(activity, ContactInfoActivityClass.isInstance(activity)));
                        infoLayout.addView(itemView);
                    } catch (Throwable e) {
                        logDebug(e);
                        Utils.showToast(e.getMessage(), Toast.LENGTH_SHORT);
                    }
                }
            };
            WppCore.addListenerActivity(hooker);
        } else if (type == 2) {
            var hooker = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    var menu = (Menu) param.args[0];
                    var activity = (Activity) param.thisObject;
                    var customPrivacy = menu.add(0, 0, 0, ResId.string.custom_privacy);
                    customPrivacy.setIcon(ResId.drawable.ic_privacy);
                    customPrivacy.setOnMenuItemClickListener(item -> {
                        showPrivacyDialog(activity, ContactInfoActivityClass.isInstance(activity));
                        return true;
                    });
                }
            };
            XposedHelpers.findAndHookMethod(ContactInfoActivityClass, "onCreateOptionsMenu", Menu.class, hooker);
            XposedHelpers.findAndHookMethod(GroupInfoActivityClass, "onCreateOptionsMenu", Menu.class, hooker);
        }

        if (type == 0) return;

        var icon = DesignUtils.resizeDrawable(DesignUtils.getDrawable(ResId.drawable.ic_privacy), Utils.dipToPixels(24), Utils.dipToPixels(24));
        icon.setTint(0xff8696a0);
        MenuHome.menuItems.add((menu, activity) -> menu.add(0, 0, 0, ResId.string.custom_privacy).setIcon(icon).setOnMenuItemClickListener(item -> {
            showCustomPrivacyList(activity, ContactInfoActivityClass, GroupInfoActivityClass);
            return true;
        }));
    }

    private View createItemView(Activity activity, String title, String summary, Drawable icon) {
        LinearLayout mainLayout = new LinearLayout(activity);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        mainLayout.setOrientation(LinearLayout.HORIZONTAL);
        mainLayout.setPadding(16, 16, 16, 16);

        ImageView imageView = new ImageView(activity);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                Utils.dipToPixels(20),
                Utils.dipToPixels(20)
        );
        imageParams.setMargins(Utils.dipToPixels(20), 0, Utils.dipToPixels(16), Utils.dipToPixels(20));
        imageView.setLayoutParams(imageParams);
        icon.setTint(0xff8696a0);
        imageView.setImageDrawable(icon);

        LinearLayout textContainer = new LinearLayout(activity);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        containerParams.setMarginStart(16);
        textContainer.setLayoutParams(containerParams);
        textContainer.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(activity);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleView.setLayoutParams(titleParams);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setText(title);
        titleView.setTextColor(DesignUtils.getPrimaryTextColor());

        TextView summaryView = new TextView(activity);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryParams.setMarginStart(4);
        summaryView.setLayoutParams(summaryParams);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        summaryView.setText(summary);

        textContainer.addView(titleView);
        textContainer.addView(summaryView);

        mainLayout.addView(imageView);
        mainLayout.addView(textContainer);

        return mainLayout;
    }

    private void showCustomPrivacyList(Activity activity, Class<?> contactClass, Class<?> groupClass) {
        SharedPreferences pprefs = WppCore.getPrivPrefs();
        var maps = pprefs.getAll();
        ArrayList<CustomPrivacyAdapter.Item> list = new ArrayList<>();
        for (var key : maps.keySet()) {
            if (key.endsWith("_privacy")) {
                var number = key.replace("_privacy", "");
                var userJid = new FMessageWpp.UserJid(number + (number.length() > 14 ? "@g.us" : "@s.whatsapp.net"));

                var contactName = WppCore.getContactName(userJid);

                if (TextUtils.isEmpty(contactName)) {
                    contactName = number;
                }
                CustomPrivacyAdapter.Item item = new CustomPrivacyAdapter.Item();
                item.name = contactName;
                item.number = number;
                item.key = key;
                item.activeOptionsCount = countActiveOptions(getJSON(number));
                list.add(item);
            }
        }

        if (list.isEmpty()) {
            Utils.showToast(activity.getString(ResId.string.no_contact_with_custom_privacy), Toast.LENGTH_SHORT);
            return;
        }

        AlertDialogWpp builder = new AlertDialogWpp(activity);
        builder.setTitle(ResId.string.custom_privacy);
        ListView listView = new ListView(activity);
        listView.setAdapter(new CustomPrivacyAdapter(activity, pprefs, list, contactClass, groupClass));
        builder.setView(listView);
        builder.show();
    }

    private void showPrivacyDialog(Activity activity, boolean isChat) {
        var userJid = getUserJid(activity, isChat);
        if (userJid.isNull()) return;
        final String phoneNumber = userJid.getPhoneNumber();
        final DialogBuilder dialogBuilder = new DialogBuilder(activity, phoneNumber, getJSON(phoneNumber));
        View viewBuild = dialogBuilder.build();
        AlertDialogWpp alertDialogWpp = new AlertDialogWpp(activity);
        alertDialogWpp.setTitle(ResId.string.custom_privacy);
        alertDialogWpp.setView(viewBuild);
        alertDialogWpp.setPositiveButton("OK", (dialogInterface, i9) -> savePreferences(phoneNumber, dialogBuilder.getCheckedStates()));
        alertDialogWpp.setNegativeButton(activity.getString(ResId.string.cancel), null);
        alertDialogWpp.show();
    }

    private FMessageWpp.UserJid getUserJid(Activity activity, boolean isChat) {
        if (isChat) {
            return new FMessageWpp.UserJid(ReflectionUtils.callMethod(chatUserJidMethod, activity));
        } else {
            return new FMessageWpp.UserJid(ReflectionUtils.callMethod(groupUserJidMethod, activity));
        }
    }

    private void savePreferences(String number, Map<String, Boolean> states) {
        try {
            JSONObject jsonObject = new JSONObject();
            for (PrivacyOption option : OPTIONS) {
                boolean defaultValue = option.getDefaultValue(prefs);
                Boolean currentState = states.get(option.key);
                if (currentState != null && currentState != defaultValue) {
                    jsonObject.put(option.key, currentState);
                }
            }
            WppCore.setPrivJSON(number + "_privacy", jsonObject);
        } catch (Exception e) {
            Utils.showToast(e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    private int countActiveOptions(JSONObject json) {
        int count = 0;
        for (PrivacyOption option : OPTIONS) {
            boolean defaultValue = option.getDefaultValue(prefs);
            if (json.optBoolean(option.key, defaultValue) != defaultValue) {
                count++;
            }
        }
        return count;
    }

    public static class PrivacyOption {
        public final String key;
        public final int titleResId;
        public final int descriptionResId;
        public final String globalPreferenceKey;
        public final boolean isStringPreference;

        public PrivacyOption(String key, int titleResId, int descriptionResId, String globalPreferenceKey, boolean isStringPreference) {
            this.key = key;
            this.titleResId = titleResId;
            this.descriptionResId = descriptionResId;
            this.globalPreferenceKey = globalPreferenceKey;
            this.isStringPreference = isStringPreference;
        }

        public boolean getDefaultValue(XSharedPreferences prefs) {
            if (isStringPreference) {
                return Objects.equals(prefs.getString(globalPreferenceKey, "0"), "1");
            }
            return prefs.getBoolean(globalPreferenceKey, false);
        }
    }

    public class DialogBuilder {
        private final Map<String, Boolean> checkedStates = new HashMap<>();
        private final Context context;
        private final JSONObject currentJson;
        private final String number;

        public DialogBuilder(Context context, String str, JSONObject jSONObject) {
            this.context = context;
            this.number = str;
            this.currentJson = jSONObject;
        }

        @SuppressLint({"UseSwitchCompatOrMaterialCode", "SetTextI18n"})
        private View createOptionCard(final PrivacyOption privacyOption) {
            LinearLayout linearLayout = new LinearLayout(this.context);
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-1, -2);
            layoutParams.setMargins(0, Utils.dipToPixels(4.0f), 0, Utils.dipToPixels(4.0f));
            linearLayout.setLayoutParams(layoutParams);
            linearLayout.setPadding(Utils.dipToPixels(16.0f), Utils.dipToPixels(12.0f), Utils.dipToPixels(16.0f), Utils.dipToPixels(12.0f));
            
            int bgColor = DesignUtils.isNightMode() ? 0x1AFFFFFF : 0x1A000000;
            linearLayout.setBackground(DesignUtils.createDrawable("rc_dotline_dialog", bgColor));

            LinearLayout rowLayout = new LinearLayout(this.context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            rowLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);

            LinearLayout textLayout = new LinearLayout(this.context);
            textLayout.setOrientation(LinearLayout.VERTICAL);
            textLayout.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

            TextView titleView = new TextView(this.context);
            titleView.setText(privacyOption.titleResId);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.0f);
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            titleView.setTextColor(DesignUtils.getPrimaryTextColor());
            textLayout.addView(titleView);

            TextView descView = new TextView(this.context);
            descView.setText(privacyOption.descriptionResId);
            descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.0f);
            descView.setTextColor(DesignUtils.isNightMode() ? 0xB3FFFFFF : 0xB3000000);
            LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
            descParams.setMargins(0, Utils.dipToPixels(2.0f), 0, 0);
            descView.setLayoutParams(descParams);
            textLayout.addView(descView);

            boolean defaultValue = privacyOption.getDefaultValue(prefs);
            String statusText = context.getString(ResId.string.custom_privacy_global_status, 
                    context.getString(defaultValue ? ResId.string.enabled : ResId.string.disabled));
            
            TextView globalStatusView = new TextView(this.context);
            globalStatusView.setText(statusText);
            globalStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.0f);
            globalStatusView.setTextColor(DesignUtils.getUnSeenColor());
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
            statusParams.setMargins(0, Utils.dipToPixels(4.0f), 0, 0);
            globalStatusView.setLayoutParams(statusParams);
            textLayout.addView(globalStatusView);

            rowLayout.addView(textLayout);

            final Switch switchView = new Switch(this.context);
            boolean currentVal = this.currentJson.optBoolean(privacyOption.key, defaultValue);
            switchView.setChecked(currentVal);
            this.checkedStates.put(privacyOption.key, currentVal);
            switchView.setOnCheckedChangeListener((compoundButton, isChecked) -> this.checkedStates.put(privacyOption.key, isChecked));
            
            rowLayout.addView(switchView);
            linearLayout.addView(rowLayout);

            linearLayout.setOnClickListener(view -> switchView.toggle());

            return linearLayout;
        }

        public View build() {
            ScrollView scrollView = new ScrollView(this.context);
            scrollView.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
            LinearLayout container = new LinearLayout(this.context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            container.setPadding(Utils.dipToPixels(16.0f), Utils.dipToPixels(8.0f), Utils.dipToPixels(16.0f), Utils.dipToPixels(8.0f));
            
            for (PrivacyOption option : OPTIONS) {
                container.addView(createOptionCard(option));
            }
            
            scrollView.addView(container);
            return scrollView;
        }

        public Map<String, Boolean> getCheckedStates() {
            return this.checkedStates;
        }
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Custom Privacy";
    }
}