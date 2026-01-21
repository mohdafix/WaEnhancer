package com.wmods.wppenhacer.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.activities.base.BaseActivity;
import com.wmods.wppenhacer.databinding.ActivitySettingsSearchBinding;
import com.wmods.wppenhacer.search.SettingsSearchAdapter;
import com.wmods.wppenhacer.search.SettingsSearchIndex;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SettingsSearchActivity extends BaseActivity {

    private ActivitySettingsSearchBinding binding;
    private SettingsSearchAdapter adapter;
    private List<SettingsSearchIndex.Entry> allEntries;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsSearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);
        var actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        allEntries = SettingsSearchIndex.get(this);
        adapter = new SettingsSearchAdapter(this::onEntryClick);

        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter);

        updateResults("", true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.settings_search_menu, menu);

        MenuItem item = menu.findItem(R.id.action_search);
        item.expandActionView();

        SearchView searchView = (SearchView) item.getActionView();
        searchView.setQueryHint(getString(R.string.settings_search_hint));
        searchView.setIconified(false);
        searchView.requestFocus();
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                updateResults(query, false);
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                updateResults(newText, false);
                return true;
            }
        });

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateResults(@Nullable String query, boolean allowEmpty) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty() && !allowEmpty) {
            adapter.submit(Collections.emptyList());
            binding.emptyView.setVisibility(android.view.View.GONE);
            return;
        }

        String needle = q.toLowerCase(Locale.ROOT);
        var results = SettingsSearchIndex.filter(allEntries, needle, 60);
        adapter.submit(results);
        binding.emptyView.setVisibility(results.isEmpty() && !q.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void onEntryClick(SettingsSearchIndex.Entry entry) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_SEARCH_TAB_INDEX, entry.tabIndex);
        intent.putExtra(MainActivity.EXTRA_SEARCH_SCREEN_CLASS, entry.screenClassName);
        if (entry.preferenceKey != null) {
            intent.putExtra(MainActivity.EXTRA_SEARCH_PREF_KEY, entry.preferenceKey);
        }
        startActivity(intent);
        finish();
    }
}
