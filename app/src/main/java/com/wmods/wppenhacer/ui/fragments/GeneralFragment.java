package com.wmods.wppenhacer.ui.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.ScheduledMessagesListActivity;
import com.wmods.wppenhacer.ui.fragments.base.BaseFragment;
import com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment;

public class GeneralFragment extends BaseFragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        var root = super.onCreateView(inflater, container, savedInstanceState);
        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction().add(R.id.frag_container, new GeneralPreferenceFragment()).commitNow();
        }
        return root;
    }

    public static class GeneralPreferenceFragment extends BasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            setPreferencesFromResource(R.xml.fragment_general, rootKey);
        }

        @Override
        public void onResume() {
            super.onResume();
            setDisplayHomeAsUpEnabled(false);
        }
    }

    public static class HomeGeneralPreference extends BasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            setPreferencesFromResource(R.xml.preference_general_home, rootKey);
            setDisplayHomeAsUpEnabled(true);
        }

        @Override
        public boolean onPreferenceTreeClick(Preference preference) {
            String key = preference.getKey();
            if ("scheduled_messages".equals(key)) {
                Intent intent = new Intent(getActivity(), ScheduledMessagesListActivity.class);
                startActivity(intent);
                return true;
            }
            return super.onPreferenceTreeClick(preference);
        }
    }

    public static class HomeScreenGeneralPreference extends BasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            setPreferencesFromResource(R.xml.preference_general_homescreen, rootKey);
            setDisplayHomeAsUpEnabled(true);
        }
    }

    public static class ConversationGeneralPreference extends BasePreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            // Migrate boolean preferences to string for ListPreferences
            var editor = mPrefs.edit();
            boolean changed = false;
            String[] listPrefKeys = {"toastdeleted", "antirevoke", "antirevokestatus", "profile_picture_change_toast"};
            for (String key : listPrefKeys) {
                Object value = mPrefs.getAll().get(key);
                if (value instanceof Boolean) {
                    boolean boolValue = (Boolean) value;
                    editor.putString(key, boolValue ? "1" : "0");
                    changed = true;
                }
            }
            if (changed) {
                editor.apply();
            }
            setPreferencesFromResource(R.xml.preference_general_conversation, rootKey);
            setDisplayHomeAsUpEnabled(true);
        }
    }

}