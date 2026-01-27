package com.wmods.wppenhacer.ui.fragments;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment;

public class CustomizationFragment extends BasePreferenceFragment {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        super.onCreatePreferences(savedInstanceState, rootKey);
        setPreferencesFromResource(R.xml.fragment_customization, rootKey);
    }

    @Override
    public void onResume() {
        super.onResume();
        setDisplayHomeAsUpEnabled(false);
    }

    @Override
    public void onDisplayPreferenceDialog(androidx.preference.Preference preference) {
        if (preference instanceof com.wmods.wppenhacer.preference.custom.FilterItemsPreference) {
             androidx.fragment.app.DialogFragment dialogFragment = 
                 com.wmods.wppenhacer.preference.custom.FilterItemsPreferenceDialog.newInstance(preference.getKey());
             dialogFragment.setTargetFragment(this, 0);
             dialogFragment.show(getParentFragmentManager(), "androidx.preference.PreferenceFragment.DIALOG");
        } else {
             super.onDisplayPreferenceDialog(preference);
        }
    }
}
