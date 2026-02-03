package com.wmods.wppenhacer.adapter;

import android.animation.LayoutTransition;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.SparseBooleanArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
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

    // Track expansion per row. Avoid collapsing others to prevent scroll jump.
    private final SparseBooleanArray expandedPositions = new SparseBooleanArray();

    // Requested diff colors
    // green: rgba(122, 224, 178, 1)
    // red:   rgba(238, 118, 117, 1)
    private static final int DIFF_ADDED_COLOR = Color.rgb(122, 224, 178);
    private static final int DIFF_DELETED_COLOR = Color.rgb(238, 118, 117);

    private static final int EXPANDED_BG_ALPHA = 16;

    public MessageAdapter(Context context, List<MessageHistory.MessageItem> items) {
        super(context, 0, items);
        this.context = context;
        this.items = items;
    }

    @Override
    public int getCount() {
        // Hide the original message (timestamp == 0) and only show diffs for edits.
        // We still keep the original in the list because it is needed as the base for the first diff.
        return Math.max(0, items.size() - 1);
    }

    @Override
    public MessageHistory.MessageItem getItem(int position) {
        // Offset by 1 to skip the original message.
        return items.get(position + 1);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class ViewHolder {
        TextView time;
        TextView diff;

        LinearLayout expandedContainer;
        TextView originalLabel;
        TextView originalText;
        TextView editedLabel;
        TextView editedText;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams lpMatchWrapWithTopMargin(int topMarginDp) {
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.topMargin = Utils.dipToPixels(topMarginDp);
        return lp;
    }

    private TextView makeMetaTextView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(12.0f);
        tv.setAlpha(0.75f);
        tv.setTypeface(null, Typeface.ITALIC);
        tv.setLayoutParams(lpMatchWrap());
        return tv;
    }

    private TextView makeBodyTextView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(14.0f);
        tv.setLayoutParams(lpMatchWrap());
        tv.setSingleLine(false);
        tv.setMaxLines(Integer.MAX_VALUE);
        return tv;
    }

    private void applyExpandedState(ViewHolder holder, boolean expanded) {
        holder.diff.setVisibility(expanded ? View.GONE : View.VISIBLE);
        holder.expandedContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private CharSequence getDiffSpannable(String oldText, String newText) {
        if (oldText == null) oldText = "";
        if (newText == null) newText = "";

        SpannableStringBuilder builder = new SpannableStringBuilder();
        String NEWLINE_TOKEN = "<NL>";

        // Preserve newlines by treating them as tokens
        String oldPrepared = oldText.replace("\n", " " + NEWLINE_TOKEN + " ");
        String newPrepared = newText.replace("\n", " " + NEWLINE_TOKEN + " ");

        String[] oldWords = filterEmpty(oldPrepared.trim().split("\\s+"));
        String[] newWords = filterEmpty(newPrepared.trim().split("\\s+"));

        int oldIndex = 0;
        int newIndex = 0;

        while (oldIndex < oldWords.length || newIndex < newWords.length) {
            if (oldIndex < oldWords.length && newIndex < newWords.length && oldWords[oldIndex].equals(newWords[newIndex])) {
                 appendWord(builder, newWords[newIndex], NEWLINE_TOKEN);
                 oldIndex++;
                 newIndex++;
            } else {
                boolean foundMatch = false;
                if (oldIndex < oldWords.length && newIndex < newWords.length) {
                    for (int i = newIndex; i < newWords.length; i++) {
                        if (oldWords[oldIndex].equals(newWords[i])) {
                            for (int j = newIndex; j < i; j++) {
                                int start = builder.length();
                                appendWord(builder, newWords[j], NEWLINE_TOKEN);
                                if (!newWords[j].equals(NEWLINE_TOKEN))
                                    builder.setSpan(new ForegroundColorSpan(DIFF_ADDED_COLOR), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                            appendWord(builder, newWords[i], NEWLINE_TOKEN);
                            newIndex = i + 1;
                            foundMatch = true;
                            break;
                        }
                    }
                }
                if (!foundMatch) {
                    if (oldIndex < oldWords.length) {
                        int start = builder.length();
                        appendWord(builder, oldWords[oldIndex], NEWLINE_TOKEN);
                        if (!oldWords[oldIndex].equals(NEWLINE_TOKEN)) {
                            builder.setSpan(new ForegroundColorSpan(DIFF_DELETED_COLOR), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            builder.setSpan(new StrikethroughSpan(), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        oldIndex++;
                    } else if (newIndex < newWords.length) {
                        int start = builder.length();
                        appendWord(builder, newWords[newIndex], NEWLINE_TOKEN);
                        if (!newWords[newIndex].equals(NEWLINE_TOKEN))
                            builder.setSpan(new ForegroundColorSpan(DIFF_ADDED_COLOR), start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        newIndex++;
                    }
                }
            }
        }

        return builder;
    }

    private String[] filterEmpty(String[] arr) {
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (String s : arr) if (!s.isEmpty()) list.add(s);
        return list.toArray(new String[0]);
    }

    private void appendWord(SpannableStringBuilder builder, String word, String newlineToken) {
        if (word.equals(newlineToken)) {
            builder.append("\n");
        } else {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                builder.append(" ");
            }
            builder.append(word);
        }
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            int padH = Utils.dipToPixels(16);
            int padV = Utils.dipToPixels(10);
            root.setPadding(padH, padV, padH, padV);

            LayoutTransition lt = new LayoutTransition();
            lt.enableTransitionType(LayoutTransition.APPEARING);
            lt.enableTransitionType(LayoutTransition.DISAPPEARING);
            lt.enableTransitionType(LayoutTransition.CHANGING);
            lt.setDuration(220);
            root.setLayoutTransition(lt);

            holder = new ViewHolder();

            holder.time = makeMetaTextView(context);
            holder.diff = makeBodyTextView(context);
            holder.diff.setLayoutParams(lpMatchWrapWithTopMargin(6));

            holder.expandedContainer = new LinearLayout(context);
            holder.expandedContainer.setOrientation(LinearLayout.VERTICAL);
            holder.expandedContainer.setLayoutParams(lpMatchWrapWithTopMargin(8));
            int innerPad = Utils.dipToPixels(12);
            holder.expandedContainer.setPadding(innerPad, innerPad, innerPad, innerPad);
            holder.expandedContainer.setVisibility(View.GONE);

            holder.originalLabel = makeMetaTextView(context);
            holder.originalText = makeBodyTextView(context);
            holder.originalText.setLayoutParams(lpMatchWrapWithTopMargin(2));

            holder.editedLabel = makeMetaTextView(context);
            holder.editedLabel.setLayoutParams(lpMatchWrapWithTopMargin(10));
            holder.editedText = makeBodyTextView(context);
            holder.editedText.setLayoutParams(lpMatchWrapWithTopMargin(2));

            holder.expandedContainer.addView(holder.originalLabel);
            holder.expandedContainer.addView(holder.originalText);
            holder.expandedContainer.addView(holder.editedLabel);
            holder.expandedContainer.addView(holder.editedText);

            root.addView(holder.time);
            root.addView(holder.diff);
            root.addView(holder.expandedContainer);

            convertView = root;
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        boolean expanded = expandedPositions.get(position, false);

        int actualIndex = position + 1;
        String originalMessage = this.items.get(0).message;
        if (originalMessage == null) originalMessage = "";
        String currMessage = this.items.get(actualIndex).message;
        if (currMessage == null) currMessage = "";
        long timestamp = this.items.get(actualIndex).timestamp;

        holder.time.setTextColor(DesignUtils.getPrimaryTextColor());
        holder.time.setText("✏️ " + Utils.getDateTimeFromMillis(timestamp));

        holder.diff.setTextColor(DesignUtils.getPrimaryTextColor());
        holder.diff.setSingleLine(false);
        holder.diff.setMaxLines(Integer.MAX_VALUE);

        holder.originalLabel.setTextColor(DesignUtils.getPrimaryTextColor());
        holder.originalText.setTextColor(DesignUtils.getPrimaryTextColor());
        holder.editedLabel.setTextColor(DesignUtils.getPrimaryTextColor());
        holder.editedText.setTextColor(DesignUtils.getPrimaryTextColor());

        // Content (set for both states so click toggling doesn't need notifyDataSetChanged)
        holder.diff.setText(getDiffSpannable(originalMessage, currMessage));
        holder.originalLabel.setText(context.getString(ResId.string.message_original));
        holder.originalText.setText(originalMessage);
        holder.editedLabel.setText(context.getString(ResId.string.edited_history));
        holder.editedText.setText(currMessage);

        // Card-like background for expanded details
        var cardDrawable = DesignUtils.createDrawable("selector_bg", Color.BLACK);
        holder.expandedContainer.setBackground(
                DesignUtils.alphaDrawable(cardDrawable, DesignUtils.getPrimaryTextColor(), EXPANDED_BG_ALPHA)
        );

        applyExpandedState(holder, expanded);

        final int boundPosition = position;
        convertView.setOnClickListener(v -> {
            boolean nextExpanded = !expandedPositions.get(boundPosition, false);
            expandedPositions.put(boundPosition, nextExpanded);
            applyExpandedState(holder, nextExpanded);

            // Fade/slide the newly shown block a bit
            View animatedTarget = nextExpanded ? holder.expandedContainer : holder.diff;
            animatedTarget.setAlpha(0.0f);
            animatedTarget.setTranslationY(nextExpanded ? Utils.dipToPixels(6) : Utils.dipToPixels(-6));
            animatedTarget.animate()
                    .alpha(1.0f)
                    .translationY(0.0f)
                    .setDuration(200)
                    .start();
        });
        return convertView;
    }

}
