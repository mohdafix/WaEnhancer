package com.wmods.wppenhacer.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityOptionsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.adapter.SearchResultsAdapter;
import com.wmods.wppenhacer.model.SearchResult;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SearchActivity extends AppCompatActivity {

    private Toolbar toolbar;
    private EditText searchInput;
    private RecyclerView recyclerView;
    private SearchResultsAdapter adapter;
    private TextView noResultsText;
    private List<SearchResult> allPreferences;

    private static final Map<Integer, Class<?>> FRAGMENT_MAP = new HashMap<>();
    private static final Map<Integer, String> CATEGORY_MAP = new HashMap<>();

    static {
        FRAGMENT_MAP.put(R.xml.fragment_general, com.wmods.wppenhacer.ui.fragments.GeneralFragment.class);
        FRAGMENT_MAP.put(R.xml.fragment_privacy, com.wmods.wppenhacer.ui.fragments.PrivacyFragment.class);
        FRAGMENT_MAP.put(R.xml.fragment_customization, com.wmods.wppenhacer.ui.fragments.CustomizationFragment.class);
        FRAGMENT_MAP.put(R.xml.fragment_media, com.wmods.wppenhacer.ui.fragments.MediaFragment.class);

        CATEGORY_MAP.put(R.xml.fragment_general, "General");
        CATEGORY_MAP.put(R.xml.fragment_privacy, "Privacy");
        CATEGORY_MAP.put(R.xml.fragment_customization, "Customization");
        CATEGORY_MAP.put(R.xml.fragment_media, "Media");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        toolbar = findViewById(R.id.toolbar);
        searchInput = findViewById(R.id.search_input);
        recyclerView = findViewById(R.id.search_results);
        noResultsText = findViewById(R.id.no_results_text);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        setupRecyclerView();
        loadAllPreferences();
        setupSearchListener();
    }

    private void setupRecyclerView() {
        adapter = new SearchResultsAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        adapter.setOnSearchResultClickListener(result -> {
            Intent intent = new Intent(this, com.wmods.wppenhacer.activities.MainActivity.class);
            intent.putExtra("search_key", result.getKey());
            intent.putExtra("search_fragment", result.getFragmentClass().getName());
            startActivity(intent, 
                ActivityOptionsCompat.makeCustomAnimation(this, R.anim.slide_in_right, R.anim.slide_out_left).toBundle());
            finish();
        });
    }

    private void loadAllPreferences() {
        allPreferences = new ArrayList<>();
        loadPreferencesFromXml(R.xml.fragment_general);
        loadPreferencesFromXml(R.xml.fragment_privacy);
        loadPreferencesFromXml(R.xml.fragment_customization);
        loadPreferencesFromXml(R.xml.fragment_media);
    }

    private void loadPreferencesFromXml(int xmlResId) {
        try {
            XmlResourceParser parser = getResources().getXml(xmlResId);
            String currentCategory = CATEGORY_MAP.get(xmlResId);
            String currentSubCategory = "";

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tagName = parser.getName();
                    if ("PreferenceCategory".equals(tagName)) {
                        currentSubCategory = parser.getAttributeValue(null, "title");
                        if (currentSubCategory != null && currentSubCategory.startsWith("@string/")) {
                            int stringRes = getResources().getIdentifier(currentSubCategory.substring(9), "string", getPackageName());
                            if (stringRes != 0) {
                                currentSubCategory = getString(stringRes);
                            }
                        }
                    } else if ("Preference".equals(tagName) || 
                               "SwitchPreference".equals(tagName) || 
                               "ListPreference".equals(tagName) ||
                               "EditTextPreference".equals(tagName) ||
                               "CheckBoxPreference".equals(tagName) ||
                               "SeekBarPreference".equals(tagName) ||
                               "MultiSelectListPreference".equals(tagName) ||
                               "PreferenceScreen".equals(tagName) ||
                               tagName.contains("MaterialSwitchPreference") ||
                               tagName.contains("ColorPreferenceCompat")) {
                        
                        String key = parser.getAttributeValue(null, "key");
                        String title = parser.getAttributeValue(null, "title");
                        String summary = parser.getAttributeValue(null, "summary");

                        if (key != null && !key.isEmpty() && title != null) {
                            String resolvedTitle = resolveString(title);
                            String resolvedSummary = summary != null ? resolveString(summary) : "";
                            String resolvedCategory = currentSubCategory.isEmpty() ? currentCategory : 
                                currentCategory + " > " + currentSubCategory;

                            if (resolvedTitle != null) {
                                allPreferences.add(new SearchResult(
                                    key,
                                    resolvedTitle,
                                    resolvedSummary,
                                    resolvedCategory,
                                    FRAGMENT_MAP.get(xmlResId)
                                ));
                            }
                        }
                    }
                }
                eventType = parser.next();
            }
            parser.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String resolveString(String value) {
        if (value == null) return null;
        if (value.startsWith("@string/")) {
            int stringRes = getResources().getIdentifier(value.substring(9), "string", getPackageName());
            if (stringRes != 0) {
                return getString(stringRes);
            }
        }
        return value;
    }

    private void setupSearchListener() {
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                performSearch(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showKeyboard();
            }
        });

        showKeyboard();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private void performSearch(String query) {
        List<SearchResult> filteredResults = new ArrayList<>();

        if (TextUtils.isEmpty(query)) {
            adapter.setResults(filteredResults);
            noResultsText.setVisibility(View.VISIBLE);
            return;
        }

        String lowerCaseQuery = query.toLowerCase(Locale.getDefault());

        for (SearchResult result : allPreferences) {
            if (result.getTitle() != null && result.getTitle().toLowerCase(Locale.getDefault()).contains(lowerCaseQuery) ||
                result.getSummary() != null && result.getSummary().toLowerCase(Locale.getDefault()).contains(lowerCaseQuery) ||
                result.getCategory() != null && result.getCategory().toLowerCase(Locale.getDefault()).contains(lowerCaseQuery)) {
                filteredResults.add(result);
            }
        }

        adapter.setResults(filteredResults);
        noResultsText.setVisibility(filteredResults.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onBackPressed() {
        hideKeyboard();
        super.onBackPressed();
    }
}
