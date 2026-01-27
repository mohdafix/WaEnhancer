package com.wmods.wppenhacer.preference.custom;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceDialogFragmentCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmods.wppenhacer.R;

import java.util.ArrayList;
import java.util.List;

public class FilterItemsPreferenceDialog extends PreferenceDialogFragmentCompat {

    private RecyclerView recyclerView;
    private EditText inputAdd;
    private ImageButton btnAdd;
    private FilterItemAdapter adapter;
    private List<FilterItemsPreference.FilterItem> items;

    public static FilterItemsPreferenceDialog newInstance(String key) {
        final FilterItemsPreferenceDialog fragment = new FilterItemsPreferenceDialog();
        final Bundle b = new Bundle(1);
        b.putString(ARG_KEY, key);
        fragment.setArguments(b);
        return fragment;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        recyclerView = view.findViewById(R.id.recycler_filter_items);
        inputAdd = view.findViewById(R.id.input_filter_id);
        btnAdd = view.findViewById(R.id.btn_add_filter);

        String value = getPreference().getSharedPreferences().getString(getPreference().getKey(), "");
        items = FilterItemsPreference.loadItems(value);

        adapter = new FilterItemAdapter(items);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> {
            String idName = inputAdd.getText().toString().trim();
            if (!idName.isEmpty()) {
                boolean exists = false;
                for (FilterItemsPreference.FilterItem item : items) {
                    if (item.idName.equals(idName)) {
                        exists = true;
                        break;
                    }
                }
                
                if (!exists) {
                    items.add(new FilterItemsPreference.FilterItem(idName, true));
                    adapter.notifyItemInserted(items.size() - 1);
                    inputAdd.setText("");
                } else {
                     Toast.makeText(getContext(), "Item already exists", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult) {
            String json = FilterItemsPreference.saveItems(items);
            ((FilterItemsPreference) getPreference()).setValue(json);
        }
    }

    private class FilterItemAdapter extends RecyclerView.Adapter<FilterItemAdapter.ViewHolder> {
        private final List<FilterItemsPreference.FilterItem> list;

        FilterItemAdapter(List<FilterItemsPreference.FilterItem> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_filter_preference, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FilterItemsPreference.FilterItem item = list.get(position);
            holder.textId.setText(item.idName);
            holder.checkBox.setChecked(item.enabled);
            
            holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                item.enabled = isChecked;
            });

            holder.btnDelete.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    list.remove(pos);
                    notifyItemRemoved(pos);
                }
            });
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textId;
            CheckBox checkBox;
            ImageButton btnDelete;

            ViewHolder(View itemView) {
                super(itemView);
                textId = itemView.findViewById(R.id.text_filter_id);
                checkBox = itemView.findViewById(R.id.checkbox_filter_enable);
                btnDelete = itemView.findViewById(R.id.btn_delete_filter);
            }
        }
    }
}
