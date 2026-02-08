package com.wmods.wppenhacer.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.wmods.wppenhacer.App;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.adapter.AutoReplyRulesAdapter;
import com.wmods.wppenhacer.xposed.core.db.AutoReplyDatabase;

import java.util.List;

import com.wmods.wppenhacer.activities.base.BaseActivity;

public class AutoReplyRulesActivity extends BaseActivity implements AutoReplyRulesAdapter.OnRuleActionListener {

    private RecyclerView recyclerView;
    private AutoReplyRulesAdapter adapter;
    private AutoReplyDatabase database;
    private LinearLayout layoutEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        App.changeLanguage(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_reply_rules);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.auto_reply_manage_rules);
        }
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        database = AutoReplyDatabase.getInstance();

        recyclerView = findViewById(R.id.recycler_rules);
        adapter = new AutoReplyRulesAdapter(this, this);
        recyclerView.setAdapter(adapter);

        layoutEmpty = findViewById(R.id.layout_empty);

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> {
            AutoReplyRuleSheet sheet = AutoReplyRuleSheet.newInstance(-1);
            sheet.setOnRuleSavedListener(this::loadRules);
            sheet.show(getSupportFragmentManager(), "AutoReplyRuleSheet");
        });

        loadRules();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRules();
    }

    private void loadRules() {
        List<AutoReplyDatabase.AutoReplyRule> rules = database.getAllRules();
        adapter.setRules(rules);
        if (rules.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            layoutEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            layoutEmpty.setVisibility(View.GONE);
        }
    }

    @Override
    public void onEdit(AutoReplyDatabase.AutoReplyRule rule) {
        AutoReplyRuleSheet sheet = AutoReplyRuleSheet.newInstance(rule.id);
        sheet.setOnRuleSavedListener(this::loadRules);
        sheet.show(getSupportFragmentManager(), "AutoReplyRuleSheet");
    }

    @Override
    public void onDelete(AutoReplyDatabase.AutoReplyRule rule) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.auto_reply_delete_rule)
                .setMessage("Are you sure you want to delete this auto-reply rule?")
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    database.deleteRule(rule.id);
                    loadRules();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onToggleActive(AutoReplyDatabase.AutoReplyRule rule, boolean isChecked) {
        database.setRuleEnabled(rule.id, isChecked);
        loadRules();
    }
}
