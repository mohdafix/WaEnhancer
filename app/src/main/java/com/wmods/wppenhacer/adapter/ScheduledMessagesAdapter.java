package com.wmods.wppenhacer.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ScheduledMessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    private final Context context;
    private final OnMessageActionListener listener;
    
    // The flat list currently displayed
    private List<ListItem> items = new ArrayList<>();
    
    // Data structures for grouping
    private Map<String, List<ScheduledMessage>> groupedData = new LinkedHashMap<>();
    private Map<String, Boolean> expandedState = new HashMap<>();

    public interface OnMessageActionListener {
        void onEdit(ScheduledMessage scheduledMessage);
        void onDelete(ScheduledMessage scheduledMessage);
        void onToggleActive(ScheduledMessage scheduledMessage, boolean z);
    }

    public ScheduledMessagesAdapter(Context context, OnMessageActionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setMessages(List<ScheduledMessage> messages) {
        groupedData.clear();
        if (messages != null && !messages.isEmpty()) {
            // Sort raw list first
            Collections.sort(messages, (o1, o2) -> o1.getContactsDisplayString().compareToIgnoreCase(o2.getContactsDisplayString()));

            // Group data
            for (ScheduledMessage message : messages) {
                String key = message.getContactsDisplayString();
                if (!groupedData.containsKey(key)) {
                    groupedData.put(key, new ArrayList<>());
                    
                    // Initialize state as expanded by default if new
                    if (!expandedState.containsKey(key)) {
                        expandedState.put(key, true);
                    }
                }
                groupedData.get(key).add(message);
            }
        }
        rebuildList();
    }
    
    private void rebuildList() {
        items.clear();
        for (Map.Entry<String, List<ScheduledMessage>> entry : groupedData.entrySet()) {
            String headerTitle = entry.getKey();
            boolean isExpanded = expandedState.get(headerTitle) != null && expandedState.get(headerTitle);
            
            items.add(new HeaderItem(headerTitle, isExpanded));
            
            if (isExpanded) {
                for (ScheduledMessage msg : entry.getValue()) {
                    items.add(new MessageItem(msg));
                }
            }
        }
        notifyDataSetChanged();
    }
    
    private void toggleGroup(String headerTitle) {
        boolean current = expandedState.get(headerTitle) != null && expandedState.get(headerTitle);
        expandedState.put(headerTitle, !current);
        rebuildList();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scheduled_header, parent, false);
            return new HeaderViewHolder(view, this::toggleGroup);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scheduled_message, parent, false);
            return new MessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind((HeaderItem) items.get(position));
        } else if (holder instanceof MessageViewHolder) {
            ((MessageViewHolder) holder).bind(((MessageItem) items.get(position)).message);
        }
    }

    @Override
    public int getItemCount() {
        return this.items.size();
    }

    // --- List Items ---

    private interface ListItem {
        int getType();
    }

    private static class HeaderItem implements ListItem {
        String title;
        boolean isExpanded;
        HeaderItem(String title, boolean isExpanded) { 
            this.title = title; 
            this.isExpanded = isExpanded;
        }
        @Override public int getType() { return VIEW_TYPE_HEADER; }
    }

    private static class MessageItem implements ListItem {
        ScheduledMessage message;
        MessageItem(ScheduledMessage message) { this.message = message; }
        @Override public int getType() { return VIEW_TYPE_ITEM; }
    }

    // --- ViewHolders ---
    
    interface OnHeaderClickListener {
        void onHeaderClick(String title);
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView textTitle;
        OnHeaderClickListener listener;
        String currentTitle;

        HeaderViewHolder(View itemView, OnHeaderClickListener listener) {
            super(itemView);
            this.listener = listener;
            textTitle = itemView.findViewById(R.id.text_header_title);
            itemView.setOnClickListener(v -> {
                if (currentTitle != null) listener.onHeaderClick(currentTitle);
            });
        }

        void bind(HeaderItem item) {
            currentTitle = item.title;
            textTitle.setText(item.title);
            // Rotate arrow based on state (assuming arrow_down_float is pointing down)
            // 0 -> Down (Expanded), -90 -> Right (Collapsed)
            // Or if system drawable is static, we can just rotate.
            textTitle.animate().rotation(0).setDuration(0).start(); // Reset
            
            // Adjust the arrow rotation to indicate state
            // Let's assume the icon is an arrow pointing down
            // Expanded: Point Down (0 deg)
            // Collapsed: Point Right (-90 deg or 90 deg depending on LTR/RTL) 
            // Better yet, just use standard rotation logic if the drawable allows it.
            // Since we use compounddrawable, we can't easily rotate ONLY the drawable without custom view or wrappers.
            // Simple approach: Use text color or alpha to verify it's working first, or assume user knows click = toggle.
            
            // For better UX:
            if (item.isExpanded) {
                textTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
            } else {
                 // Try to find a 'right' arrow or just reuse down and maybe don't rotate to avoid complexity for now? 
                 // Actually there isn't a guaranteed arrow_right_float public resource. 
                 // Let's stick to arrow_down and maybe just tint it or swap it if possible.
                 // Ideally we'd rotate the View but that rotates text too.
                 // A common trick is to use arrow_down for expanded and arrow_up (if available) or nothing for collapsed? 
                 // Or just use arrow_down for both but relying on the list height change.
                 
                 // Let's try arrow_up_float if exists, otherwise same icon.
                textTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_up_float, 0); 
                // Note: arrow_up_float might not exist publicly. If it crashes, I'll revert.
                // Safest fallback: use arrow_down_float for expanded, and 0 (no icon) for collapsed? No that's confusing.
                // Let's try to assume arrow_down_float works for both for now, just toggling listing.
                textTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0); 
                // Or rotate the whole view?
                // itemView.setRotation(item.isExpanded ? 0 : -90); // BAD idea for text.
            }
            
            // Re-apply rotation to the compound drawable? Not possible easily.
            // Let's manually set rotation on the TextView for now just to show state, acknowledging text rotates too? No that looks broken.
            
            // Best standard quick fix: 
            // Expanded: arrow_down
            // Collapsed: arrow_drop_down (often smaller) or just keep same icon.
        }
    }

    class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView textContactName;
        TextView textMessagePreview;
        TextView textScheduleTime;
        Chip chipStatus;
        SwitchMaterial switchActive;
        ImageButton buttonEdit;
        ImageButton buttonDelete;

        public MessageViewHolder(View itemView) {
            super(itemView);
            this.textContactName = itemView.findViewById(R.id.text_contact_name);
            this.textMessagePreview = itemView.findViewById(R.id.text_message_preview);
            this.textScheduleTime = itemView.findViewById(R.id.text_schedule_time);
            this.chipStatus = itemView.findViewById(R.id.chip_status);
            this.switchActive = itemView.findViewById(R.id.switch_active);
            this.buttonEdit = itemView.findViewById(R.id.button_edit);
            this.buttonDelete = itemView.findViewById(R.id.button_delete);
        }

        public void bind(final ScheduledMessage message) {
            this.textContactName.setVisibility(View.GONE);
            this.textMessagePreview.setText(message.getMessage());
            StringBuilder timeInfo = new StringBuilder("Scheduled for: ");
            if (message.getRepeatType() != 0) {
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                timeInfo.append(timeFormat.format(new Date(message.getScheduledTime())));
                timeInfo.append(", ");
                timeInfo.append(message.getRepeatTypeString());
            } else {
                SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, ", Locale.getDefault());
                timeInfo.append(dateFormat.format(new Date(message.getScheduledTime())));
                SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                timeInfo.append(timeFormat.format(new Date(message.getScheduledTime())));
                timeInfo.append(", Once");
            }
            this.textScheduleTime.setText(timeInfo.toString());
            this.switchActive.setOnCheckedChangeListener(null);
            this.switchActive.setChecked(message.isActive());
            this.switchActive.setEnabled((message.isSent() && message.getRepeatType() == 0) ? false : true);
            this.switchActive.setOnCheckedChangeListener((buttonView, isChecked) -> ScheduledMessagesAdapter.this.listener.onToggleActive(message, isChecked));
            this.buttonEdit.setOnClickListener(v -> ScheduledMessagesAdapter.this.listener.onEdit(message));
            this.buttonDelete.setOnClickListener(v -> ScheduledMessagesAdapter.this.listener.onDelete(message));
            if (message.isSent() && message.getRepeatType() == 0) {
                this.chipStatus.setText(R.string.schedule_sent);
                this.chipStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_active)));
                this.chipStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_active)));
                this.chipStatus.setTextColor(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, android.R.color.white));
            } else if (message.isActive()) {
                this.chipStatus.setText(R.string.schedule_pending);
                this.chipStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_warning)));
                this.chipStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_warning)));
                this.chipStatus.setTextColor(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, android.R.color.white));
            } else {
                this.chipStatus.setText(R.string.schedule_inactive);
                this.chipStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_inactive)));
                this.chipStatus.setChipStrokeColor(ColorStateList.valueOf(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, R.color.status_inactive)));
                this.chipStatus.setTextColor(ContextCompat.getColor(ScheduledMessagesAdapter.this.context, android.R.color.white));
            }
        }
    }
}