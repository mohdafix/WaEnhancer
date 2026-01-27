package com.wmods.wppenhacer.preference.custom;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.DialogPreference;
import androidx.preference.PreferenceDialogFragmentCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmods.wppenhacer.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class FilterItemsPreference extends DialogPreference {

    public FilterItemsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    
    @Override
    public int getDialogLayoutResource() {
        return R.layout.dialog_filter_items;
    }

    @Override
    public CharSequence getSummary() {
        String value = getSharedPreferences().getString(getKey(), "");
        List<String> active = getActiveFilters(value);
        if (active.isEmpty()) {
            return getContext().getString(R.string.filter_items_by_id_sum);
        }
        return active.size() + " items active";
    }

    public static class FilterItem {
        public String idName;
        public boolean enabled;

        public FilterItem(String idName, boolean enabled) {
            this.idName = idName;
            this.enabled = enabled;
        }
        
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", idName);
            json.put("enabled", enabled);
            return json;
        }

        public static FilterItem fromJson(JSONObject json) throws JSONException {
            return new FilterItem(json.getString("id"), json.getBoolean("enabled"));
        }
    }

    public static List<FilterItem> loadItems(String jsonString) {
        List<FilterItem> items = new ArrayList<>();
        if (TextUtils.isEmpty(jsonString)) return items;
        try {
            JSONArray array = new JSONArray(jsonString);
            for (int i = 0; i < array.length(); i++) {
                items.add(FilterItem.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException e) {
            // Fallback for legacy format (plain text separated by newlines)
             String[] lines = jsonString.split("\n");
             for (String line : lines) {
                 if (!line.trim().isEmpty()) {
                     items.add(new FilterItem(line.trim(), true));
                 }
             }
        }
        return items;
    }

    public static String saveItems(List<FilterItem> items) {
        JSONArray array = new JSONArray();
        try {
            for (FilterItem item : items) {
                array.put(item.toJson());
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return array.toString();
    }
    
    // Helper used by the hook to get active filters
    public static List<String> getActiveFilters(String jsonString) {
        List<String> active = new ArrayList<>();
        for (FilterItem item : loadItems(jsonString)) {
            if (item.enabled) active.add(item.idName);
        }
        return active;
    }
}
