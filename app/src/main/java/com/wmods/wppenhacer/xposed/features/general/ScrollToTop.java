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
        if (prefs.getBoolean("tap_to_scroll_top", false)) {
            hookTabPagerInstance();
            hookOnMenuItemSelected();
        }
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

        if (prefs.getBoolean("tap_to_scroll_top", false)) {
            XposedHelpers.findAndHookMethod(WppCore.getHomeActivityClass(classLoader), "onCreate", Bundle.class, hook);
        }

        XposedHelpers.findAndHookMethod("com.whatsapp.Conversation", classLoader, "onCreate", Bundle.class, hook);
    }

    private void setupToolbar(Activity activity) {
        var toolbar = (ViewGroup) activity.findViewById(Utils.getID("toolbar", "id"));
        if (toolbar == null) return;

        View clickableView = findClickableView(toolbar);
        if (clickableView == null) {
            clickableView = toolbar;
        }

        View.OnClickListener originalListener = getOnClickListener(clickableView);
        if (originalListener instanceof SmartMultiClickListener) return; 

        clickableView.setOnClickListener(new SmartMultiClickListener(originalListener, 300) {
            @Override
            public void onDoubleClick(View v) {
                if (prefs.getBoolean("tap_to_scroll_top", false)) {
                    scrollToTop(activity);
                } else if (originalListener != null) {
                    originalListener.onClick(v);
                }
            }

            @Override
            public void onTripleClick(View v) {
                if (prefs.getBoolean("jump_to_first_chat", true) && activity.getClass().getName().endsWith("Conversation")) {
                    jumpToFirst(activity);
                }
            }
        });
    }

    private void jumpToFirst(Activity activity) {
        try {
            var delegateField = Unobfuscator.loadConversationDelegateField(classLoader);
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(activity);
            if (delegate == null) return;

            Class<?> handlerClass = Unobfuscator.loadConversationSearchHandlerClass(classLoader);
            Object handler = null;
            
            // Search for SearchHandler instance in delegate fields or superclasses
            Class<?> current = delegate.getClass();
            while (current != null && current != Object.class) {
                for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(delegate);
                    if (val == null) continue;
                    
                    if (handlerClass.isInstance(val)) {
                        handler = val;
                        break;
                    }
                    
                    // If it's a provider/lazy
                    if (val.getClass().getName().contains("AnonymousClass00p") || val.getClass().getName().contains("Provider") || val.getClass().getName().contains("Lazy")) {
                        try {
                            Object provided = XposedHelpers.callMethod(val, "get");
                            if (provided != null && handlerClass.isInstance(provided)) {
                                handler = provided;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                if (handler != null) break;
                current = current.getSuperclass();
            }

            if (handler == null) {
                // Last ditch: iterate all fields and look for the one containing DateSetListener
                handler = delegate;
            }

            // Find OnDateSetListener in handler or its superclasses
            android.app.DatePickerDialog.OnDateSetListener listener = findOnDateSetListener(handler);

            if (listener == null && handler != delegate) {
                // Try finding it in delegate directly
                listener = findOnDateSetListener(delegate);
            }

            if (listener == null) {
                // Deep search in all non-primitive fields of delegate
                listener = deepSearchListener(delegate, 0);
            }

            if (listener != null) {
                // Jump to the past
                listener.onDateSet(null, 1900, 0, 1);
                Utils.showToast("Jumping to first message...", 0);
            } else {
                Utils.showToast("Date listener not found!", 0);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private android.app.DatePickerDialog.OnDateSetListener deepSearchListener(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        
        // Check current object fields
        android.app.DatePickerDialog.OnDateSetListener listener = findOnDateSetListener(obj);
        if (listener != null) return listener;
        
        // Recurse into fields
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            if (current.getName().startsWith("android.") || current.getName().startsWith("java.")) {
                current = current.getSuperclass();
                continue;
            }
            
            for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val == null || val == obj) continue;
                    
                    // Specific check for providers
                    if (val.getClass().getName().contains("AnonymousClass00p") || val.getClass().getName().contains("Provider") || val.getClass().getName().contains("Lazy")) {
                        try {
                            Object provided = XposedHelpers.callMethod(val, "get");
                            if (provided != null) {
                                if (provided instanceof android.app.DatePickerDialog.OnDateSetListener) {
                                    return (android.app.DatePickerDialog.OnDateSetListener) provided;
                                }
                                listener = deepSearchListener(provided, depth + 1);
                                if (listener != null) return listener;
                            }
                        } catch (Throwable ignored) {}
                    }
                    
                    listener = deepSearchListener(val, depth + 1);
                    if (listener != null) return listener;
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private android.app.DatePickerDialog.OnDateSetListener findOnDateSetListener(Object obj) {
        if (obj == null) return null;
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            for (java.lang.reflect.Field f : current.getDeclaredFields()) {
                if (android.app.DatePickerDialog.OnDateSetListener.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try {
                        return (android.app.DatePickerDialog.OnDateSetListener) f.get(obj);
                    } catch (Throwable ignored) {}
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private View findClickableView(ViewGroup root) {
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
        return null; 
    }

    private View.OnClickListener getOnClickListener(View view) {
        try {
            var listenerInfo = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (listenerInfo != null) {
                return (View.OnClickListener) XposedHelpers.getObjectField(listenerInfo, "mOnClickListener");
            }
        } catch (Throwable ignored) {}
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
        return "Scroll To Top / Jump To First";
    }
}
