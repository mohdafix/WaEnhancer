package com.wmods.wppenhacer.xposed.core;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.robv.android.xposed.XposedBridge;

public class ErrorDialogManager {
    private static final String LAST_TESTED_STABLE_WPP_VERSION = "2.26.3.78";
    private static final String WPP_APKMIRROR_URL = "https://www.apkmirror.com/apk/whatsapp-inc/whatsapp/";
    private static String currentVersion;
    private static final ArrayList<ErrorItem> errorList = new ArrayList<>();
    private static List<String> supportedVersions;

    public static void addError(ErrorItem errorItem) {
        errorList.add(errorItem);
    }

    public static void setCurrentVersion(String str) {
        currentVersion = str;
    }

    public static void setSupportedVersions(List<String> list) {
        supportedVersions = list;
    }

    public static void clear() {
        errorList.clear();
    }

    public static ErrorItem createError(String pluginName, String wppVersion, Throwable th) {
        ErrorItem errorItem = new ErrorItem();
        errorItem.setPluginName(pluginName);
        errorItem.setWhatsAppVersion(wppVersion);
        errorItem.setModuleVersion(com.wmods.wppenhacer.BuildConfig.VERSION_NAME);
        errorItem.setMessage(th.getMessage());
        errorItem.setError(java.util.Arrays.toString(java.util.Arrays.stream(th.getStackTrace())
                .filter(s -> !s.getClassName().startsWith("android") && !s.getClassName().startsWith("com.android"))
                .map(StackTraceElement::toString)
                .toArray()));
        return errorItem;
    }

    public static void showErrorsIfAny(Activity activity) {
        if (errorList.isEmpty()) return;

        try {
            String errorsText = errorList.stream()
                    .map(item -> "<b>" + escapeHtml(item.getPluginName()) + "</b> - " + escapeHtml(item.getMessage()))
                    .collect(Collectors.joining("<br>"));

            String supportedStr = String.join("<br>", supportedVersions);
            
            String html = "<b>" + escapeHtml(activity.getString(ResId.string.version_error)) + "</b><br><br>"
                    + errorsText + "<br><br>"
                    + "<b>WAEnhancer Version:</b> " + escapeHtml(com.wmods.wppenhacer.BuildConfig.VERSION_NAME) + "<br>"
                    + "<b>WhatsApp Version:</b> " + escapeHtml(currentVersion) + "<br>"
                    + "<b>Latest Tested Stable:</b> " + LAST_TESTED_STABLE_WPP_VERSION + "<br>"
                    + "<b>Download WhatsApp: <a href=\"" + WPP_APKMIRROR_URL + "\">APKMirror</a></b><br><br>"
                    + "<b>Supported Versions:</b><br>" + supportedStr;

            TextView textView = new TextView(activity);
            textView.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
            textView.setMovementMethod(LinkMovementMethod.getInstance());
            textView.setLinksClickable(true);
            textView.setTextSize(14.0f);
            
            int padding = Utils.dipToPixels(20);
            int paddingVertical = Utils.dipToPixels(16);
            textView.setPadding(padding, paddingVertical, padding, paddingVertical);

            ScrollView scrollView = new ScrollView(activity);
            scrollView.addView(textView);

            new AlertDialogWpp(activity)
                    .setTitle(activity.getString(ResId.string.error_detected))
                    .setView(scrollView)
                    .setPositiveButton(activity.getString(ResId.string.copy_to_clipboard), (dialog, which) -> {
                        String copyText = errorList.stream()
                                .map(ErrorItem::toString)
                                .collect(Collectors.joining("\n\n"));
                        Utils.setToClipboard(copyText);
                        Toast.makeText(activity, activity.getString(ResId.string.copied_to_clipboard), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    })
                    .show();
        } catch (Throwable th) {
            XposedBridge.log(th);
        }
    }

    private static String escapeHtml(String str) {
        if (str == null) return "";
        return TextUtils.htmlEncode(str);
    }
}
