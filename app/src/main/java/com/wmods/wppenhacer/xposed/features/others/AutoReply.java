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
 * Listens for incoming messages and automatically sends replies based on
 * configured rules.
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
        // Try multiple hook points to ensure we catch incoming messages
        boolean hooked = false;

        // Log that we're starting to hook
        log("AutoReply: Attempting to hook incoming messages...");

        // PRIMARY: Hook the MessageHandler which processes all incoming messages
        try {
            var messageHandlerMethod = Unobfuscator.loadDndModeMethod(classLoader);
            if (messageHandlerMethod != null) {
                log("AutoReply: Found MessageHandler method: "
                        + Unobfuscator.getMethodDescriptor(messageHandlerMethod));

                // Log parameter types
                Class<?>[] paramTypes = messageHandlerMethod.getParameterTypes();
                for (int i = 0; i < paramTypes.length; i++) {
                    log("AutoReply: MessageHandler param[" + i + "]: " + paramTypes[i].getName());
                }

                XposedBridge.hookMethod(messageHandlerMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        log("AutoReply: MessageHandler/start called with "
                                + (param.args != null ? param.args.length : 0) + " args");
                        if (param.args == null || param.args.length == 0)
                            return;

                        // Log all arguments
                        for (int i = 0; i < param.args.length; i++) {
                            Object arg = param.args[i];
                            if (arg != null) {
                                log("AutoReply: MessageHandler arg[" + i + "] = " + arg.getClass().getName());
                                // If this looks like FMessage type, log it
                                if (FMessageWpp.TYPE != null && FMessageWpp.TYPE.isInstance(arg)) {
                                    log("AutoReply: MessageHandler arg[" + i + "] IS an FMessage!");
                                }
                            }
                        }

                        try {
                            Object fMessageObj = findFMessageInArgs(param.args);
                            if (fMessageObj != null) {
                                log("AutoReply: Found FMessage in MessageHandler");
                                processIncomingMessage(fMessageObj);
                            } else {
                                logDebug("AutoReply: No FMessage found in MessageHandler args");
                            }
                        } catch (Exception e) {
                            log("AutoReply: Error in MessageHandler hook: " + e.getMessage());
                        }
                    }
                });
                log("AutoReply: Hooked MessageHandler method successfully");
                hooked = true;
            }
        } catch (Exception e) {
            log("AutoReply: Failed to hook MessageHandler: " + e.getMessage());
        }

        // Second try: Hook the receipt method (used by Tasker)
        try {
            var receiptMethod = Unobfuscator.loadReceiptMethod(classLoader);
            if (receiptMethod != null) {
                log("AutoReply: Found loadReceiptMethod: " + Unobfuscator.getMethodDescriptor(receiptMethod));

                XposedBridge.hookMethod(receiptMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // Skip if this is a "sender" receipt (outgoing)
                        if (param.args != null && param.args.length > 4 && "sender".equals(param.args[4])) {
                            return;
                        }

                        logDebug("AutoReply: loadReceiptMethod called");

                        if (param.args == null || param.args.length < 4)
                            return;
                        if (param.args[1] == null || param.args[3] == null)
                            return;

                        try {
                            // Get FMessage from the Key object (arg[3])
                            var fMessage = new FMessageWpp.Key(param.args[3]).getFMessage();
                            if (fMessage != null && fMessage.isValid()) {
                                logDebug("AutoReply: Found FMessage via Key in receipt method");
                                processIncomingMessage(fMessage.getObject());
                            }
                        } catch (Exception e) {
                            logDebug("AutoReply: Error extracting FMessage from receipt: " + e.getMessage());
                            // Also try to find FMessage in other args
                            Object fMessageObj = findFMessageInArgs(param.args);
                            if (fMessageObj != null) {
                                logDebug("AutoReply: Found FMessage in receipt args directly");
                                processIncomingMessage(fMessageObj);
                            }
                        }
                    }
                });
                log("AutoReply: Hooked loadReceiptMethod successfully");
                hooked = true;
            }
        } catch (Exception e) {
            log("AutoReply: Failed to hook loadReceiptMethod: " + e.getMessage());
        }

        // Third try: Original incoming message method (fallback)
        try {
            Method incomingMessageMethod = Unobfuscator.loadIncomingMessageMethod(classLoader);
            if (incomingMessageMethod != null) {
                log("AutoReply: Found incoming message method: "
                        + Unobfuscator.getMethodDescriptor(incomingMessageMethod));

                XposedBridge.hookMethod(incomingMessageMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        log("AutoReply: incomingMessageMethod BEFORE called with " +
                                (param.args != null ? param.args.length : 0) + " args");
                        if (param.args == null || param.args.length == 0)
                            return;

                        // Log all arguments with their types
                        for (int i = 0; i < param.args.length; i++) {
                            Object arg = param.args[i];
                            if (arg != null) {
                                log("AutoReply: incomingMessage arg[" + i + "] = " + arg.getClass().getName());
                                if (FMessageWpp.TYPE != null && FMessageWpp.TYPE.isInstance(arg)) {
                                    log("AutoReply: arg[" + i + "] IS an FMessage!");
                                }
                            }
                        }

                        try {
                            Object fMessageObj = findFMessageInArgs(param.args);
                            if (fMessageObj != null) {
                                log("AutoReply: Found FMessage in incomingMessage");
                                processIncomingMessage(fMessageObj);
                            } else {
                                log("AutoReply: No FMessage found in incomingMessage args");
                            }
                        } catch (Exception e) {
                            log("AutoReply: Error processing message in incomingMessage: " + e.getMessage());
                        }
                    }
                });
                log("AutoReply: Hooked incomingMessageMethod successfully");
                hooked = true;
            }
        } catch (Exception e) {
            log("AutoReply: Failed to hook incomingMessageMethod: " + e.getMessage());
        }

        // FOURTH: Hook the database insert method (most reliable for all incoming
        // messages)
        try {
            var messageInsertMethod = Unobfuscator.loadMessageInsertMethod(classLoader);
            if (messageInsertMethod != null) {
                log("AutoReply: Found MessageInsert method: " + Unobfuscator.getMethodDescriptor(messageInsertMethod));

                XposedBridge.hookMethod(messageInsertMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        log("AutoReply: MessageInsert method called");
                        if (param.args == null || param.args.length == 0)
                            return;

                        try {
                            // First arg should be FMessage
                            Object fMessageObj = param.args[0];
                            if (fMessageObj != null && FMessageWpp.TYPE != null
                                    && FMessageWpp.TYPE.isInstance(fMessageObj)) {
                                log("AutoReply: Found FMessage in MessageInsert");
                                processIncomingMessage(fMessageObj);
                            }
                        } catch (Exception e) {
                            log("AutoReply: Error in MessageInsert hook: " + e.getMessage());
                        }
                    }
                });
                log("AutoReply: Hooked MessageInsert method successfully");
                hooked = true;
            }
        } catch (Exception e) {
            log("AutoReply: Failed to hook MessageInsert: " + e.getMessage());
        }

        if (!hooked) {
            log("AutoReply: ERROR - Could not hook any incoming message method!");
        } else {
            log("AutoReply: Hook installation complete");
        }
    }

    /**
     * Find FMessage object in method arguments
     */
    private Object findFMessageInArgs(Object[] args) {
        if (args == null)
            return null;

        for (Object arg : args) {
            if (arg == null)
                continue;

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
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void processIncomingMessage(Object messageObj) {
        logDebug("AutoReply: processIncomingMessage called");

        FMessageWpp fMessage = new FMessageWpp(messageObj);
        if (!fMessage.isValid()) {
            logDebug("AutoReply: FMessage is not valid");
            return;
        }

        FMessageWpp.Key key = fMessage.getKey();
        if (key == null || key.remoteJid == null) {
            logDebug("AutoReply: Key or remoteJid is null");
            return;
        }

        // Only process incoming messages (not from me)
        if (key.isFromMe) {
            logDebug("AutoReply: Skipping message from me");
            return;
        }

        // Skip status messages
        if (key.remoteJid.isStatus()) {
            logDebug("AutoReply: Skipping status message");
            return;
        }

        // Skip newsletter/broadcast
        if (key.remoteJid.isNewsletter() || key.remoteJid.isBroadcast()) {
            logDebug("AutoReply: Skipping newsletter/broadcast");
            return;
        }

        String messageText = fMessage.getMessageStr();
        if (TextUtils.isEmpty(messageText)) {
            logDebug("AutoReply: Message text is empty");
            return;
        }

        String senderJid = key.remoteJid.getPhoneRawString();
        if (TextUtils.isEmpty(senderJid)) {
            logDebug("AutoReply: Sender JID is empty");
            return;
        }

        log("AutoReply: Processing message from " + senderJid + ": "
                + messageText.substring(0, Math.min(50, messageText.length())));

        // Get all enabled rules from SharedPreferences (cross-app access)
        List<AutoReplyDatabase.AutoReplyRule> rules = AutoReplyDatabase.getEnabledRulesFromPrefs();
        if (rules == null || rules.isEmpty()) {
            log("AutoReply: No enabled rules found in prefs");
            return;
        }

        log("AutoReply: Found " + rules.size() + " enabled rules from prefs");

        boolean isGroup = key.remoteJid.isGroup();

        for (AutoReplyDatabase.AutoReplyRule rule : rules) {
            logDebug("AutoReply: Checking rule ID " + rule.id + " pattern: " + rule.pattern);
            if (shouldApplyRule(rule, senderJid, messageText, isGroup)) {
                log("AutoReply: Rule matched! Sending auto-reply");
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
                if (TextUtils.isEmpty(rule.specificJids))
                    return false;
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
        if (TextUtils.isEmpty(jidsString))
            return jids;

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

            if (startTime == null || endTime == null)
                return true;

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
        if (TextUtils.isEmpty(template))
            return template;

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
