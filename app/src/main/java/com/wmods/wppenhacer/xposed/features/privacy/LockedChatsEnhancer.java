package com.wmods.wppenhacer.xposed.features.privacy;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class LockedChatsEnhancer extends Feature {
    /* access modifiers changed from: private */
    public Object chatCache;

    public LockedChatsEnhancer(ClassLoader classLoader, XSharedPreferences xSharedPreferences) {
        super(classLoader, xSharedPreferences);
    }

    public void doHook() {
        if (this.prefs.getBoolean("lockedchats_enhancer", false)) {
            try {
                // Hook Notification processing to hide locked chats content/notification
                Method loadNotificationMethod = Unobfuscator.loadNotificationMethod(this.classLoader);
                final Method loadLockedChatsMethod = Unobfuscator.loadLockedChatsMethod(this.classLoader);
                
                if (loadNotificationMethod != null && loadLockedChatsMethod != null) {
                    XposedBridge.hookMethod(loadNotificationMethod, new XC_MethodHook() {
                        private XC_MethodHook.Unhook unhook;

                        public void afterHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                            if (this.unhook != null) {
                                this.unhook.unhook();
                            }
                        }

                        public void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                            // Temporarily hook loadLockedChatsMethod to return empty list
                            // This tricks the notification generator into thinking there are no locked chats
                            this.unhook = XposedBridge.hookMethod(loadLockedChatsMethod, new XC_MethodHook() {
                                public void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                                    param.setResult(new ArrayList());
                                }
                            });
                        }
                    });
                }
            } catch (Throwable t) {
                XposedBridge.log("LockedChatsEnhancer: Failed notification hook - " + t.getMessage());
            }

            try {
                // Hook ChatCache to filter locked contacts from the contact list
                Class<?> loadChatCacheClass = Unobfuscator.loadChatCacheClass(this.classLoader);
                
                // Find the field that holds the locked chats (HashSet<String> jid)
                final Field[] findAllFieldsUsingFilter = ReflectionUtils.findAllFieldsUsingFilter(loadChatCacheClass, field -> field.getType() == HashSet.class);
                
                if (findAllFieldsUsingFilter != null && findAllFieldsUsingFilter.length > 1) {
                    XposedBridge.hookAllConstructors(loadChatCacheClass, new XC_MethodHook() {
                        public void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                            LockedChatsEnhancer.this.chatCache = methodHookParam.thisObject;
                        }
                    });

                    Method loadedContactsMethod = Unobfuscator.loadLoadedContactsMethod(this.classLoader);
                    if (loadedContactsMethod != null) {
                        XposedBridge.hookMethod(loadedContactsMethod, new XC_MethodHook() {
                            public void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) {
                                if (LockedChatsEnhancer.this.chatCache == null) return;
                                
                                try {
                                    List list = (List) XposedHelpers.getObjectField(methodHookParam.args[0], "allContacts"); // Assuming field name, might need adjustment
                                    if (list == null) return;
                                    
                                    // Get the set of locked JIDs
                                    HashSet lockedJids = (HashSet) findAllFieldsUsingFilter[1].get(LockedChatsEnhancer.this.chatCache);
                                    if (lockedJids == null) return;

                                    // Filter the list
                                    // We need to iterate and remove. WaContactWpp wrapper helps us get the JID string
                                    list.removeIf(obj -> {
                                        if (!WaContactWpp.TYPE.isInstance(obj)) {
                                            return false;
                                        }
                                        return lockedJids.contains(new WaContactWpp(obj).getUserJid().getPhoneNumber()); // Verify if getPhoneNumber returns the raw JID string used in the set
                                    });
                                } catch (Throwable e) {
                                     // Silent fail to avoid crashing the contact list
                                     // XposedBridge.log(e);
                                }
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                 XposedBridge.log("LockedChatsEnhancer: Failed chat cache hook - " + t.getMessage());
            }
        }
    }

    public String getPluginName() {
        return "LockedChatsEnhancer";
    }
}
