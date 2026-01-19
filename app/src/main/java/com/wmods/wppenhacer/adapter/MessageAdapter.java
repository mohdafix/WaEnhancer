package com.wmods.wppenhacer.adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.db.MessageHistory;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

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
        holder.text1.setText(this.items.get(position).message);
        holder.text2.setTextSize(12.0f);
        holder.text2.setAlpha(0.75f);
        holder.text2.setTypeface(null, Typeface.ITALIC);
        holder.text2.setTextColor(DesignUtils.getPrimaryTextColor());
        var timestamp = this.items.get(position).timestamp;
        holder.text2.setText((timestamp == 0L ? context.getString(ResId.string.message_original) : "✏️ " + Utils.getDateTimeFromMillis(timestamp)));
        return convertView;
    }

}