package com.wmods.wppenhacer.adapter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.util.Arrays;
import java.util.List;

public class MessageAdapter extends ArrayAdapter<MessageHistory.MessageItem> {
    private final Context context;
    private final List<MessageHistory.MessageItem> items;

    public MessageAdapter(Context context, List<MessageHistory.MessageItem> items) {
        super(context, android.R.layout.simple_list_item_2, android.R.id.text1, items);
        this.context = context;
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public MessageHistory.MessageItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class ViewHolder {
        TextView text1;
        TextView text2;
    }

    private CharSequence getDiffSpannable(String oldText, String newText) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";

        SpannableStringBuilder builder = new SpannableStringBuilder();

        // Simple word-based diff
        String[] oldWords = oldText.split("\\s+");
        String[] newWords = newText.split("\\s+");

        int oldIndex = 0;
        int newIndex = 0;

        while (oldIndex < oldWords.length || newIndex < newWords.length) {
            if (oldIndex < oldWords.length && newIndex < newWords.length && oldWords[oldIndex].equals(newWords[newIndex])) {
                // Unchanged word
                if (builder.length() > 0) builder.append(" ");
                builder.append(newWords[newIndex]);
                oldIndex++;
                newIndex++;
            } else {
                // Check if we can find a match later for old word
                boolean foundMatch = false;
                for (int i = newIndex; i < newWords.length; i++) {
                    if (oldWords[oldIndex].equals(newWords[i])) {
                        // Add remaining new words as additions
                        for (int j = newIndex; j < i; j++) {
                            if (builder.length() > 0) builder.append(" ");
                            int start = builder.length();
                            builder.append(newWords[j]);
                            builder.setSpan(new ForegroundColorSpan(Color.GREEN), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        // Add the matched word normally
                        if (builder.length() > 0) builder.append(" ");
                        builder.append(newWords[i]);
                        newIndex = i + 1;
                        foundMatch = true;
                        break;
                    }
                }
                if (!foundMatch && oldIndex < oldWords.length) {
                    // Old word deleted
                    if (builder.length() > 0) builder.append(" ");
                    int start = builder.length();
                    builder.append(oldWords[oldIndex]);
                    builder.setSpan(new ForegroundColorSpan(Color.RED), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new StrikethroughSpan(), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    oldIndex++;
                }
                if (!foundMatch && newIndex < newWords.length) {
                    // New word added
                    if (builder.length() > 0) builder.append(" ");
                    int start = builder.length();
                    builder.append(newWords[newIndex]);
                    builder.setSpan(new ForegroundColorSpan(Color.GREEN), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    newIndex++;
                }
            }
        }

        return builder;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = super.getView(position, convertView, parent);
            holder = new ViewHolder();
            holder.text1 = convertView.findViewById(android.R.id.text1);
            holder.text2 = convertView.findViewById(android.R.id.text2);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        holder.text1.setTextSize(14.0f);
        holder.text1.setTextColor(DesignUtils.getPrimaryTextColor());

        if (position == 0) {
            // Original message, show normally
            holder.text1.setText(this.items.get(position).message);
        } else {
            // Edited message, show diff with previous
            String prevMessage = this.items.get(position - 1).message;
            String currMessage = this.items.get(position).message;
            CharSequence diffText = getDiffSpannable(prevMessage, currMessage);
            holder.text1.setText(diffText);
        }
        holder.text2.setTextSize(12.0f);
        holder.text2.setAlpha(0.75f);
        holder.text2.setTypeface(null, Typeface.ITALIC);
        holder.text2.setTextColor(DesignUtils.getPrimaryTextColor());
        var timestamp = this.items.get(position).timestamp;
        holder.text2.setText((timestamp == 0L ? context.getString(ResId.string.message_original) : "✏️ " + Utils.getDateTimeFromMillis(timestamp)));
        return convertView;
    }

}