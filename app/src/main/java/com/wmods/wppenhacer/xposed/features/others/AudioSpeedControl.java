package com.wmods.wppenhacer.xposed.features.others;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.wmods.wppenhacer.R;
import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener;
import com.wmods.wppenhacer.xposed.utils.AnimationUtil;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.ResId;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class AudioSpeedControl extends Feature {
    AtomicReference<Float> audioSpeed = new AtomicReference<>(Float.valueOf(1.0f));
    AtomicBoolean changed = new AtomicBoolean(false);

    public AudioSpeedControl(ClassLoader classLoader, XSharedPreferences xSharedPreferences) {
        super(classLoader, xSharedPreferences);
    }

    @Override
    public void doHook() {
        if (!this.prefs.getBoolean("audio_speed_control", true)) return;

        try {
            XposedBridge.hookMethod(Unobfuscator.loadPlaybackSpeed(this.classLoader), new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (changed.get()) {
                        param.args[1] = audioSpeed.get();
                    }
                }
            });
        } catch (Throwable e) {
            XposedBridge.log("AudioSpeedControl: Failed to hook loadPlaybackSpeed - " + e.getMessage());
        }

        ConversationItemListener.conversationListeners.add(new ConversationItemListener.OnConversationItemListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public void onItemBind(FMessageWpp fMessageWpp, ViewGroup root) {
                try {
                    // 1. Try to find the speed TextView
                    TextView textView = null;
                    
                    int speedBtnId = Utils.getID("playback_speed_text", "id");
                    if (speedBtnId != 0) {
                        View v = root.findViewById(speedBtnId);
                        if (v instanceof TextView) textView = (TextView) v;
                    }
                    
                    // Fallback: search by text content
                    if (textView == null) {
                        textView = findSpeedTextView(root);
                    }

                    if (textView == null) return;

                    // 2. Find or create the container
                    // We try to find a suitable parent or existing container
                    if (root.findViewWithTag("audio_speed_seekbar") != null) return; // Already added

                    // Try to finding the player container. It's usually the parent or grandparent of the speed text
                    ViewGroup voiceNoteContainer = null;
                    if (textView.getParent() instanceof ViewGroup) {
                        ViewGroup parent = (ViewGroup) textView.getParent();
                        // This might be too small, check if it has enough width/siblings
                        if (parent.getChildCount() > 1) voiceNoteContainer = parent;
                        else if (parent.getParent() instanceof ViewGroup) voiceNoteContainer = (ViewGroup) parent.getParent();
                    }
                    
                    if (voiceNoteContainer == null) {
                         int containerId = Utils.getID("voice_note_player_container", "id");
                         if (containerId == 0) containerId = Utils.getID("audio_player_container", "id");
                         if (containerId != 0) voiceNoteContainer = (ViewGroup) root.findViewById(containerId);
                    }

                    if (voiceNoteContainer == null) return; // Can't find place to inject

                    Context context = voiceNoteContainer.getContext();
                    final LinearLayout linearLayout = new LinearLayout(context);
                    linearLayout.setOrientation(LinearLayout.HORIZONTAL);
                    linearLayout.setGravity(16);
                    linearLayout.setPadding(Utils.dipToPixels(10.0f), Utils.dipToPixels(6.0f), Utils.dipToPixels(10.0f), Utils.dipToPixels(6.0f));
                    
                    Drawable bg = DesignUtils.getDrawable(ResId.drawable.audio_speed_container_bg);
                    if (bg != null) linearLayout.setBackground(bg);
                    linearLayout.setVisibility(View.GONE);

                    ImageView imageView = new ImageView(context);
                    LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(Utils.dipToPixels(20.0f), Utils.dipToPixels(20.0f));
                    imgParams.rightMargin = Utils.dipToPixels(8.0f);
                    imageView.setLayoutParams(imgParams);
                    
                    Drawable icon = DesignUtils.getDrawable(ResId.drawable.ic_audio_speed);
                    if (icon != null) {
                        imageView.setImageDrawable(DesignUtils.coloredDrawable(icon, DesignUtils.getUnSeenColor()));
                    }
                    linearLayout.addView(imageView);

                    SeekBar seekBar = new SeekBar(context);
                    seekBar.setTag("audio_speed_seekbar");
                    seekBar.setMax(50); // 0.1x to 5.1x
                    seekBar.setProgress(9); // Default 1.0x (9 + 1 = 10 * 0.1 = 1.0)
                    seekBar.setSplitTrack(false);
                    
                    Drawable thumb = DesignUtils.getDrawable(ResId.drawable.audio_speed_seekbar_thumb);
                    if (thumb != null) seekBar.setThumb(thumb);
                    
                    Drawable progress = DesignUtils.getDrawable(ResId.drawable.audio_speed_seekbar_progress);
                    if (progress != null) seekBar.setProgressDrawable(progress);
                    
                    seekBar.setPadding(Utils.dipToPixels(6.0f), Utils.dipToPixels(10.0f), Utils.dipToPixels(6.0f), Utils.dipToPixels(10.0f));
                    seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, Utils.dipToPixels(32.0f), 1.0f));
                    linearLayout.addView(seekBar);

                    final Animation slideIn = AnimationUtil.getAnimation("slide_in_bottom");
                    final Animation slideOut = AnimationUtil.getAnimation("slide_out_bottom");
                    final LinearLayout finalLayout = linearLayout;

                    if (slideOut != null) {
                        slideOut.setAnimationListener(new Animation.AnimationListener() {
                            @Override public void onAnimationStart(Animation animation) {}
                            @Override public void onAnimationRepeat(Animation animation) {}
                            @Override public void onAnimationEnd(Animation animation) {
                                finalLayout.setVisibility(View.GONE);
                            }
                        });
                    }

                    final TextView finalTextView = textView;

                    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                            float speed = (progress * 0.1f) + 0.1f;
                            audioSpeed.set(speed);
                            changed.set(true);
                            try {
                                // Triggering a click on playback speed text often refreshes the speed in WS
                                finalTextView.callOnClick();
                            } catch (Exception e) {}
                            
                            // Update text if possible
                            finalTextView.setText(String.format("%.1fx", speed));
                        }
                        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
                    });

                    textView.setOnLongClickListener(v -> {
                        if (linearLayout.getVisibility() == View.GONE) {
                            linearLayout.setVisibility(View.VISIBLE);
                            if (slideIn != null) linearLayout.startAnimation(slideIn);
                        } else {
                            if (slideOut != null) linearLayout.startAnimation(slideOut);
                            else linearLayout.setVisibility(View.GONE);
                            changed.set(false);
                        }
                        return true;
                    });

                    // Add the linear layout to the container
                    // Try to add it near the speed button if possible, otherwise at the end
                     int index = -1;
                     if (textView.getParent() == voiceNoteContainer) {
                         index = voiceNoteContainer.indexOfChild(textView) + 1;
                     }
                     
                     if (index >= 0 && index <= voiceNoteContainer.getChildCount()) {
                         voiceNoteContainer.addView(linearLayout, index);
                     } else {
                         voiceNoteContainer.addView(linearLayout);
                     }
                    
                } catch (Throwable th) {
                    XposedBridge.log("AudioSpeedControl: Error in onItemBind - " + th.getMessage());
                }
            }
        });
    }

    private TextView findSpeedTextView(View view) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            if (text.equals("1x") || text.equals("1.5x") || text.equals("2x")) {
                return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findSpeedTextView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Override
    public String getPluginName() {
        return "AudioSpeedControl";
    }
}
