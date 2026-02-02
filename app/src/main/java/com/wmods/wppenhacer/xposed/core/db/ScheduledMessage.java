package com.wmods.wppenhacer.xposed.core.db;

import android.content.Context;
import cz.vutbr.web.csskit.OutputUtil;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;

public class ScheduledMessage {
    public static final int DAY_FRIDAY = 32;
    public static final int DAY_MONDAY = 2;
    public static final int DAY_SATURDAY = 64;
    public static final int DAY_SUNDAY = 1;
    public static final int DAY_THURSDAY = 16;
    public static final int DAY_TUESDAY = 4;
    public static final int DAY_WEDNESDAY = 8;
    public static final int REPEAT_CUSTOM_DAYS = 4;
    public static final int REPEAT_DAILY = 1;
    public static final int REPEAT_MONTHLY = 3;
    public static final int REPEAT_ONCE = 0;
    public static final int REPEAT_WEEKLY = 2;
    public static final int WHATSAPP_BUSINESS = 1;
    public static final int WHATSAPP_NORMAL = 0;
    private List<String> contactJids;
    private List<String> contactNames;
    private long createdTime;

    private long id;
    private boolean isActive;
    private boolean isSent;
    private long lastSentTime;
    private String message;
    private int repeatDays;
    private int repeatType;
    private long scheduledTime;
    private int whatsappType;

    private String imagePath;

    public ScheduledMessage() {
        this.createdTime = System.currentTimeMillis();
        this.isActive = true;
        this.isSent = false;
        this.lastSentTime = 0L;
        this.repeatDays = 0;
        this.whatsappType = 0;
        this.contactJids = new ArrayList();
        this.contactNames = new ArrayList();
        this.imagePath = null;
    }

    public ScheduledMessage(long id, List<String> contactJids, List<String> contactNames, String message, long scheduledTime, int repeatType, int repeatDays, boolean isActive, boolean isSent, long lastSentTime, long createdTime, int whatsappType, String imagePath) {
        this.id = id;
        this.contactJids = contactJids != null ? contactJids : new ArrayList<>();
        this.contactNames = contactNames != null ? contactNames : new ArrayList<>();
        this.message = message;
        this.scheduledTime = scheduledTime;
        this.repeatType = repeatType;
        this.repeatDays = repeatDays;
        this.isActive = isActive;
        this.isSent = isSent;
        this.lastSentTime = lastSentTime;
        this.createdTime = createdTime;
        this.whatsappType = whatsappType;
        this.imagePath = imagePath;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public boolean hasImage() {
        return imagePath != null && !imagePath.isEmpty();
    }

    public long getId() {
        return this.id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public static String listToJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        JSONArray jsonArray = new JSONArray();
        for (String item : list) {
            jsonArray.put(item);
        }
        return jsonArray.toString();
    }

    public static List<String> jsonToList(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.isEmpty() || json.equals("[]")) {
            return list;
        }
        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(jsonArray.getString(i));
            }
        } catch (JSONException e) {
            list.add(json);
        }
        return list;
    }

    public List<String> getContactJids() {
        return this.contactJids;
    }

    public void setContactJids(List<String> contactJids) {
        this.contactJids = contactJids != null ? contactJids : new ArrayList<>();
    }

    public List<String> getContactNames() {
        return this.contactNames;
    }

    public void setContactNames(List<String> contactNames) {
        this.contactNames = contactNames != null ? contactNames : new ArrayList<>();
    }

    @Deprecated
    public String getContactJid() {
        if (this.contactJids.isEmpty()) {
            return null;
        }
        return this.contactJids.get(0);
    }

    @Deprecated
    public void setContactJid(String contactJid) {
        this.contactJids.clear();
        if (contactJid != null) {
            this.contactJids.add(contactJid);
        }
    }

    @Deprecated
    public String getContactName() {
        return this.contactNames.isEmpty() ? "" : this.contactNames.get(0);
    }

    @Deprecated
    public void setContactName(String contactName) {
        this.contactNames.clear();
        if (contactName != null) {
            this.contactNames.add(contactName);
        }
    }

    public void addContact(String jid, String name) {
        if (jid != null) {
            this.contactJids.add(jid);
            this.contactNames.add(name != null ? name : jid.split(OutputUtil.MARGIN_AREA_OPENING)[0]);
        }
    }

    public void clearContacts() {
        this.contactJids.clear();
        this.contactNames.clear();
    }

    public String getMessage() {
        return this.message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getScheduledTime() {
        return this.scheduledTime;
    }

    public void setScheduledTime(long scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public int getRepeatType() {
        return this.repeatType;
    }

    public void setRepeatType(int repeatType) {
        this.repeatType = repeatType;
    }

    public int getRepeatDays() {
        return this.repeatDays;
    }

    public void setRepeatDays(int repeatDays) {
        this.repeatDays = repeatDays;
    }

    public boolean isDaySelected(int dayFlag) {
        return (this.repeatDays & dayFlag) != 0;
    }

    public void setDaySelected(int dayFlag, boolean selected) {
        if (selected) {
            this.repeatDays |= dayFlag;
        } else {
            this.repeatDays &= ~dayFlag;
        }
    }

    public List<Integer> getSelectedDays() {
        List<Integer> days = new ArrayList<>();
        if ((this.repeatDays & 1) != 0) {
            days.add(1);
        }
        if ((this.repeatDays & 2) != 0) {
            days.add(2);
        }
        if ((this.repeatDays & 4) != 0) {
            days.add(3);
        }
        if ((this.repeatDays & 8) != 0) {
            days.add(4);
        }
        if ((this.repeatDays & 16) != 0) {
            days.add(5);
        }
        if ((this.repeatDays & 32) != 0) {
            days.add(6);
        }
        if ((this.repeatDays & 64) != 0) {
            days.add(7);
        }
        return days;
    }

    public boolean isActive() {
        return this.isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public boolean isSent() {
        return this.isSent;
    }

    public void setSent(boolean sent) {
        this.isSent = sent;
    }

    public long getLastSentTime() {
        return this.lastSentTime;
    }

    public void setLastSentTime(long lastSentTime) {
        this.lastSentTime = lastSentTime;
    }

    public long getCreatedTime() {
        return this.createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public int getWhatsappType() {
        return this.whatsappType;
    }

    public void setWhatsappType(int whatsappType) {
        this.whatsappType = whatsappType;
    }

    public String getRepeatTypeString() {
        switch (this.repeatType) {
            case 1:
                return "Daily";
            case 2:
                return "Weekly";
            case 3:
                return "Monthly";
            case 4:
                return "Custom";
            default:
                return "Once";
        }
    }

    public String getRepeatTypeString(Context context) {
        return getRepeatTypeString();
    }

    public String getSelectedDaysShortString() {
        if (this.repeatType != 4 || this.repeatDays == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] dayNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        int[] dayFlags = {1, 2, 4, 8, 16, 32, 64};
        for (int i = 0; i < 7; i++) {
            if ((this.repeatDays & dayFlags[i]) != 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(dayNames[i]);
            }
        }
        return sb.toString();
    }

    public int getContactCount() {
        return this.contactJids.size();
    }

    private long getNextCustomDayTime(long fromTime) {
        if (this.repeatDays == 0) {
            return fromTime;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(fromTime);
        Calendar originalTime = Calendar.getInstance();
        originalTime.setTimeInMillis(this.scheduledTime);
        int hour = originalTime.get(11);
        int minute = originalTime.get(12);
        for (int i = 0; i < 7; i++) {
            int dayOfWeek = cal.get(7);
            int dayFlag = getDayFlagFromCalendar(dayOfWeek);
            if ((this.repeatDays & dayFlag) != 0) {
                cal.set(11, hour);
                cal.set(12, minute);
                cal.set(13, 0);
                cal.set(14, 0);
                return cal.getTimeInMillis();
            }
            cal.add(5, 1);
        }
        return fromTime;
    }

    private int getDayFlagFromCalendar(int calendarDay) {
        switch (calendarDay) {
            case 1:
                return 1;
            case 2:
                return 2;
            case 3:
                return 4;
            case 4:
                return 8;
            case 5:
                return 16;
            case 6:
                return 32;
            case 7:
                return 64;
            default:
                return 0;
        }
    }

    public String getContactsDisplayString() {
        if (this.contactNames.isEmpty()) {
            return "";
        }
        if (this.contactNames.size() == 1) {
            return this.contactNames.get(0);
        }
        if (this.contactNames.size() <= 3) {
            return String.join(", ", this.contactNames);
        }
        return this.contactNames.get(0) + " +" + (this.contactNames.size() - 1);
    }

    public long getNextScheduledTime() {
        if (this.repeatType == 0) {
            return this.scheduledTime;
        }
        System.currentTimeMillis();
        if (this.lastSentTime == 0) {
            if (this.repeatType == 4) {
                return getNextCustomDayTime(this.scheduledTime);
            }
            return this.scheduledTime;
        }
        long baseTime = this.lastSentTime;
        switch (this.repeatType) {
            case 1:
                long nextTime = baseTime + 86400000;
                return nextTime;
            case 2:
                long nextTime2 = baseTime + (7 * 86400000);
                return nextTime2;
            case 3:
                long nextTime3 = baseTime + (30 * 86400000);
                return nextTime3;
            case 4:
                long nextTime4 = getNextCustomDayTime(baseTime + 86400000);
                return nextTime4;
            default:
                return baseTime;
        }
    }

    public boolean shouldSendNow() {
        if (!this.isActive) {
            return false;
        }
        if (this.repeatType == 0 && this.isSent) {
            return false;
        }
        if (this.repeatType == 4 && this.repeatDays != 0) {
            Calendar today = Calendar.getInstance();
            int todayFlag = getDayFlagFromCalendar(today.get(7));
            if ((this.repeatDays & todayFlag) == 0) {
                return false;
            }
        }
        long now = System.currentTimeMillis();
        long nextTime = getNextScheduledTime();
        return now >= nextTime;
    }
}