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
        boolean enabled = this.prefs.getBoolean("audio_speed_control", false);
        XposedBridge.log("AudioSpeedControl: doHook called. Enabled in prefs: " + enabled);

        if (!enabled) return;

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
                XposedBridge.log("AudioSpeedControl: onItemBind called. Root: " + root.getClass().getName());
                try {
                    // 1. Try to find the speed TextView
                    TextView textView = null;
                    
                    int speedBtnId = Utils.getID("fast_playback_overlay", "id");
                    if (speedBtnId == 0) speedBtnId = Utils.getID("playback_speed_text", "id");

                    if (speedBtnId != 0) {
                        View v = root.findViewById(speedBtnId);
                        if (v instanceof TextView) textView = (TextView) v;
                    }
                    
                    // Fallback: search by text content
                    if (textView == null) {
                        textView = findSpeedTextView(root);
                    }

                    if (textView == null) {
                         // Debug: Log that we couldn't find the speed text in this view
                         // XposedBridge.log("AudioSpeedControl: No speed text found in this view.");
                         return;
                    }

                    XposedBridge.log("AudioSpeedControl: Found speed text: " + textView.getText() + " View: " + textView.getClass().getName());

                    // 2. Find or create the container
                    View existingSeekbar = root.findViewWithTag("audio_speed_seekbar");
                    if (existingSeekbar != null) {
                         // Already added: Ensure correct visibility state on re-bind
                         try {
                             View parent = (View) existingSeekbar.getParent(); // This is the LinearLayout
                             if (parent != null && parent.getVisibility() == View.VISIBLE && textView != null) {
                                 textView.setVisibility(View.GONE);
                             }
                         } catch (Exception e) {}
                         return;
                    }

                    ViewGroup voiceNoteContainer = null;
                    View refView = null; // The view to insert after

                    // Traverse up to find the main content container
                    // Hierarchy typically: textView -> VoiceNoteProfileAvatarView -> Row(LinearLayout) -> ContentWrapper(LinearLayout)
                    if (textView.getParent() instanceof ViewGroup) {
                        ViewGroup avatarView = (ViewGroup) textView.getParent();
                        if (avatarView.getParent() instanceof ViewGroup) {
                            ViewGroup playerRow = (ViewGroup) avatarView.getParent();
                            if (playerRow.getParent() instanceof LinearLayout) {
                                voiceNoteContainer = (ViewGroup) playerRow.getParent();
                                refView = playerRow;
                            }
                        }
                    }
                    
                    // Fallback to old logic if traversal failed (unlikely if hierarchy matches dump)
                    if (voiceNoteContainer == null) {
                         int containerId = Utils.getID("voice_note_player_container", "id");
                         if (containerId == 0) containerId = Utils.getID("audio_player_container", "id");
                         if (containerId != 0) voiceNoteContainer = (ViewGroup) root.findViewById(containerId);
                    }

                    if (voiceNoteContainer == null) {
                        XposedBridge.log("AudioSpeedControl: Could not find container for " + textView.getText());
                        return; 
                    }
                    
                    final TextView finalTextView = textView;
                    
                    // Sync with current text
                    float currentSpeed = 1.0f;
                    try {
                        String txt = finalTextView.getText().toString();
                        txt = txt.replace("x", "").replace("×", "").trim();
                        if (!txt.isEmpty()) {
                            currentSpeed = Float.parseFloat(txt);
                            audioSpeed.set(currentSpeed);
                        }
                    } catch (Exception e) {}

                    XposedBridge.log("AudioSpeedControl: Injecting UI into " + voiceNoteContainer.getClass().getName());

                    Context context = voiceNoteContainer.getContext();
                    final LinearLayout linearLayout = new LinearLayout(context);
                    linearLayout.setOrientation(LinearLayout.HORIZONTAL);
                    linearLayout.setGravity(16); // Center Vertical
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
                    
                    // Sync initial progress with current global speed
                    int initialProgress = (int) ((currentSpeed - 0.1f) / 0.1f);
                    if (initialProgress < 0) initialProgress = 0;
                    if (initialProgress > 50) initialProgress = 50;
                    seekBar.setProgress(initialProgress);
                    
                    seekBar.setSplitTrack(false);
                    
                    Drawable thumb = DesignUtils.getDrawable(ResId.drawable.audio_speed_seekbar_thumb);
                    if (thumb != null) seekBar.setThumb(thumb);
                    
                    Drawable progressDrawable = DesignUtils.getDrawable(ResId.drawable.audio_speed_seekbar_progress);
                    if (progressDrawable != null) seekBar.setProgressDrawable(progressDrawable);
                    
                    seekBar.setPadding(Utils.dipToPixels(6.0f), Utils.dipToPixels(10.0f), Utils.dipToPixels(6.0f), Utils.dipToPixels(10.0f));
                    seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, Utils.dipToPixels(32.0f), 1.0f));
                    linearLayout.addView(seekBar);

                    final Animation slideIn = AnimationUtil.getAnimation("slide_in_bottom");
                    final Animation slideOut = AnimationUtil.getAnimation("slide_out_bottom");

                    if (slideOut != null) {
                        slideOut.setAnimationListener(new Animation.AnimationListener() {
                            @Override public void onAnimationStart(Animation animation) {}
                            @Override public void onAnimationRepeat(Animation animation) {}
                            @Override public void onAnimationEnd(Animation animation) {
                                linearLayout.setVisibility(View.GONE);
                            }
                        });
                    }

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

                    finalTextView.setLongClickable(true);
                    finalTextView.setFocusable(true);
                    finalTextView.setClickable(true);

                    // Revert to Toggle Mode
                    finalTextView.setOnLongClickListener(v -> {
                        XposedBridge.log("AudioSpeedControl: Long click detected!");
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
                    
                    finalTextView.requestLayout();

                    // Add the linear layout to the container
                    int index = -1;
                    if (refView != null) {
                         index = voiceNoteContainer.indexOfChild(refView) + 1;
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
            CharSequence txt = ((TextView) view).getText();
            if (txt != null) {
                String text = txt.toString();
                // Check for standard speed strings or format like "1.5x"
                // Added check for multiplication sign '×' which is used in newer WA versions
                if (text.equals("1x") || text.equals("1.5x") || text.equals("2x") || 
                    text.equals("1×") || text.equals("1.5×") || text.equals("2×") ||
                    (text.endsWith("x") && text.length() < 5 && Character.isDigit(text.charAt(0))) ||
                    (text.endsWith("×") && text.length() < 5 && Character.isDigit(text.charAt(0)))) {
                    return (TextView) view;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findSpeedTextView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null; // dumpViewHierarchy method was here but removed
    }

    @Override
    public String getPluginName() {
        return "AudioSpeedControl";
    }
}
