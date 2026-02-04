package com.wmods.wppenhacer.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
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
import java.util.concurrent.CompletableFuture;

public class SettingsSearchActivity extends BaseActivity {

    private ActivitySettingsSearchBinding binding;
    private SettingsSearchAdapter adapter;
    private volatile List<SettingsSearchIndex.Entry> allEntries;
    @Nullable
    private MenuItem searchMenuItem;
    @Nullable
    private SearchView searchView;
    private OnBackPressedCallback mBackCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsSearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (searchMenuItem != null && searchMenuItem.isActionViewExpanded()) {
                    searchMenuItem.collapseActionView();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, mBackCallback);

        setSupportActionBar(binding.toolbar);
        var actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        // Initialize adapter immediately with empty/null listener first or handle clicks
        adapter = new SettingsSearchAdapter(this::onEntryClick);

        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter);

        // Load index asynchronously
        CompletableFuture.supplyAsync(() -> SettingsSearchIndex.get(getApplicationContext()))
                .thenAcceptAsync(entries -> {
                    allEntries = entries;
                    // Apply filter if query already exists (e.g. from state restoration or fast typing)
                    if (searchView != null) {
                        updateResults(searchView.getQuery().toString(), true);
                    } else {
                        updateResults("", true);
                    }
                }, getMainExecutor());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Use the existing menu resource which contains action_search
        getMenuInflater().inflate(R.menu.settings_search_menu, menu);

        searchMenuItem = menu.findItem(R.id.action_search);
        if (searchMenuItem != null) {
            searchMenuItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(MenuItem item) {
                    mBackCallback.setEnabled(true);
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(MenuItem item) {
                    mBackCallback.setEnabled(false);
                    return true;
                }
            });
            searchMenuItem.expandActionView();
            searchView = (SearchView) searchMenuItem.getActionView();
            
            if (searchView != null) {
                searchView.setQueryHint(getString(R.string.settings_search_hint));
                searchView.setIconified(false);
                searchView.requestFocus();
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        return false;
                    }

                    @Override
                    public boolean onQueryTextChange(String newText) {
                        updateResults(newText, false);
                        return true;
                    }
                });
            }
        }
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
        // Safe check if index is not loaded yet
        if (allEntries == null) {
            return;
        }

        String q = query == null ? "" : query.trim();
        if (q.isEmpty() && !allowEmpty) {
            adapter.submit(Collections.emptyList());
            binding.emptyView.setVisibility(View.GONE);
            return;
        }

        String needle = q.toLowerCase(Locale.ROOT);
        var results = SettingsSearchIndex.filter(allEntries, needle, 60);
        adapter.submit(results);
        binding.emptyView.setVisibility(results.isEmpty() && !q.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onEntryClick(SettingsSearchIndex.Entry entry) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); // 603979776
        intent.putExtra(MainActivity.EXTRA_SEARCH_TAB_INDEX, entry.tabIndex);
        intent.putExtra(MainActivity.EXTRA_SEARCH_SCREEN_CLASS, entry.screenClassName);
        if (entry.preferenceKey != null) {
            intent.putExtra(MainActivity.EXTRA_SEARCH_PREF_KEY, entry.preferenceKey);
        }
        startActivity(intent);
        finish();
    }
}
