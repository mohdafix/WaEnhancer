package com.wmods.wppenhacer.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.wmods.wppenhacer.App;
import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.adapter.ScheduledMessagesAdapter;
import com.wmods.wppenhacer.services.ScheduledMessageService;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessage;
import com.wmods.wppenhacer.xposed.core.db.ScheduledMessageStore;
import java.util.List;

import com.wmods.wppenhacer.activities.base.BaseActivity;

public class ScheduledMessagesListActivity extends BaseActivity implements ScheduledMessagesAdapter.OnMessageActionListener {

    private RecyclerView recyclerView;
    private ScheduledMessagesAdapter adapter;
    private ScheduledMessageStore messageStore;
    private LinearLayout layoutEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        App.changeLanguage(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scheduled_messages_list);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        messageStore = ScheduledMessageStore.getInstance(this);

        recyclerView = findViewById(R.id.recycler_scheduled);
        adapter = new ScheduledMessagesAdapter(this, this);
        recyclerView.setAdapter(adapter);

        layoutEmpty = findViewById(R.id.layout_empty);

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> {
            ScheduleMessageSheet sheet = ScheduleMessageSheet.newInstance(-1);
            sheet.setOnMessageSavedListener(this::loadMessages);
            sheet.show(getSupportFragmentManager(), "ScheduleMessageSheet");
        });

        loadMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadMessages();
    }

    private void loadMessages() {
        List<ScheduledMessage> messages = messageStore.getAllMessages();
        adapter.setMessages(messages);
        if (messages.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            layoutEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            layoutEmpty.setVisibility(View.GONE);
        }
    }

    @Override
    public void onEdit(ScheduledMessage scheduledMessage) {
        ScheduleMessageSheet sheet = ScheduleMessageSheet.newInstance(scheduledMessage.getId());
        sheet.setOnMessageSavedListener(this::loadMessages);
        sheet.show(getSupportFragmentManager(), "ScheduleMessageSheet");
    }

    @Override
    public void onDelete(ScheduledMessage scheduledMessage) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Scheduled Message")
                .setMessage("Are you sure you want to delete this scheduled message?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    messageStore.deleteMessage(scheduledMessage.getId());
                    ScheduledMessageService.startService(this);
                    loadMessages();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onToggleActive(ScheduledMessage scheduledMessage, boolean isChecked) {
        messageStore.toggleActive(scheduledMessage.getId(), isChecked);
        ScheduledMessageService.startService(this);
        loadMessages();
    }
}