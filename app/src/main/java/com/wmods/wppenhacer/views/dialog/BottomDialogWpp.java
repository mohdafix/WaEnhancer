package com.wmods.wppenhacer.views.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.utils.Utils;

public class BottomDialogWpp {

    private final Dialog dialog;
    private Activity mActivity;
    private boolean blurEnabled = false;

    public BottomDialogWpp(@NonNull Dialog dialog) {
        this.dialog = dialog;
        Context context = dialog.getContext();
        if (context instanceof Activity) {
            this.mActivity = (Activity) context;
        } else if (context instanceof android.content.ContextWrapper) {
            Context baseContext = ((android.content.ContextWrapper) context).getBaseContext();
            if (baseContext instanceof Activity) {
                this.mActivity = (Activity) baseContext;
            }
        }
    }

    public void dismissDialog() {
        dialog.dismiss();
    }

    public void showDialog() {
        Activity activity = mActivity;
        if (activity == null) activity = dialog.getOwnerActivity();
        
        if (blurEnabled && activity != null) {
            final var finalActivity = activity;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    activity.getWindow().getDecorView().setRenderEffect(RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP));
                } catch (Exception ignored) {}
            } else {
                try {
                    View decorView = activity.getWindow().getDecorView();
                    Bitmap bitmap = Bitmap.createBitmap(decorView.getWidth(), decorView.getHeight(), Bitmap.Config.ARGB_8888);
                    decorView.draw(new Canvas(bitmap));
                    Bitmap blurred = Utils.blurBitmap(finalActivity, bitmap, 25f);
                    dialog.getWindow().setBackgroundDrawable(new BitmapDrawable(finalActivity.getResources(), blurred));
                } catch (Exception ignored) {}
            }

            dialog.setOnDismissListener(d -> {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    try {
                        finalActivity.getWindow().getDecorView().setRenderEffect(null);
                    } catch (Exception ignored) {}
                }
            });
        }

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(null);
            dialog.getWindow().setDimAmount(0);
            var view = dialog.getWindow().getDecorView();
            view.findViewById(Utils.getID("design_bottom_sheet", "id")).setBackgroundColor(Color.TRANSPARENT);
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }
    }

    public void setContentView(View view) {
        dialog.setContentView(view);
    }


    public void setCanceledOnTouchOutside(boolean b) {
        dialog.setCanceledOnTouchOutside(b);
    }

    public void setBlur(boolean enabled) {
        this.blurEnabled = enabled;
    }
}
