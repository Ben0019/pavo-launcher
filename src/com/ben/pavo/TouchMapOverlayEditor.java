package com.ben.pavo;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Transparent in-game editor shown from the Touch Mapper floating handle. */
final class TouchMapOverlayEditor extends FrameLayout {
    interface Listener {
        void onClose();
    }

    private static final int ACCENT = 0xFF8BE9FF;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xCCFFFFFF;
    private static final int CARD_TINT = 0xC80B0E14;
    private static final int GREEN = 0xFF69D98B;
    private static final int[] RIGHT_MODES = {
            RightStick.OFF, RightStick.AIM, RightStick.SWIPE, RightStick.MOUSE,
            RightStick.JOYSTICK, RightStick.MOBA
    };

    private final Context context;
    private final String target;
    private final String appLabel;
    private final TouchMapStore store;
    private final Listener listener;
    private final TouchMapEditorView editor;
    private final LinearLayout panel;
    private final LinearLayout currentList;
    private final LinearLayout paletteRow;
    private final TextView status;
    private final TextView rightChip;
    private final TextView rightHint;
    private TextView buttonsHideChip;
    private boolean buttonsHidden;
    private final List<TouchCombo> combos = new ArrayList<>();

    private TouchStick stick;
    private boolean useStick;
    private RightStick right;
    private boolean layoutEditing;
    private boolean layoutEditReturnCombo;
    private boolean comboPageVisible;
    private TextView doneButton;
    private TouchMapFabView handle;
    private LinearLayout comboPanel;
    private FrameLayout.LayoutParams comboPanelLp;
    private LinearLayout comboList;
    private LinearLayout comboPending;
    private LinearLayout comboPalette;
    private TextView comboStatus;
    private final List<Integer> pendingCombo = new ArrayList<>();
    private LinearLayout layoutTools;
    private LinearLayout layoutToolControls;
    private TextView layoutToolTitle;
    private TextView layoutOpacity;
    private TextView layoutHide;
    private int selectedCode = -1;
    private int selectedKind; // 1 target, 2 left joy, 3 right joy, 4 combo
    private int selectedJoy = -1;
    private String selectedComboKey = "";
    private boolean leftJoyHidden;
    private boolean rightJoyHidden;
    private float leftJoyAlpha = 1f;
    private float rightJoyAlpha = 1f;
    private TextView layoutResizeHint;

    TouchMapOverlayEditor(Context c, String target, String appLabel,
                          TouchMapStore store, Listener listener) {
        super(c);
        this.context = c;
        this.target = target == null ? "" : target;
        this.appLabel = appLabel == null ? target : appLabel;
        this.store = store;
        this.listener = listener;
        stick = store.stick(this.target);
        useStick = store.stickEnabled(this.target);
        int mode = store.rightMode(this.target);
        right = new RightStick(mode, store.rightZone(this.target, mode));
        combos.addAll(store.combos(this.target));
        buttonsHidden = store.buttonsHidden(this.target);
        leftJoyHidden = store.leftJoyHidden(this.target);
        rightJoyHidden = store.rightJoyHidden(this.target);
        leftJoyAlpha = store.leftJoyAlpha(this.target);
        rightJoyAlpha = store.rightJoyAlpha(this.target);

        setClickable(true);
        setFocusable(false);

        editor = new TouchMapEditorView(c);
        editor.setOverlayMode(true);
        editor.setComboEditable(false);
        editor.setButtonsHidden(buttonsHidden);
        editor.setJoystickAppearance(leftJoyHidden, leftJoyAlpha,
                rightJoyHidden, rightJoyAlpha);
        editor.setLayout(store.layout(this.target), stick, useStick, right);
        editor.setCombos(combos);
        editor.setListener(new TouchMapEditorView.Listener() {
            @Override
            public void onLayoutMoved(boolean finished) {
                save(false);
            }

            @Override
            public void onTargetSelected(TouchTarget target) {
                if (target == null) return;
                selectedKind = 1;
                selectedCode = target.code;
                selectedJoy = -1;
                selectedComboKey = "";
                updateLayoutTools();
            }

            @Override
            public void onJoystickSelected(int code) {
                selectedKind = code == TouchTarget.LEFT_JOY ? 2 : 3;
                selectedJoy = code;
                selectedCode = -1;
                selectedComboKey = "";
                updateLayoutTools();
            }

            @Override
            public void onComboSelected(TouchCombo combo) {
                if (combo == null) return;
                selectedKind = 4;
                selectedComboKey = combo.key();
                selectedCode = -1;
                selectedJoy = -1;
                updateLayoutTools();
            }
        });
        addView(editor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new GlassPanel(c, null, ACCENT, CARD_TINT, 20, true);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(14));
        ((GlassPanel) panel).setFrosted(true);
        ((GlassPanel) panel).setBackdropScreenAnchored(true);
        panel.setClickable(true);
        panel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.TOP);
        panelLp.topMargin = dp(12);
        panelLp.rightMargin = dp(12);
        addView(panel, panelLp);

        TextView header = text("⠿  TOUCH MAP", 11f, ACCENT, true);
        header.setLetterSpacing(0.12f);
        header.setPadding(dp(2), dp(2), dp(2), dp(8));
        header.setOnTouchListener(new PanelDrag(panel));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView game = text(appLabel, 15f, TEXT, true);
        game.setSingleLine(true);
        game.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(game);
        TextView hint = text("Use EDIT LAYOUT to move, resize, hide or fade buttons.",
                10f, MUTED, false);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(3), 0, dp(6));
        panel.addView(hint);

        TextView editLayout = chip("EDIT LAYOUT", ACCENT, 0x228BE9FF, 0x558BE9FF);
        editLayout.setGravity(Gravity.CENTER);
        editLayout.setPadding(dp(10), dp(8), dp(10), dp(8));
        editLayout.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                enterLayoutEdit();
            }
        });
        TextView comboOpen = chip("COMBOS  ›", GREEN, 0x2669D98B, 0x5569D98B);
        comboOpen.setGravity(Gravity.CENTER);
        comboOpen.setPadding(dp(8), dp(8), dp(8), dp(8));
        comboOpen.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                showComboPage();
            }
        });
        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(editLayout, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams comboOpenLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        comboOpenLp.leftMargin = dp(6);
        topRow.addView(comboOpen, comboOpenLp);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topLp.bottomMargin = dp(5);
        panel.addView(topRow, topLp);

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(actions);
        TextView save = chip("SAVE", GREEN, 0x2669D98B, 0x5569D98B);
        save.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                save(true);
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = chip("CLOSE", 0xFFFFC857, 0x22FFC857, 0x55FFC857);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                save(false);
                if (listener != null) listener.onClose();
            }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        closeLp.leftMargin = dp(6);
        actions.addView(close, closeLp);

        LinearLayout modeRow = new LinearLayout(c);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.topMargin = dp(9);
        panel.addView(modeRow, modeLp);
        TextView modeLabel = text("RIGHT STICK", 9f, MUTED, true);
        modeLabel.setLetterSpacing(0.12f);
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rightChip = chip("AIM", ACCENT, 0x228BE9FF, 0x558BE9FF);
        rightChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                cycleRightMode();
            }
        });
        modeRow.addView(rightChip);
        rightHint = text("", 8.5f, 0xCCFFFFFF, false);
        rightHint.setLineSpacing(dp(1), 1f);
        rightHint.setPadding(0, dp(3), 0, dp(5));
        panel.addView(rightHint);

        LinearLayout buttonsRow = new LinearLayout(c);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER_VERTICAL);
        buttonsRow.setPadding(0, dp(5), 0, 0);
        panel.addView(buttonsRow);
        TextView buttonsLabel = text("BUTTONS", 10f, MUTED, true);
        buttonsLabel.setLetterSpacing(0.12f);
        buttonsRow.addView(buttonsLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttonsHideChip = chip("HIDE ALL", ACCENT, 0x1A8BE9FF, 0x448BE9FF);
        buttonsHideChip.setPadding(dp(8), dp(4), dp(8), dp(4));
        buttonsHideChip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                buttonsHidden = !buttonsHidden;
                store.setButtonsHidden(target, buttonsHidden);
                editor.setButtonsHidden(buttonsHidden);
                updateButtonHideChip();
                updateStatus(buttonsHidden ? "●  BUTTONS HIDDEN" : "●  AUTO-SAVE");
            }
        });
        buttonsRow.addView(buttonsHideChip);
        updateButtonHideChip();

        currentList = new LinearLayout(c);
        currentList.setOrientation(LinearLayout.VERTICAL);
        currentList.setPadding(0, dp(3), 0, dp(3));
        panel.addView(currentList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView addLabel = text("ADD BUTTON", 10f, MUTED, true);
        addLabel.setLetterSpacing(0.12f);
        addLabel.setPadding(0, dp(4), 0, dp(4));
        panel.addView(addLabel);
        paletteRow = new LinearLayout(c);
        paletteRow.setOrientation(LinearLayout.VERTICAL);
        paletteRow.setPadding(0, 0, 0, dp(3));
        ScrollView paletteScroll = new ScrollView(c);
        paletteScroll.setFillViewport(false);
        paletteScroll.setVerticalScrollBarEnabled(false);
        paletteScroll.setVerticalFadingEdgeEnabled(true);
        paletteScroll.setFadingEdgeLength(dp(22));
        paletteScroll.addView(paletteRow, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams paletteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        paletteLp.topMargin = dp(2);
        panel.addView(paletteScroll, paletteLp);

        status = text("●  AUTO-SAVE", 8.5f, 0x99FFFFFF, false);
        status.setGravity(Gravity.END);
        status.setLetterSpacing(0.10f);
        status.setPadding(0, dp(9), 0, 0);
        panel.addView(status);

        comboPanel = buildComboPanel();
        comboPanel.setVisibility(GONE);
        comboPanelLp = new FrameLayout.LayoutParams(
                dp(330), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.TOP);
        comboPanelLp.topMargin = dp(12);
        comboPanelLp.rightMargin = dp(12);

        handle = new TouchMapFabView(c);
        handle.setEditing(true);
        handle.setContentDescription("Close Touch Mapper editor");
        handle.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                save(false);
                if (listener != null) listener.onClose();
            }
        });
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        handleLp.topMargin = dp(10);
        addView(handle, handleLp);
        handle.bringToFront();

        doneButton = chip("DONE", 0xFF061015, 0xFF8BE9FF, 0xFFFFFFFF);
        doneButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        doneButton.setPadding(dp(18), dp(8), dp(18), dp(8));
        doneButton.setVisibility(GONE);
        doneButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                exitLayoutEdit();
            }
        });
        FrameLayout.LayoutParams doneLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        doneLp.topMargin = dp(12);
        doneLp.leftMargin = dp(12);
        addView(doneButton, doneLp);
        doneButton.bringToFront();

        layoutTools = buildLayoutTools();
        FrameLayout.LayoutParams toolsLp = new FrameLayout.LayoutParams(
                dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        toolsLp.topMargin = dp(58);
        toolsLp.leftMargin = dp(12);
        addView(layoutTools, toolsLp);
        layoutTools.bringToFront();

        applyCardPositions();
        refreshLists();
        refreshMode();
        captureCardBackdrop();
    }

    private void applyCardPositions() {
        panel.setTranslationX(store.cardX(target));
        panel.setTranslationY(store.cardY(target));
        if (comboPanel != null) {
            comboPanel.setTranslationX(store.comboX(target));
            comboPanel.setTranslationY(store.comboY(target));
        }
    }

    private void captureCardBackdrop() {
        if (panel == null) return;
        panel.setVisibility(INVISIBLE);
        editor.setVisibility(INVISIBLE);
        if (handle != null) handle.setVisibility(INVISIBLE);
        if (comboPanel != null) comboPanel.setVisibility(GONE);
        Thread capture = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(140L);
                } catch (InterruptedException ignored) {
                }
                Backdrop captured = ScreenCaptureBackdrop.capture(context);
                final Backdrop backdrop;
                if (captured != null) {
                    android.graphics.Bitmap stronger = Blur.blur(captured.bitmap, 18);
                    if (stronger != captured.bitmap) captured.bitmap.recycle();
                    backdrop = new Backdrop(stronger,
                            captured.screenWidth, captured.screenHeight);
                } else {
                    backdrop = null;
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (backdrop != null) {
                            if (panel instanceof GlassPanel) {
                                ((GlassPanel) panel).setBackdrop(backdrop);
                            }
                            if (comboPanel instanceof GlassPanel) {
                                ((GlassPanel) comboPanel).setBackdrop(backdrop);
                            }
                            if (layoutTools instanceof GlassPanel) {
                                ((GlassPanel) layoutTools).setBackdrop(backdrop);
                            }
                        }
                        editor.setVisibility(VISIBLE);
                        panel.setVisibility(VISIBLE);
                        if (handle != null) handle.setVisibility(VISIBLE);
                    }
                });
            }
        }, "touchmap-glass-capture");
        capture.setDaemon(true);
        capture.start();
    }

    private void updateButtonHideChip() {
        if (buttonsHideChip == null) return;
        buttonsHideChip.setText(buttonsHidden ? "SHOW ALL" : "HIDE ALL");
        buttonsHideChip.setTextColor(buttonsHidden ? 0xFFFFC857 : ACCENT);
        buttonsHideChip.setBackground(Ui.rounded(
                buttonsHidden ? 0x22FFC857 : 0x1A8BE9FF, dp(9),
                buttonsHidden ? 0x55FFC857 : 0x448BE9FF, dp(1)));
    }

    private LinearLayout buildLayoutTools() {
        GlassPanel tools = new GlassPanel(context, null, ACCENT, 0xA00B0E14, 16, true);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(12), dp(10), dp(12), dp(10));
        tools.setClickable(true);
        tools.setVisibility(GONE);
        tools.setFrosted(true);
        tools.setBackdropScreenAnchored(true);

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(heading);
        layoutToolTitle = text("TAP A BUTTON TO ADJUST", 11f, ACCENT, true);
        layoutToolTitle.setLetterSpacing(0.04f);
        heading.addView(layoutToolTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        layoutOpacity = text("100%", 11f, TEXT, true);
        layoutOpacity.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        heading.addView(layoutOpacity);

        layoutToolControls = new LinearLayout(context);
        layoutToolControls.setOrientation(LinearLayout.HORIZONTAL);
        layoutToolControls.setGravity(Gravity.CENTER_VERTICAL);
        layoutToolControls.setPadding(0, dp(10), 0, 0);
        tools.addView(layoutToolControls);
        layoutHide = chip("HIDE", 0xFFFFC857, 0x22FFC857, 0x55FFC857);
        layoutHide.setPadding(dp(12), dp(8), dp(12), dp(8));
        layoutHide.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                toggleSelectedHidden();
            }
        });
        layoutToolControls.addView(layoutHide, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView minus = chip("−", ACCENT, 0x1A8BE9FF, 0x448BE9FF);
        minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        minus.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams minusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        minusLp.leftMargin = dp(8);
        layoutToolControls.addView(minus, minusLp);
        TextView plus = chip("+", ACCENT, 0x1A8BE9FF, 0x448BE9FF);
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        plus.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plusLp.leftMargin = dp(6);
        layoutToolControls.addView(plus, plusLp);
        minus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                adjustSelectedAlpha(-0.1f);
            }
        });
        plus.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                adjustSelectedAlpha(0.1f);
            }
        });

        layoutResizeHint = text("Drag a button, joystick or combo ring to resize it",
                9f, MUTED, false);
        layoutResizeHint.setPadding(0, dp(8), 0, 0);
        layoutResizeHint.setVisibility(GONE);
        tools.addView(layoutResizeHint);
        return tools;
    }

    private TouchTarget selectedTarget() {
        if (selectedKind != 1 || selectedCode < 0) return null;
        for (TouchTarget t : editor.targets()) {
            if (t.code == selectedCode) return t;
        }
        return null;
    }

    private TouchCombo selectedCombo() {
        if (selectedKind != 4 || selectedComboKey.isEmpty()) return null;
        for (TouchCombo c : editor.combos()) {
            if (c.key().equals(selectedComboKey)) return c;
        }
        return null;
    }

    private boolean selectedHidden() {
        if (selectedKind == 1) {
            TouchTarget t = selectedTarget();
            return t != null && t.hidden;
        }
        if (selectedKind == 2) return leftJoyHidden;
        if (selectedKind == 3) return rightJoyHidden;
        if (selectedKind == 4) {
            TouchCombo c = selectedCombo();
            return c != null && c.hidden;
        }
        return false;
    }

    private float selectedAlpha() {
        if (selectedKind == 1) {
            TouchTarget t = selectedTarget();
            return t == null ? 1f : t.alpha;
        }
        if (selectedKind == 2) return leftJoyAlpha;
        if (selectedKind == 3) return rightJoyAlpha;
        if (selectedKind == 4) {
            TouchCombo c = selectedCombo();
            return c == null ? 1f : c.alpha;
        }
        return 1f;
    }

    private void setSelectedHidden(boolean hidden) {
        if (selectedKind == 1) {
            TouchTarget t = selectedTarget();
            if (t == null) return;
            t.hidden = hidden;
        } else if (selectedKind == 2) {
            leftJoyHidden = hidden;
            store.saveLeftJoyAppearance(target, leftJoyHidden, leftJoyAlpha);
            editor.setJoystickAppearance(leftJoyHidden, leftJoyAlpha,
                    rightJoyHidden, rightJoyAlpha);
        } else if (selectedKind == 3) {
            rightJoyHidden = hidden;
            store.saveRightJoyAppearance(target, rightJoyHidden, rightJoyAlpha);
            editor.setJoystickAppearance(leftJoyHidden, leftJoyAlpha,
                    rightJoyHidden, rightJoyAlpha);
        } else if (selectedKind == 4) {
            TouchCombo c = selectedCombo();
            if (c == null) return;
            c.hidden = hidden;
        }
    }

    private void setSelectedAlpha(float alpha) {
        if (selectedKind == 1) {
            TouchTarget t = selectedTarget();
            if (t == null) return;
            t.alpha = alpha;
        } else if (selectedKind == 2) {
            leftJoyAlpha = alpha;
            store.saveLeftJoyAppearance(target, leftJoyHidden, leftJoyAlpha);
            editor.setJoystickAppearance(leftJoyHidden, leftJoyAlpha,
                    rightJoyHidden, rightJoyAlpha);
        } else if (selectedKind == 3) {
            rightJoyAlpha = alpha;
            store.saveRightJoyAppearance(target, rightJoyHidden, rightJoyAlpha);
            editor.setJoystickAppearance(leftJoyHidden, leftJoyAlpha,
                    rightJoyHidden, rightJoyAlpha);
        } else if (selectedKind == 4) {
            TouchCombo c = selectedCombo();
            if (c == null) return;
            c.alpha = alpha;
        }
    }

    private void toggleSelectedHidden() {
        setSelectedHidden(!selectedHidden());
        editor.invalidate();
        save(false);
        updateLayoutTools();
    }

    private void updateLayoutTools() {
        if (layoutTools == null) return;
        if (!layoutEditing) {
            layoutTools.setVisibility(GONE);
            return;
        }
        layoutTools.setVisibility(VISIBLE);
        String title = null;
        if (selectedKind == 1) {
            TouchTarget t = selectedTarget();
            if (t != null) title = shortLabel(t.code).toUpperCase() + "  •  BUTTON";
        } else if (selectedKind == 2) {
            title = "LEFT JOY";
        } else if (selectedKind == 3) {
            title = "RIGHT JOY";
        } else if (selectedKind == 4) {
            TouchCombo c = selectedCombo();
            if (c != null) title = c.label().toUpperCase();
        }
        if (title == null) {
            layoutToolTitle.setText("TAP A BUTTON TO ADJUST");
            layoutOpacity.setVisibility(GONE);
            layoutToolControls.setVisibility(GONE);
            if (layoutResizeHint != null) layoutResizeHint.setVisibility(GONE);
            return;
        }
        layoutToolTitle.setText(title);
        layoutOpacity.setVisibility(VISIBLE);
        layoutToolControls.setVisibility(VISIBLE);
        if (layoutResizeHint != null) layoutResizeHint.setVisibility(VISIBLE);
        layoutHide.setText(selectedHidden() ? "SHOW" : "HIDE");
        float alpha = selectedAlpha();
        layoutOpacity.setText(Math.round(alpha * 100f) + "%");
        layoutOpacity.setTextColor(alpha < 0.5f ? 0xFFFFC857 : TEXT);
    }

    private void adjustSelectedAlpha(float delta) {
        float alpha = TouchTarget.clampAlpha(selectedAlpha() + delta);
        setSelectedAlpha(alpha);
        editor.invalidate();
        save(false);
        updateLayoutTools();
    }

    private LinearLayout buildComboPanel() {
        LinearLayout card = new GlassPanel(context, null, GREEN, CARD_TINT, 20, true);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        ((GlassPanel) card).setFrosted(true);
        ((GlassPanel) card).setBackdropScreenAnchored(true);
        card.setClickable(true);
        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);
        TextView header = text("COMBOS", 11f, GREEN, true);
        header.setLetterSpacing(0.12f);
        header.setPadding(dp(2), dp(4), dp(2), dp(4));
        header.setOnTouchListener(new PanelDrag(card));
        top.addView(header, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView back = chip("BACK", ACCENT, 0x1A8BE9FF, 0x448BE9FF);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                backToMap();
            }
        });
        top.addView(back);

        TextView hint = text(
                "Tap the first button for a full combo press. Hold it to hand the right stick to the combo zone; move the stick, then release it to fire.",
                10f, MUTED, false);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(4), 0, dp(7));
        card.addView(hint);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(actions);
        TextView edit = chip("EDIT TARGETS", ACCENT, 0x228BE9FF, 0x558BE9FF);
        edit.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                enterLayoutEdit();
            }
        });
        actions.addView(edit, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = chip("CLOSE", 0xFFFFC857, 0x22FFC857, 0x55FFC857);
        close.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                save(false);
                if (listener != null) listener.onClose();
            }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        closeLp.leftMargin = dp(6);
        actions.addView(close, closeLp);

        TextView listLabel = text("EXISTING COMBOS", 10f, MUTED, true);
        listLabel.setLetterSpacing(0.10f);
        listLabel.setPadding(0, dp(10), 0, dp(3));
        card.addView(listLabel);
        comboList = new LinearLayout(context);
        comboList.setOrientation(LinearLayout.VERTICAL);
        card.addView(comboList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView newLabel = text("NEW COMBO", 10f, MUTED, true);
        newLabel.setLetterSpacing(0.10f);
        newLabel.setPadding(0, dp(12), 0, dp(4));
        card.addView(newLabel);

        comboPending = new LinearLayout(context);
        comboPending.setOrientation(LinearLayout.HORIZONTAL);
        comboPending.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(comboPending, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView pendingHint = text("First button is the gate; the second button names the combo.", 9.5f, MUTED, false);
        pendingHint.setPadding(0, dp(4), 0, 0);
        card.addView(pendingHint);

        TextView add = chip("ADD COMBO", GREEN, 0x2669D98B, 0x5569D98B);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(10), dp(8), dp(10), dp(8));
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                addCombo();
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = dp(6);
        card.addView(add, addLp);

        TextView allLabel = text("ALL BUTTONS", 10f, MUTED, true);
        allLabel.setLetterSpacing(0.10f);
        allLabel.setPadding(0, dp(10), 0, dp(3));
        card.addView(allLabel);
        comboPalette = new LinearLayout(context);
        comboPalette.setOrientation(LinearLayout.VERTICAL);
        ScrollView comboScroll = new ScrollView(context);
        comboScroll.setFillViewport(false);
        comboScroll.setVerticalScrollBarEnabled(false);
        comboScroll.setVerticalFadingEdgeEnabled(true);
        comboScroll.setFadingEdgeLength(dp(22));
        comboScroll.addView(comboPalette, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams comboScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        comboScrollLp.topMargin = dp(2);
        card.addView(comboScroll, comboScrollLp);

        comboStatus = text("●  AUTO-SAVE", 8.5f, 0x99FFFFFF, false);
        comboStatus.setGravity(Gravity.END);
        comboStatus.setLetterSpacing(0.10f);
        comboStatus.setPadding(0, dp(9), 0, 0);
        card.addView(comboStatus);

        refreshComboPending();
        refreshComboPalette();
        refreshComboList();
        return card;
    }

    private void showComboPage() {
        save(false);
        comboPageVisible = true;
        panel.setVisibility(View.GONE);
        if (comboPanel.getParent() == null) addView(comboPanel, comboPanelLp);
        comboPanel.setVisibility(View.VISIBLE);
        comboPanel.bringToFront();
        refreshComboList();
        refreshComboPending();
        refreshComboPalette();
    }

    private void backToMap() {
        comboPageVisible = false;
        comboPanel.setVisibility(View.GONE);
        if (comboPanel.getParent() != null) removeView(comboPanel);
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
        refreshLists();
        refreshMode();
    }

    private void refreshComboPending() {
        if (comboPending == null) return;
        comboPending.removeAllViews();
        if (pendingCombo.isEmpty()) {
            TextView empty = text("Tap two buttons below.", 9f, MUTED, false);
            comboPending.addView(empty);
            return;
        }
        StringBuilder label = new StringBuilder();
        for (int code : pendingCombo) {
            if (label.length() > 0) label.append('+');
            label.append(TouchTarget.labelFor(code));
        }
        TextView p = chip(label.toString(), GREEN, 0x2669D98B, 0x5569D98B);
        p.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Haptics.click(v);
                pendingCombo.clear();
                refreshComboPending();
                refreshComboPalette();
            }
        });
        comboPending.addView(p);
    }

    private void refreshComboPalette() {
        if (comboPalette == null) return;
        comboPalette.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < TouchCombo.BUTTONS.length; i++) {
            final int code = TouchCombo.BUTTONS[i];
            if (i % 4 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = dp(2);
                comboPalette.addView(row, rowLp);
            }
            boolean selected = pendingCombo.contains(code);
            int accent = TouchTarget.colorFor(code);
            TextView button = chip(TouchTarget.labelFor(code),
                    selected ? 0xFF061015 : accent,
                    selected ? GREEN : withAlpha(accent, 0x26),
                    selected ? GREEN : withAlpha(accent, 0x99));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            button.setPadding(dp(5), dp(8), dp(5), dp(8));
            button.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    togglePendingButton(code);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            row.addView(button, lp);
        }
    }

    private void togglePendingButton(int code) {
        if (pendingCombo.contains(code)) {
            pendingCombo.remove(Integer.valueOf(code));
        } else if (pendingCombo.size() >= 2) {
            LToast.makeText(context, "A combo uses two buttons. Remove one first.",
                    Toast.LENGTH_SHORT).show();
            return;
        } else {
            pendingCombo.add(code);
        }
        refreshComboPending();
        refreshComboPalette();
    }

    private void refreshComboList() {
        if (comboList == null) return;
        comboList.removeAllViews();
        if (combos.isEmpty()) {
            TextView empty = text("No combos yet. Create one below.", 9f, MUTED, false);
            comboList.addView(empty);
        } else {
            LinearLayout row = null;
            for (int i = 0; i < combos.size(); i++) {
                final TouchCombo c = combos.get(i);
                if (i % 2 == 0) {
                    row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.topMargin = dp(2);
                    comboList.addView(row, rowLp);
                }
                TextView chip = chip(c.label() + "  ×", 0xFFFFB3B8,
                        0x18FF8A80, 0x55FF8A80);
                chip.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Haptics.click(v);
                        removeCombo(c.key());
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.rightMargin = dp(4);
                row.addView(chip, lp);
            }
        }
        if (comboStatus != null) {
            comboStatus.setText(combos.size() + " COMBO"
                    + (combos.size() == 1 ? "" : "S") + "  ·  AUTO-SAVE");
        }
    }

    private void addCombo() {
        if (pendingCombo.size() != 2) {
            LToast.makeText(context, "Tap two buttons to make a combo",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int modifier = pendingCombo.get(0);
        int button = pendingCombo.get(1);
        for (TouchCombo c : combos) {
            if (c.modifier == modifier) {
                LToast.makeText(context, "That first button already gates a combo",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        int n = combos.size();
        TouchCombo c = new TouchCombo(modifier, button,
                0.56f + (n % 3) * 0.055f, 0.58f + ((n / 3) % 3) * 0.06f);
        combos.add(c);
        pendingCombo.clear();
        editor.setCombos(combos);
        save(false);
        refreshComboList();
        refreshComboPending();
        refreshComboPalette();
    }

    private void removeCombo(String key) {
        for (int i = combos.size() - 1; i >= 0; i--) {
            if (combos.get(i).key().equals(key)) combos.remove(i);
        }
        editor.setCombos(combos);
        save(false);
        refreshComboList();
    }

    private void enterLayoutEdit() {
        save(false);
        layoutEditing = true;
        layoutEditReturnCombo = comboPageVisible;
        editor.setComboEditable(true);
        editor.setLayoutEditing(true);
        panel.setVisibility(View.GONE);
        if (comboPanel != null) {
            comboPanel.setVisibility(View.GONE);
            if (comboPanel.getParent() != null) removeView(comboPanel);
        }
        doneButton.setVisibility(View.VISIBLE);
        doneButton.bringToFront();
        updateLayoutTools();
        if (layoutTools != null) layoutTools.bringToFront();
        invalidate();
    }

    private void exitLayoutEdit() {
        layoutEditing = false;
        editor.setComboEditable(false);
        editor.setLayoutEditing(false);
        if (layoutTools != null) layoutTools.setVisibility(GONE);
        save(false);
        doneButton.setVisibility(View.GONE);
        if (layoutEditReturnCombo && comboPageVisible) {
            panel.setVisibility(View.GONE);
            if (comboPanel.getParent() == null) addView(comboPanel, comboPanelLp);
            comboPanel.setVisibility(View.VISIBLE);
            comboPanel.bringToFront();
            refreshComboList();
        } else {
            comboPageVisible = false;
            if (comboPanel != null) {
                comboPanel.setVisibility(View.GONE);
                if (comboPanel.getParent() != null) removeView(comboPanel);
            }
            panel.setVisibility(View.VISIBLE);
            panel.bringToFront();
            refreshMode();
            refreshLists();
        }
        layoutEditReturnCombo = false;
    }

    private void refreshLists() {
        currentList.removeAllViews();
        List<TouchTarget> targets = editor.targets();
        LinearLayout row = null;
        int count = 0;
        for (TouchTarget t : targets) {
            final int code = t.code;
            if (count % 5 == 0) row = newGridRow(currentList);
            int color = t.hidden ? 0xFFA8B0BC : TouchTarget.colorFor(code);
            TextView chip = gridChip(shortLabel(code) + "  ×", color,
                    withAlpha(color, 0x28), withAlpha(color, 0x99));
            chip.setAlpha(t.hidden ? 0.62f : 1f);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    removeTarget(code);
                }
            });
            row.addView(chip, gridChipParams());
            count++;
        }
        if (useStick) {
            if (count % 5 == 0) row = newGridRow(currentList);
            TextView chip = gridChip("L JOY  ×", GREEN,
                    withAlpha(GREEN, 0x26), withAlpha(GREEN, 0x99));
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    removeLeftJoy();
                }
            });
            row.addView(chip, gridChipParams());
            count++;
        }
        if (right.mode != RightStick.OFF) {
            if (count % 5 == 0) row = newGridRow(currentList);
            TextView chip = gridChip("R JOY  ×", GREEN,
                    withAlpha(GREEN, 0x26), withAlpha(GREEN, 0x99));
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    removeRightJoy();
                }
            });
            row.addView(chip, gridChipParams());
        }

        paletteRow.removeAllViews();
        List<Integer> existing = new ArrayList<>();
        for (TouchTarget t : targets) existing.add(t.code);
        LinearLayout addRow = null;
        int added = 0;
        for (int code : TouchTarget.PALETTE) {
            if (existing.contains(code)) continue;
            if (added % 4 == 0) addRow = newGridRow(paletteRow);
            int color = TouchTarget.colorFor(code);
            TextView add = paletteChip(shortLabel(code), color,
                    withAlpha(color, 0x26), withAlpha(color, 0x99));
            add.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    addTarget(code);
                }
            });
            addRow.addView(add, paletteChipParams());
            added++;
        }
        if (!useStick) {
            if (added % 4 == 0) addRow = newGridRow(paletteRow);
            TextView add = paletteChip("L JOY", GREEN,
                    withAlpha(GREEN, 0x26), withAlpha(GREEN, 0x99));
            add.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    addLeftJoy();
                }
            });
            addRow.addView(add, paletteChipParams());
            added++;
        }
        if (right.mode == RightStick.OFF) {
            if (added % 4 == 0) addRow = newGridRow(paletteRow);
            TextView add = paletteChip("R JOY", GREEN,
                    withAlpha(GREEN, 0x26), withAlpha(GREEN, 0x99));
            add.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Haptics.click(v);
                    addRightJoy();
                }
            });
            addRow.addView(add, paletteChipParams());
            added++;
        }
        if (added == 0) {
            TextView full = text("ALL BUTTONS ADDED", 9.5f, MUTED, true);
            paletteRow.addView(full);
        }
        updateStatus("●  AUTO-SAVE");
    }

    private LinearLayout newGridRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);
        parent.addView(row, rowLp);
        return row;
    }

    private LinearLayout.LayoutParams gridChipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(4);
        return lp;
    }

    private TextView gridChip(String label, int color, int fill, int border) {
        TextView t = chip(label, color, fill, border);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        t.setPadding(dp(3), dp(5), dp(3), dp(5));
        return t;
    }

    private LinearLayout.LayoutParams paletteChipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(5);
        return lp;
    }

    private TextView paletteChip(String label, int color, int fill, int border) {
        TextView t = chip(label, color, fill, border);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        t.setPadding(dp(5), dp(8), dp(5), dp(8));
        return t;
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private String shortLabel(int code) {
        if (code == TouchTarget.R3_STICK) return "R3 STK";
        return TouchTarget.labelFor(code);
    }

    private void addLeftJoy() {
        useStick = true;
        editor.setLayout(editor.targets(), editor.stick(), true, right);
        save(false);
        refreshLists();
    }

    private void removeLeftJoy() {
        if (editor.targets().isEmpty() && right.mode == RightStick.OFF) {
            LToast.makeText(context, "Keep at least one button or an enabled joystick",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        useStick = false;
        editor.setLayout(editor.targets(), editor.stick(), false, right);
        save(false);
        refreshLists();
    }

    private void addRightJoy() {
        if (right.mode != RightStick.OFF) return;
        TouchStick zone = store.rightZone(target, RightStick.JOYSTICK);
        right = new RightStick(RightStick.JOYSTICK, zone);
        store.setRightMode(target, RightStick.JOYSTICK);
        editor.setLayout(editor.targets(), editor.stick(), useStick, right);
        save(false);
        refreshMode();
        refreshLists();
    }

    private void removeRightJoy() {
        if (editor.targets().isEmpty() && !useStick) {
            LToast.makeText(context, "Keep at least one button or an enabled joystick",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (right.mode != RightStick.OFF) {
            store.saveRightZone(target, right.mode, right.zone);
        }
        right = new RightStick(RightStick.OFF, store.rightZone(target, RightStick.OFF));
        store.setRightMode(target, RightStick.OFF);
        editor.setLayout(editor.targets(), editor.stick(), useStick, right);
        save(false);
        refreshMode();
        refreshLists();
    }

    private void addTarget(int code) {
        List<TouchTarget> list = new ArrayList<>();
        for (TouchTarget t : editor.targets()) list.add(t.copy());
        int n = list.size();
        float x = 0.62f + (n % 3) * 0.055f;
        float y = 0.52f + ((n / 3) % 3) * 0.06f;
        list.add(new TouchTarget(code, TouchTarget.labelFor(code), x, y));
        editor.setLayout(list, editor.stick(), useStick, right);
        save(false);
        refreshLists();
    }

    private void removeTarget(int code) {
        List<TouchTarget> list = new ArrayList<>();
        for (TouchTarget t : editor.targets()) {
            if (t.code != code) list.add(t.copy());
        }
        if (list.isEmpty() && !useStick && right.mode == RightStick.OFF) {
            LToast.makeText(context, "Keep at least one button or an enabled joystick",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        editor.setLayout(list, editor.stick(), useStick, right);
        save(false);
        refreshLists();
    }

    private void cycleRightMode() {
        int index = 0;
        for (int i = 0; i < RIGHT_MODES.length; i++) {
            if (RIGHT_MODES[i] == right.mode) {
                index = i;
                break;
            }
        }
        if (right.mode != RightStick.OFF) {
            store.saveRightZone(target, right.mode, right.zone);
        }
        int next = RIGHT_MODES[(index + 1) % RIGHT_MODES.length];
        TouchStick zone = store.rightZone(target, next);
        store.setRightMode(target, next);
        right = new RightStick(next, zone);
        editor.setLayout(editor.targets(), editor.stick(), useStick, right);
        save(false);
        refreshMode();
        refreshLists();
    }

    private void refreshMode() {
        if (rightChip == null) return;
        rightChip.setText(RightStick.label(right.mode));
        rightChip.setTextColor(right.mode == RightStick.OFF ? 0xFFB99CFF : ACCENT);
        rightChip.setBackground(Ui.rounded(
                right.mode == RightStick.OFF ? 0x22B99CFF : 0x228BE9FF,
                dp(10), right.mode == RightStick.OFF ? 0x55B99CFF : 0x558BE9FF, dp(1)));
        switch (right.mode) {
            case RightStick.AIM:
                rightHint.setText("360° horizontal swipe aim. Release holds the last facing.");
                break;
            case RightStick.SWIPE:
                rightHint.setText("Drags a virtual finger inside the draggable swipe zone.");
                break;
            case RightStick.MOUSE:
                rightHint.setText("Mouse pointer. R1 left-click, R2 right-click.");
                break;
            case RightStick.MOBA:
                rightHint.setText("Right-side MOBA movement stick.");
                break;
            case RightStick.JOYSTICK:
                rightHint.setText("Normal right-side analog movement stick.");
                break;
            default:
                rightHint.setText("Right stick is unused for this game.");
                break;
        }
    }

    void save(boolean show) {
        if (target.isEmpty()) return;
        stick = editor.stick();
        right = editor.rightStick();
        store.saveLayout(target, editor.targets());
        store.saveStick(target, stick);
        store.setStickEnabled(target, useStick);
        store.saveRightZone(target, right.mode, right.zone);
        store.setRightMode(target, right.mode);
        combos.clear();
        for (TouchCombo c : editor.combos()) combos.add(c.copy());
        store.saveCombos(target, combos);
        store.saveLeftJoyAppearance(target, leftJoyHidden, leftJoyAlpha);
        store.saveRightJoyAppearance(target, rightJoyHidden, rightJoyAlpha);
        if (show) {
            updateStatus("●  SAVED");
            LToast.makeText(context, "Touch map saved for " + appLabel, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStatus(String text) {
        if (status != null) status.setText(
                text == null || text.isEmpty() ? "●  AUTO-SAVE" : text);
    }

    private final class PanelDrag implements OnTouchListener {
        private final View target;
        private float downX;
        private float downY;
        private float startTx;
        private float startTy;

        PanelDrag(View target) {
            this.target = target;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startTx = target.getTranslationX();
                    startTy = target.getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float tx = startTx + e.getRawX() - downX;
                    float ty = startTy + e.getRawY() - downY;
                    int w = getResources().getDisplayMetrics().widthPixels;
                    int h = getResources().getDisplayMetrics().heightPixels;
                    float maxLeft = -(w - panel.getWidth() - dp(24));
                    tx = Math.max(maxLeft, Math.min(0f, tx));
                    ty = Math.max(-dp(240), Math.min(dp(240), ty));
                    target.setTranslationX(tx);
                    target.setTranslationY(ty);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (target == panel) {
                        store.saveCardPosition(TouchMapOverlayEditor.this.target,
                                target.getTranslationX(), target.getTranslationY());
                    } else if (target == comboPanel) {
                        store.saveComboPosition(TouchMapOverlayEditor.this.target,
                                target.getTranslationX(), target.getTranslationY());
                    }
                    return true;
            }
            return false;
        }
    }

    private TextView chip(String value, int color, int fill, int border) {
        TextView t = text(value, 9.5f, color, true);
        t.setLetterSpacing(0.05f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(6), dp(9), dp(6));
        t.setBackground(Ui.rounded(fill, dp(9), border, dp(1)));
        t.setClickable(true);
        return t;
    }

    private TextView text(String value, float size, int color, boolean medium) {
        TextView t = new LTextView(context);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private int dp(float value) {
        return Ui.dp(context, value);
    }
}
