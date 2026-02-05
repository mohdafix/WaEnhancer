package com.wmods.wppenhacer.xposed.features.general;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.listeners.OnMultiClickListener;
import com.wmods.wppenhacer.listeners.SmartMultiClickListener;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ScrollToTop extends Feature {

    private Object mTabPagerInstance;

    public ScrollToTop(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        if (!prefs.getBoolean("tap_to_scroll_top", false)) return;

        hookTabPagerInstance();
        hookOnMenuItemSelected();
        hookToolbarDoubleTap();
    }

    private void hookTabPagerInstance() {
        XposedHelpers.findAndHookMethod(WppCore.getHomeActivityClass(classLoader), "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Class<?> TabsPagerClass = WppCore.getTabsPagerClass(classLoader);
                var tabsField = ReflectionUtils.getFieldByType(param.thisObject.getClass(), TabsPagerClass);
                mTabPagerInstance = tabsField.get(param.thisObject);
            }
        });
    }

    private void hookOnMenuItemSelected() throws Exception {
        var onMenuItemSelected = Unobfuscator.loadOnMenuItemSelected(classLoader);
        XposedBridge.hookMethod(onMenuItemSelected, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject != mTabPagerInstance) return;

                var newIndex = (int) param.args[0];
                var currentIndex = (int) XposedHelpers.callMethod(param.thisObject, "getCurrentItem");

                if (newIndex == currentIndex) {
                    var activity = WppCore.getCurrentActivity();
                    if (activity != null) {
                        scrollToTop(activity);
                    }
                }
            }
        });
    }

    private void hookToolbarDoubleTap() throws Exception {
        var hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                var activity = (Activity) param.thisObject;
                var root = activity.getWindow().getDecorView();
                // Post to ensure view hierarchy is built
                root.post(() -> {
                     setupToolbar(activity);
                });
            }
        };

        XposedHelpers.findAndHookMethod(WppCore.getHomeActivityClass(classLoader), "onCreate", Bundle.class, hook);

        var conversationClass = Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "Conversation"
        );
        XposedHelpers.findAndHookMethod(conversationClass, "onCreate", Bundle.class, hook);
    }

    private void setupToolbar(Activity activity) {
        var toolbar = (ViewGroup) activity.findViewById(Utils.getID("toolbar", "id"));
        if (toolbar == null) return;

        View clickableView = findClickableView(toolbar);
        if (clickableView == null) {
            // Fallback to toolbar if no child is clickable (unlikely for Convo, possible for Home)
            clickableView = toolbar;
        }

        View.OnClickListener originalListener = getOnClickListener(clickableView);
        if (originalListener instanceof SmartMultiClickListener) return; // Already hooked

        clickableView.setOnClickListener(new SmartMultiClickListener(originalListener, 300) {
            @Override
            public void onDoubleClick(View v) {
                scrollToTop(activity);
            }
        });
    }

    private View findClickableView(ViewGroup root) {
        // BFS or DFS to find the first clickable child. 
        // In Toolbar, usually the title container is what we want.
        // It's often a LinearLayout or RelativeLayout inside the Toolbar.
        
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.isClickable() && child.hasOnClickListeners()) {
                return child;
            }
            if (child instanceof ViewGroup group) {
                View found = findClickableView(group);
                if (found != null) return found;
            }
        }
        if (root.isClickable() && root.hasOnClickListeners()) return root;
        return null; // Nothing found
    }

    private View.OnClickListener getOnClickListener(View view) {
        try {
            // View.mListenerInfo
            var listenerInfo = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (listenerInfo != null) {
                // ListenerInfo.mOnClickListener
                return (View.OnClickListener) XposedHelpers.getObjectField(listenerInfo, "mOnClickListener");
            }
        } catch (Throwable e) {
            // ignore
        }
        return null;
    }

    private void scrollToTop(Activity activity) {
        View root = activity.getWindow().getDecorView();
        View scrollable = findVisibleScrollable(root);
        if (scrollable == null) return;
        
        scrollToPosition(scrollable, 0);
    }

    private void scrollToPosition(View scrollable, int position) {
        if (scrollable instanceof android.widget.AbsListView absListView) {
            try {
                // For AbsListView, setSelection usually works
                absListView.setSelection(position);
            } catch (Throwable ignored) {}
        } else {
            try {
                XposedHelpers.callMethod(scrollable, "scrollToPosition", position);
            } catch (Throwable ignored) {
                try {
                    XposedHelpers.callMethod(scrollable, "setSelection", position);
                } catch (Throwable ignored2) {}
            }
        }
    }

    private View findVisibleScrollable(View view) {
        if (view == null) return null;
        if (view.isShown() && (view instanceof android.widget.AbsListView || view.getClass().getName().contains("RecyclerView"))) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = findVisibleScrollable(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Scroll To Top";
    }
}
