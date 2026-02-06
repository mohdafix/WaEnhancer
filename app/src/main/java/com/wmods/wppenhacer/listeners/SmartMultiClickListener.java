package com.wmods.wppenhacer.listeners;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

public abstract class SmartMultiClickListener implements View.OnClickListener {

    private final View.OnClickListener originalListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long delay;
    private int clicks = 0;
    private boolean isBusy = false;

    public SmartMultiClickListener(View.OnClickListener originalListener, long delay) {
        this.originalListener = originalListener;
        this.delay = delay;
    }

    @Override
    public void onClick(View v) {
        if (isBusy) return;

        clicks++;
        if (clicks == 1) {
            handler.postDelayed(() -> {
                if (clicks == 1) {
                    if (originalListener != null) {
                        try {
                            isBusy = true;
                            originalListener.onClick(v);
                        } finally {
                            isBusy = false;
                        }
                    }
                }
                clicks = 0;
            }, delay);
        } else if (clicks == 2) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                if (clicks == 2) {
                    onDoubleClick(v);
                    clicks = 0;
                }
            }, delay);
        } else if (clicks == 3) {
            handler.removeCallbacksAndMessages(null);
            onTripleClick(v);
            clicks = 0;
        }
    }

    public abstract void onDoubleClick(View v);

    public void onTripleClick(View v) {
        // Optional override
    }
}
