package com.wmods.wppenhacer.xposed.features.others;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.wmods.wppenhacer.xposed.core.Feature;
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp;
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener;
import com.wmods.wppenhacer.xposed.utils.AnimationUtil;
import com.wmods.wppenhacer.xposed.utils.DesignUtils;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import org.luckypray.dexkit.query.enums.StringMatchType;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class AudioSpeedControl extends Feature {

    private static final String SPEED_TAG = "audio_speed_seekbar";
    
    private final AtomicReference<Float> audioSpeed = new AtomicReference<>(1.0f);
    private final AtomicBoolean changed = new AtomicBoolean(false);

    public AudioSpeedControl(ClassLoader loader, XSharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Exception {
        if (!prefs.getBoolean("audio_speed_control", true)) return;

        // Hook playback speed setter
        XposedBridge.hookMethod(
            Unobfuscator.loadPlaybackSpeed(classLoader),
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (changed.get()) {
                        param.args[1] = audioSpeed.get();
                    }
                }
            }
        );

        // Hook audio duration display to show custom speed
        try {
            Class<?> audioPlayerClass = Unobfuscator.findFirstClassUsingName(
                classLoader, 
                StringMatchType.EndsWith, 
                "AudioPlayer"
            );
            
            if (audioPlayerClass == null) {
                XposedBridge.log("AudioSpeedControl: AudioPlayer class not found, UI will not be added");
                return;
            }
            
            Method[] updateMethods = ReflectionUtils.findAllMethodsUsingFilter(
                audioPlayerClass,
                method -> method.getParameterCount() == 4 
                    && method.getParameterTypes()[0] == Integer.TYPE 
                    && method.getReturnType().equals(Void.TYPE)
            );

            if (updateMethods.length > 0) {
                XposedBridge.hookMethod(updateMethods[updateMethods.length - 1], new XC_MethodHook() {
                @Override
                @SuppressLint("SetTextI18n")
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!changed.get()) return;

                    TextView durationText = ((View) param.thisObject)
                        .findViewById(Utils.getID("duration", "id"));
                    
                    if (durationText != null) {
                        String speedStr = String.format("%.1f", audioSpeed.get())
                            .replace(".0", "")
                            .replace(",", ".");
                        durationText.setText(speedStr + "x");
                    }
                }
            });
        }
        } catch (Exception e) {
            XposedBridge.log("AudioSpeedControl: Failed to hook AudioPlayer - " + e.getMessage());
            // Continue without the duration display hook
        }

        // Add UI controls to conversation items
        ConversationItemListener.conversationListeners.add(new ConversationItemListener.OnConversationItemListener() {
            @Override
            @SuppressLint("ClickableViewAccessibility")
            public void onItemBind(FMessageWpp fMessage, ViewGroup viewGroup) {
                try {
                    // Only add to voice messages
                    if (viewGroup.findViewWithTag(SPEED_TAG) != null) return;
                    if (viewGroup.findViewById(Utils.getID("voice_note_btn", "id")) == null) return;

                    TextView durationText = viewGroup.findViewById(Utils.getID("duration", "id"));
                    if (durationText == null) return;

                    AtomicBoolean isVisible = new AtomicBoolean(false);
                    ViewGroup messageContainer = viewGroup.findViewById(Utils.getID("message_container", "id"));
                    Context context = messageContainer.getContext();

                    // Create speed control container
                    LinearLayout speedContainer = createSpeedControl(context, durationText, isVisible);
                    
                    // Insert after audio player
                    ViewGroup audioParent = (ViewGroup) viewGroup
                        .findViewById(Utils.getID("audio_player", "id"))
                        .getParent();
                    int insertIndex = messageContainer.indexOfChild(audioParent) + 1;
                    messageContainer.addView(speedContainer, insertIndex);

                } catch (Throwable e) {
                    logDebug(e);
                }
            }
        });
    }

    private LinearLayout createSpeedControl(
        Context context, 
        TextView durationText,
        AtomicBoolean isVisible
    ) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(android.view.Gravity.CENTER_VERTICAL);
        container.setPadding(
            Utils.dipToPixels(10),
            Utils.dipToPixels(6),
            Utils.dipToPixels(10),
            Utils.dipToPixels(6)
        );
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#33000000"));
        bg.setCornerRadius(Utils.dipToPixels(8));
        container.setBackground(bg);
        container.setVisibility(View.GONE);

        // Speed seekbar (no icon to keep it simple)
        SeekBar seekBar = new SeekBar(context);
        seekBar.setTag(SPEED_TAG);
        seekBar.setMax(50); // 0.1x to 5.1x (50 * 0.1 + 0.1)
        seekBar.setProgress(9); // Default 1.0x (9 * 0.1 + 0.1 = 1.0)
        seekBar.setPadding(
            Utils.dipToPixels(6),
            Utils.dipToPixels(10),
            Utils.dipToPixels(6),
            Utils.dipToPixels(10)
        );
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            Utils.dipToPixels(32),
            1.0f
        ));
        container.addView(seekBar);

        // Animations
        Animation slideIn = AnimationUtil.getAnimation("slide_in_bottom");
        Animation slideOut = AnimationUtil.getAnimation("slide_out_bottom");
        
        if (slideOut != null) {
            slideOut.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    container.setVisibility(View.GONE);
                }
                @Override public void onAnimationStart(Animation animation) {}
                @Override public void onAnimationRepeat(Animation animation) {}
            });
        }

        // SeekBar listener
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = (progress * 0.1f) + 0.1f;
                audioSpeed.set(speed);
                changed.set(true);
                
                try {
                    durationText.callOnClick();
                } catch (Exception e) {
                    logDebug(e);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Touch listener on voice note button to reset/hide
        View voiceNoteBtn = ((View) durationText.getParent().getParent())
            .findViewById(Utils.getID("voice_note_play_btn", "id"));
        
        if (voiceNoteBtn != null) {
            voiceNoteBtn.setOnTouchListener((v, event) -> {
                if (container.getVisibility() == View.GONE) {
                    changed.set(false);
                } else {
                    audioSpeed.set((seekBar.getProgress() * 0.1f) + 0.1f);
                    changed.set(true);
                }
                return false;
            });
        }

        // Long click on duration to toggle speed control
        durationText.setOnLongClickListener(v -> {
            if (container.getVisibility() == View.GONE) {
                container.clearAnimation();
                container.setVisibility(View.VISIBLE);
                if (slideIn != null) {
                    container.startAnimation(slideIn);
                }
                isVisible.set(true);
            } else {
                changed.set(false);
                isVisible.set(false);
                container.clearAnimation();
                if (slideOut != null) {
                    container.startAnimation(slideOut);
                } else {
                    container.setVisibility(View.GONE);
                }
            }
            return true;
        });

        return container;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Audio Speed Control";
    }
}
