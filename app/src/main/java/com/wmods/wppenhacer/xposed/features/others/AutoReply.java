package com.wmods.wppenhacer.xposed.features.others;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.WppCore;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.db.AutoReplyDatabase;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Auto Reply Feature
 * 
 * Listens for incoming messages and automatically sends replies based on configured rules.
 * 
 * Features:
 * - Pattern matching (all, contains, exact, regex)
 * - Target filtering (all, contacts only, groups only, specific JIDs)
 * - Reply delay
 * - Active time windows
 * - Cooldown to prevent spam (one reply per JID per rule per 60 seconds)
 */
public class AutoReply extends Feature {

    private static final String TAG = "AutoReply";
    private static final long COOLDOWN_MS = 60_000; // 60 second cooldown per JID per rule
    
    // Track recent replies to prevent spam: key = "jid:ruleId", value = timestamp
    private final ConcurrentHashMap<String, Long> recentReplies = new ConcurrentHashMap<>();
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AutoReplyDatabase database;

    public AutoReply(@NonNull ClassLoader classLoader, @NonNull XSharedPreferences preferences) {
        super(classLoader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("auto_reply_enabled", false)) {
            return;
        }

        database = AutoReplyDatabase.getInstance();
        
        hookIncomingMessages();
    }

    private void hookIncomingMessages() throws Exception {
        // Hook the method that processes incoming messages
        // This is the chatInfo/incrementUnseenImportantMessageCount method
        // which is called when new messages arrive
        Method incomingMessageMethod = Unobfuscator.loadIncomingMessageMethod(classLoader);
        
        if (incomingMessageMethod == null) {
            log("Could not find incoming message method for auto-reply");
            return;
        }

        logDebug("Hooking incoming message method for auto-reply: " + Unobfuscator.getMethodDescriptor(incomingMessageMethod));

        XposedBridge.hookMethod(incomingMessageMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args == null || param.args.length == 0) return;

                try {
                    // Find the FMessage object in the parameters
                    Object fMessageObj = findFMessageInArgs(param.args);
                    if (fMessageObj != null) {
                        processIncomingMessage(fMessageObj);
                    }
                } catch (Exception e) {
                    logDebug("Error processing message for auto-reply: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Find FMessage object in method arguments
     */
    private Object findFMessageInArgs(Object[] args) {
        if (args == null) return null;
        
        for (Object arg : args) {
            if (arg == null) continue;
            
            // Check if the argument is directly an FMessage
            if (FMessageWpp.TYPE != null && FMessageWpp.TYPE.isInstance(arg)) {
                return arg;
            }
            
            // Check if it's a collection containing FMessages
            if (arg instanceof java.util.Collection) {
                java.util.Collection<?> collection = (java.util.Collection<?>) arg;
                for (Object item : collection) {
                    if (item != null && FMessageWpp.TYPE != null && FMessageWpp.TYPE.isInstance(item)) {
                        return item;
                    }
                }
            }
            
            // Try to find FMessage field within the object
            try {
                if (arg.getClass().getName().startsWith("X.") || arg.getClass().getName().startsWith("com.whatsapp")) {
                    java.lang.reflect.Field[] fields = arg.getClass().getDeclaredFields();
                    for (java.lang.reflect.Field field : fields) {
                        if (FMessageWpp.TYPE != null && FMessageWpp.TYPE.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            Object fieldValue = field.get(arg);
                            if (fieldValue != null) {
                                return fieldValue;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void processIncomingMessage(Object messageObj) {
        FMessageWpp fMessage = new FMessageWpp(messageObj);
        if (!fMessage.isValid()) return;

        FMessageWpp.Key key = fMessage.getKey();
        if (key == null || key.remoteJid == null) return;

        // Only process incoming messages (not from me)
        if (key.isFromMe) return;

        // Skip status messages
        if (key.remoteJid.isStatus()) return;
        
        // Skip newsletter/broadcast
        if (key.remoteJid.isNewsletter() || key.remoteJid.isBroadcast()) return;

        String messageText = fMessage.getMessageStr();
        if (TextUtils.isEmpty(messageText)) return;

        String senderJid = key.remoteJid.getPhoneRawString();
        if (TextUtils.isEmpty(senderJid)) return;

        // Get all enabled rules
        List<AutoReplyDatabase.AutoReplyRule> rules = database.getEnabledRules();
        if (rules == null || rules.isEmpty()) return;

        boolean isGroup = key.remoteJid.isGroup();

        for (AutoReplyDatabase.AutoReplyRule rule : rules) {
            if (shouldApplyRule(rule, senderJid, messageText, isGroup)) {
                sendAutoReply(rule, fMessage, senderJid);
                break; // Only apply first matching rule
            }
        }
    }

    private boolean shouldApplyRule(AutoReplyDatabase.AutoReplyRule rule, String senderJid, 
                                     String messageText, boolean isGroup) {
        // 1. Check target type
        if (!isTargetAllowed(rule, senderJid, isGroup)) {
            return false;
        }

        // 2. Check time window
        if (!isWithinTimeWindow(rule)) {
            return false;
        }

        // 3. Check cooldown
        String cooldownKey = senderJid + ":" + rule.id;
        Long lastReply = recentReplies.get(cooldownKey);
        if (lastReply != null && System.currentTimeMillis() - lastReply < COOLDOWN_MS) {
            logDebug("Skipping auto-reply due to cooldown for: " + cooldownKey);
            return false;
        }

        // 4. Check pattern match
        return matchesPattern(rule, messageText);
    }

    private boolean isTargetAllowed(AutoReplyDatabase.AutoReplyRule rule, String senderJid, boolean isGroup) {
        switch (rule.targetType) {
            case ALL:
                return true;
            case CONTACTS:
                return !isGroup;
            case GROUPS:
                return isGroup;
            case SPECIFIC:
                if (TextUtils.isEmpty(rule.specificJids)) return false;
                Set<String> allowedJids = parseJidSet(rule.specificJids);
                // Check if sender JID or stripped phone number is in the allowed set
                String phoneNumber = WppCore.stripJID(senderJid);
                return allowedJids.contains(senderJid) || 
                       allowedJids.contains(phoneNumber) ||
                       allowedJids.stream().anyMatch(j -> senderJid.contains(j) || j.contains(phoneNumber));
            default:
                return true;
        }
    }

    private Set<String> parseJidSet(String jidsString) {
        Set<String> jids = new HashSet<>();
        if (TextUtils.isEmpty(jidsString)) return jids;
        
        String[] parts = jidsString.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                jids.add(trimmed);
            }
        }
        return jids;
    }

    private boolean isWithinTimeWindow(AutoReplyDatabase.AutoReplyRule rule) {
        if (TextUtils.isEmpty(rule.startTime) || TextUtils.isEmpty(rule.endTime)) {
            return true; // No time restriction
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            Date startTime = sdf.parse(rule.startTime);
            Date endTime = sdf.parse(rule.endTime);
            
            if (startTime == null || endTime == null) return true;

            Calendar now = Calendar.getInstance();
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();

            start.setTime(startTime);
            start.set(Calendar.YEAR, now.get(Calendar.YEAR));
            start.set(Calendar.MONTH, now.get(Calendar.MONTH));
            start.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH));

            end.setTime(endTime);
            end.set(Calendar.YEAR, now.get(Calendar.YEAR));
            end.set(Calendar.MONTH, now.get(Calendar.MONTH));
            end.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH));

            // Handle overnight windows (e.g., 22:00 - 06:00)
            if (end.before(start)) {
                // If current time is after start OR before end, it's within window
                return now.after(start) || now.before(end);
            } else {
                // Normal window
                return now.after(start) && now.before(end);
            }
        } catch (Exception e) {
            logDebug("Error parsing time window: " + e.getMessage());
            return true;
        }
    }

    private boolean matchesPattern(AutoReplyDatabase.AutoReplyRule rule, String messageText) {
        String pattern = rule.pattern;
        
        switch (rule.matchType) {
            case ALL:
                // Match all messages
                return true;
            case CONTAINS:
                return messageText.toLowerCase().contains(pattern.toLowerCase());
            case EXACT:
                return messageText.equalsIgnoreCase(pattern);
            case REGEX:
                try {
                    Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                    return regex.matcher(messageText).find();
                } catch (PatternSyntaxException e) {
                    logDebug("Invalid regex pattern: " + pattern);
                    return false;
                }
            default:
                return false;
        }
    }

    private void sendAutoReply(AutoReplyDatabase.AutoReplyRule rule, FMessageWpp fMessage, String senderJid) {
        String replyMessage = processReplyTemplate(rule.replyMessage, fMessage, senderJid);
        
        int delayMs = rule.delaySeconds * 1000;
        
        // Update cooldown
        String cooldownKey = senderJid + ":" + rule.id;
        recentReplies.put(cooldownKey, System.currentTimeMillis());
        
        Runnable sendTask = () -> {
            try {
                // Use the existing WppCore.sendMessage method
                WppCore.sendMessage(WppCore.stripJID(senderJid), replyMessage);
                logDebug("Auto-reply sent to " + senderJid + ": " + replyMessage);
            } catch (Exception e) {
                log("Error sending auto-reply: " + e.getMessage());
            }
        };

        if (delayMs > 0) {
            handler.postDelayed(sendTask, delayMs);
        } else {
            // Small delay to avoid immediate response
            handler.postDelayed(sendTask, 500);
        }
    }

    /**
     * Process reply template with variables like {name}, {time}, etc.
     */
    private String processReplyTemplate(String template, FMessageWpp fMessage, String senderJid) {
        if (TextUtils.isEmpty(template)) return template;

        String result = template;
        
        // {name} - sender's contact name
        try {
            FMessageWpp.UserJid userJid = fMessage.getKey().remoteJid;
            String contactName = WppCore.getContactName(userJid);
            if (TextUtils.isEmpty(contactName)) {
                contactName = userJid.getPhoneNumber();
            }
            result = result.replace("{name}", contactName != null ? contactName : "");
        } catch (Exception e) {
            result = result.replace("{name}", "");
        }

        // {time} - current time
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        result = result.replace("{time}", timeFormat.format(new Date()));

        // {date} - current date
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
        result = result.replace("{date}", dateFormat.format(new Date()));

        // {message} - the received message
        String receivedMessage = fMessage.getMessageStr();
        result = result.replace("{message}", receivedMessage != null ? receivedMessage : "");

        return result;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Auto Reply";
    }
}
