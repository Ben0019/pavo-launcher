package com.ben.pavo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Full-card editor for placing controller buttons over a game screen. */
final class TouchMapEditorView extends View {
    interface Listener {
        void onLayoutMoved(boolean finished);
        default void onTargetSelected(TouchTarget target) {}
        default void onJoystickSelected(int code) {}
        default void onComboSelected(TouchCombo combo) {}
    }

    private static final int ACCENT = 0xFF8BE9FF;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final JoystickArt joystick;
    private final RectF screenRect = new RectF();
    private final List<TouchTarget> targets = new ArrayList<>();
    private final List<TouchCombo> combos = new ArrayList<>();
    private final TouchStick stick = TouchStick.defaults();
    private boolean stickEnabled;
    private RightStick right = RightStick.defaults();
    private boolean overlayMode;
    private Listener listener;
    private TouchTarget dragged;
    private TouchTarget resizing;
    private TouchTarget selected;
    private TouchCombo draggedCombo;
    private TouchCombo resizingCombo;
    private TouchCombo selectedCombo;
    private int selectedJoy = -1;
    private boolean resizingStick;
    private boolean resizingRight;
    private float leftJoyAlpha = 1f;
    private float rightJoyAlpha = 1f;
    private boolean leftJoyHidden;
    private boolean rightJoyHidden;
    private boolean comboEditable;
    private boolean layoutEditing;
    private boolean buttonsHidden;
    private boolean draggingStick;
    private boolean draggingRight;
    private float downX;
    private float downY;

    TouchMapEditorView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(true);
        bg.setColor(0x33000000);
        screen.setColor(0xFF0A1118);
        screen.setStyle(Paint.Style.FILL);
        grid.setColor(0x14FFFFFF);
        grid.setStrokeWidth(Ui.dp(context, 1));
        fill.setColor(0x3345D7FF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(context, 1.6f));
        ring.setColor(0xCC8BE9FF);
        label.setColor(0xF2FFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextSize(Ui.dp(context, 11));
        hint.setColor(0x77FFFFFF);
        hint.setTextSize(Ui.dp(context, 9));
        hint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        guide.setColor(0x33FFFFFF);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(Ui.dp(context, 1));
        joystick = new JoystickArt(context);
    }

    void setLayout(List<TouchTarget> next, TouchStick nextStick, boolean useStick) {
        setLayout(next, nextStick, useStick, right);
    }

    void setLayout(List<TouchTarget> next, TouchStick nextStick, boolean useStick,
                   RightStick nextRight) {
        List<TouchTarget> copy = new ArrayList<>();
        if (next != null) {
            for (TouchTarget t : next) copy.add(t.copy());
        }
        targets.clear();
        targets.addAll(copy);
        TouchStick s = nextStick == null ? TouchStick.defaults() : nextStick;
        stick.set(s.x, s.y);
        stick.radius = s.radius;
        stickEnabled = useStick;
        right = nextRight == null ? RightStick.defaults() : nextRight.copy();
        invalidate();
    }

    void setTargets(List<TouchTarget> next) {
        setLayout(next, stick, stickEnabled, right);
    }

    List<TouchTarget> targets() {
        return targets;
    }

    TouchStick stick() {
        return stick.copy();
    }

    RightStick rightStick() {
        return right.copy();
    }

    void setCombos(List<TouchCombo> next) {
        combos.clear();
        if (next != null) {
            for (TouchCombo c : next) combos.add(c.copy());
        }
        invalidate();
    }

    List<TouchCombo> combos() {
        return combos;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setOverlayMode(boolean overlay) {
        overlayMode = overlay;
        computeScreenRect();
        invalidate();
    }

    void setComboEditable(boolean editable) {
        comboEditable = editable;
        if (!editable) draggedCombo = null;
        invalidate();
    }

    void setButtonsHidden(boolean hidden) {
        buttonsHidden = hidden;
        invalidate();
    }

    void setLayoutEditing(boolean editing) {
        layoutEditing = editing;
        if (!editing) {
            resizing = null;
            selected = null;
            selectedCombo = null;
            selectedJoy = -1;
        }
        invalidate();
    }

    void setJoystickAppearance(boolean leftHidden, float leftAlpha,
                               boolean rightHidden, float rightAlpha) {
        leftJoyHidden = leftHidden;
        leftJoyAlpha = leftAlpha;
        rightJoyHidden = rightHidden;
        rightJoyAlpha = rightAlpha;
        invalidate();
    }

    TouchTarget selectedTarget() {
        return selected;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        computeScreenRect();
        if (buttonsHidden && !layoutEditing) return;
        if (!overlayMode) {
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()),
                    Ui.dp(getContext(), 18), Ui.dp(getContext(), 18), bg);
            canvas.drawRoundRect(screenRect, Ui.dp(getContext(), 12),
                    Ui.dp(getContext(), 12), screen);
            canvas.drawRoundRect(screenRect, Ui.dp(getContext(), 12),
                    Ui.dp(getContext(), 12), guide);

            // Sparse 16:9 alignment grid.
            for (int i = 1; i < 8; i++) {
                float x = screenRect.left + screenRect.width() * i / 8f;
                canvas.drawLine(x, screenRect.top, x, screenRect.bottom, grid);
            }
            for (int i = 1; i < 5; i++) {
                float y = screenRect.top + screenRect.height() * i / 5f;
                canvas.drawLine(screenRect.left, y, screenRect.right, y, grid);
            }
            canvas.drawText("GAME SCREEN · DRAG THE BUTTONS",
                    screenRect.left + Ui.dp(getContext(), 10),
                    screenRect.top + Ui.dp(getContext(), 17), hint);
        }

        if (stickEnabled) {
            drawStick(canvas);
        }
        drawRightStick(canvas);
        TouchTarget r3 = null;
        for (TouchTarget t : targets) {
            if (t.code == TouchTarget.R3_STICK) {
                r3 = t;
                continue;
            }
            if (stickEnabled && isDpad(t.code)) continue;
            if (buttonsHidden && !layoutEditing) continue;
            float radius = t.radius * screenRect.height();
            float cx = screenRect.left + t.x * screenRect.width();
            float cy = screenRect.top + t.y * screenRect.height();
            boolean isSelected = t == selected;
            int color = t.code >= TouchTarget.DPAD_UP && t.code < 560
                    ? 0xFFFFC857 : TouchTarget.colorFor(t.code);
            int a = Math.max(0, Math.min(255, Math.round(t.alpha * 255f)));
            if (t.hidden) a = Math.max(36, a / 3);
            fill.setColor(withAlpha(color, Math.round(a * 0.16f)));
            ring.setColor(withAlpha(color, t.hidden ? Math.max(80, a) : a));
            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, radius, fill);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(getContext(), 1.8f));
            if (t.hidden) {
                ring.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{Ui.dp(getContext(), 4), Ui.dp(getContext(), 4)}, 0));
            } else {
                ring.setPathEffect(null);
            }
            canvas.drawCircle(cx, cy, radius, ring);
            ring.setPathEffect(null);
            if (isSelected) {
                ring.setColor(0xBFFFFFFF);
                canvas.drawCircle(cx, cy, radius * 1.25f, ring);
            }
            label.setTextSize(Math.max(Ui.dp(getContext(), 9), radius * 0.32f));
            label.setColor(withAlpha(0xFFFFFFFF, Math.max(110, a)));
            Paint.FontMetrics fm = label.getFontMetrics();
            canvas.drawText(t.label, cx, cy - (fm.ascent + fm.descent) / 2f, label);
            if (layoutEditing) {
                float hx = cx + radius * 0.92f;
                float hy = cy + radius * 0.92f;
                fill.setColor(0xFFFFFFFF);
                canvas.drawCircle(hx, hy, Ui.dp(getContext(), 5), fill);
                ring.setColor(0xCC061015);
                canvas.drawCircle(hx, hy, Ui.dp(getContext(), 5), ring);
            }
        }
        if (r3 != null && !r3.hidden && (!rightJoyHidden || layoutEditing)) {
            float rr = (right.mode == RightStick.OFF ? 0.16f : right.zone.radius)
                    * screenRect.height();
            float alpha = Math.min(r3.alpha, rightJoyAlpha)
                    * (rightJoyHidden ? 0.24f : 1f);
            joystick.draw(canvas, screenRect.left + r3.x * screenRect.width(),
                    screenRect.top + r3.y * screenRect.height(), rr,
                    0xFFFFC857, alpha, "R3 STICK", selectedJoy == TouchTarget.RIGHT_JOY);
        }
        for (TouchCombo c : combos) {
            if (c.hidden && !layoutEditing) continue;
            float ccx = screenRect.left + c.x * screenRect.width();
            float ccy = screenRect.top + c.y * screenRect.height();
            float cr = c.radius * screenRect.height();
            float alpha = c.alpha * (c.hidden ? 0.24f : 1f);
            joystick.draw(canvas, ccx, ccy, cr, 0xFF69D98B, alpha,
                    c.label(), c == selectedCombo || c == draggedCombo);
            if (layoutEditing) drawResizeHandle(canvas, ccx, ccy, cr);
        }
    }

    private float stickRadiusPx() {
        return stick.radius * screenRect.height();
    }

    private float rightRadiusPx() {
        if (right.mode == RightStick.AIM) {
            return right.zone.radius * screenRect.width();
        }
        return right.zone.radius * screenRect.height();
    }

    private void drawRightStick(Canvas canvas) {
        if (right.mode == RightStick.OFF) return;
        if (rightJoyHidden && !layoutEditing) return;
        if (right.mode == RightStick.MOUSE) {
            hint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("MOUSE · R1 CLICK · R2 RIGHT CLICK",
                    screenRect.right - Ui.dp(getContext(), 10),
                    screenRect.top + Ui.dp(getContext(), 18), hint);
            hint.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float cx = screenRect.left + right.zone.x * screenRect.width();
        float cy = screenRect.top + right.zone.y * screenRect.height();
        float r = rightRadiusPx();
        if (right.mode == RightStick.AIM) {
            joystick.draw(canvas, cx, cy, r, 0xFF8BE9FF,
                    0.92f * rightJoyAlpha * (rightJoyHidden ? 0.24f : 1f),
                    "AIM SWIPE", selectedJoy == TouchTarget.RIGHT_JOY);
            float half = right.zone.radius * screenRect.width();
            int arm = Ui.dp(getContext(), 8);
            ring.setColor(draggingRight ? 0xCCFFFFFF : 0xAA8BE9FF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(getContext(), 1.6f));
            canvas.drawLine(cx - half, cy, cx + half, cy, ring);
            canvas.drawLine(cx - half, cy, cx - half + arm, cy - arm, ring);
            canvas.drawLine(cx - half, cy, cx - half + arm, cy + arm, ring);
            canvas.drawLine(cx + half, cy, cx + half - arm, cy - arm, ring);
            canvas.drawLine(cx + half, cy, cx + half - arm, cy + arm, ring);
            return;
        }
        int color = right.mode == RightStick.MOBA ? 0xFFB99CFF
                : (right.mode == RightStick.JOYSTICK ? 0xFF8BE9FF : 0xFFFFC857);
        String title = right.mode == RightStick.MOBA ? "MOBA STICK"
                : (right.mode == RightStick.JOYSTICK ? "RIGHT STICK" : "SWIPE");
        float alpha = rightJoyAlpha * (rightJoyHidden ? 0.24f : 1f);
        joystick.draw(canvas, cx, cy, r, color,
                draggingRight || resizingRight ? 1f : 0.92f * alpha,
                title, selectedJoy == TouchTarget.RIGHT_JOY
                        || draggingRight || resizingRight);
        if (layoutEditing) drawResizeHandle(canvas, cx, cy, r);
    }

    private void drawStick(Canvas canvas) {
        float cx = screenRect.left + stick.x * screenRect.width();
        float cy = screenRect.top + stick.y * screenRect.height();
        joystick.draw(canvas, cx, cy, stickRadiusPx(), 0xFF7BE6B0,
                draggingStick ? 1f : 0.88f, "LEFT STICK", draggingStick);
    }

    private void drawResizeHandle(Canvas canvas, float cx, float cy, float r) {
        float hx = cx + r * 0.92f;
        float hy = cy + r * 0.92f;
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        canvas.drawCircle(hx, hy, Ui.dp(getContext(), 5), fill);
        ring.setColor(0xCC061015);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(getContext(), 1.2f));
        canvas.drawCircle(hx, hy, Ui.dp(getContext(), 5), ring);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private static boolean isDpad(int code) {
        return code == TouchTarget.DPAD_UP || code == TouchTarget.DPAD_DOWN
                || code == TouchTarget.DPAD_LEFT || code == TouchTarget.DPAD_RIGHT;
    }

    private TouchCombo hitCombo(float x, float y) {
        if (!comboEditable) return null;
        for (TouchCombo c : combos) {
            float radius = c.radius * screenRect.height();
            float cx = screenRect.left + c.x * screenRect.width();
            float cy = screenRect.top + c.y * screenRect.height();
            float dx = x - cx;
            float dy = y - cy;
            float reach = layoutEditing ? radius * 1.55f
                    : Math.min(screenRect.width(), screenRect.height()) * 0.07f;
            if (Math.hypot(dx, dy) <= Math.max(Ui.dp(getContext(), 38), reach)) return c;
        }
        return null;
    }

    private void computeScreenRect() {
        if (overlayMode) {
            screenRect.set(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight()));
            return;
        }
        float pad = Ui.dp(getContext(), 8);
        float availW = Math.max(1, getWidth() - pad * 2);
        float availH = Math.max(1, getHeight() - pad * 2);
        float w = availW;
        float h = w * 9f / 16f;
        if (h > availH) {
            h = availH;
            w = h * 16f / 9f;
        }
        float left = (getWidth() - w) / 2f;
        float top = (getHeight() - h) / 2f;
        screenRect.set(left, top, left + w, top + h);
    }

    private TouchTarget hit(float x, float y) {
        TouchTarget best = null;
        float bestDist = Float.MAX_VALUE;
        TouchTarget r3 = null;
        for (TouchTarget t : targets) {
            if (t.code == TouchTarget.R3_STICK) {
                r3 = t;
                continue;
            }
            if (stickEnabled && isDpad(t.code)) continue;
            if (buttonsHidden && !layoutEditing) continue;
            float radius = t.radius * screenRect.height();
            float cx = screenRect.left + t.x * screenRect.width();
            float cy = screenRect.top + t.y * screenRect.height();
            float dx = x - cx;
            float dy = y - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float reach = radius * (layoutEditing ? 1.55f : 1.15f);
            if (d < bestDist && d <= Math.max(Ui.dp(getContext(), 38), reach)) {
                bestDist = d;
                best = t;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (screenRect.width() <= 0) computeScreenRect();
                if (buttonsHidden && !layoutEditing) return false;
                draggingStick = false;
                draggingRight = false;
                draggedCombo = null;
                resizing = null;
                resizingCombo = null;
                resizingStick = false;
                resizingRight = false;
                if (stickEnabled) {
                    float cx = screenRect.left + stick.x * screenRect.width();
                    float cy = screenRect.top + stick.y * screenRect.height();
                    float dx = event.getX() - cx;
                    float dy = event.getY() - cy;
                    float dist = (float) Math.hypot(dx, dy);
                    float stickReach = stickRadiusPx() * (layoutEditing ? 1.55f : 1f);
                    if (dist <= Math.max(Ui.dp(getContext(), 46), stickReach)) {
                        if (layoutEditing) {
                            selectedJoy = TouchTarget.LEFT_JOY;
                            selectedCombo = null;
                            selected = null;
                            if (listener != null) {
                                listener.onJoystickSelected(TouchTarget.LEFT_JOY);
                            }
                        }
                        if (layoutEditing && dist > stickRadiusPx() * 0.64f) {
                            resizingStick = true;
                        } else {
                            draggingStick = true;
                        }
                        dragged = null;
                        downX = event.getX();
                        downY = event.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        Haptics.tick(this);
                        invalidate();
                        return true;
                    }
                }
                if (layoutEditing && right.usesTouchZone()) {
                    float rcx = screenRect.left + right.zone.x * screenRect.width();
                    float rcy = screenRect.top + right.zone.y * screenRect.height();
                    float rd = (float) Math.hypot(event.getX() - rcx,
                            event.getY() - rcy);
                    float rr = rightRadiusPx();
                    if (rd <= Math.max(Ui.dp(getContext(), 46), rr * 1.55f)
                            && rd > rr * 0.64f) {
                        if (layoutEditing) {
                            selectedJoy = TouchTarget.RIGHT_JOY;
                            selectedCombo = null;
                            selected = null;
                            if (listener != null) {
                                listener.onJoystickSelected(TouchTarget.RIGHT_JOY);
                            }
                        }
                        resizingRight = true;
                        dragged = null;
                        draggedCombo = null;
                        downX = event.getX();
                        downY = event.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        Haptics.tick(this);
                        invalidate();
                        return true;
                    }
                }
                draggedCombo = hitCombo(event.getX(), event.getY());
                if (draggedCombo != null) {
                    if (layoutEditing) {
                        selectedCombo = draggedCombo;
                        selectedJoy = -1;
                        selected = null;
                        if (listener != null) {
                            listener.onComboSelected(draggedCombo);
                        }
                    }
                    if (layoutEditing) {
                        float ccx = screenRect.left + draggedCombo.x * screenRect.width();
                        float ccy = screenRect.top + draggedCombo.y * screenRect.height();
                        float cd = (float) Math.hypot(event.getX() - ccx,
                                event.getY() - ccy);
                        if (cd > draggedCombo.radius * screenRect.height() * 0.64f) {
                            resizingCombo = draggedCombo;
                            draggedCombo = null;
                        }
                    }
                    downX = event.getX();
                    downY = event.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    Haptics.tick(this);
                    invalidate();
                    return true;
                }
                dragged = hit(event.getX(), event.getY());
                if (dragged != null) {
                    if (layoutEditing) {
                        selected = dragged;
                        selectedJoy = -1;
                        selectedCombo = null;
                        if (listener != null) listener.onTargetSelected(dragged);
                    }
                    downX = event.getX();
                    downY = event.getY();
                    float rcx = screenRect.left + dragged.x * screenRect.width();
                    float rcy = screenRect.top + dragged.y * screenRect.height();
                    float dp = (float) Math.hypot(event.getX() - rcx, event.getY() - rcy);
                    if (layoutEditing && dragged.code != TouchTarget.R3_STICK
                            && dp > dragged.radius * screenRect.height() * 0.64f) {
                        resizing = dragged;
                        dragged = null;
                    }
                    getParent().requestDisallowInterceptTouchEvent(true);
                    Haptics.tick(this);
                    invalidate();
                    return true;
                }
                if (right.usesTouchZone()) {
                    float rx = screenRect.left + right.zone.x * screenRect.width();
                    float ry = screenRect.top + right.zone.y * screenRect.height();
                    float rdx = event.getX() - rx;
                    float rdy = event.getY() - ry;
                    float rd = (float) Math.hypot(rdx, rdy);
                    float rightReach = rightRadiusPx() * (layoutEditing ? 1.55f : 1f);
                    if (rd <= Math.max(Ui.dp(getContext(), 46), rightReach)) {
                        if (layoutEditing) {
                            selectedJoy = TouchTarget.RIGHT_JOY;
                            selectedCombo = null;
                            selected = null;
                            if (listener != null) {
                                listener.onJoystickSelected(TouchTarget.RIGHT_JOY);
                            }
                        }
                        if (layoutEditing && rd > rightRadiusPx() * 0.64f) {
                            resizingRight = true;
                        } else {
                            draggingRight = true;
                        }
                        dragged = null;
                        downX = event.getX();
                        downY = event.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        Haptics.tick(this);
                        invalidate();
                        return true;
                    }
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (resizingStick) {
                    float cx = screenRect.left + stick.x * screenRect.width();
                    float cy = screenRect.top + stick.y * screenRect.height();
                    float r = (float) Math.hypot(event.getX() - cx,
                            event.getY() - cy) / Math.max(1f, screenRect.height());
                    stick.radius = Math.max(0.06f, Math.min(0.30f, r));
                    invalidate();
                    return true;
                }
                if (resizingRight) {
                    float cx = screenRect.left + right.zone.x * screenRect.width();
                    float cy = screenRect.top + right.zone.y * screenRect.height();
                    float r = (float) Math.hypot(event.getX() - cx,
                            event.getY() - cy) / Math.max(1f, screenRect.height());
                    right.zone.radius = Math.max(0.06f, Math.min(0.30f, r));
                    invalidate();
                    return true;
                }
                if (resizingCombo != null) {
                    float cx = screenRect.left + resizingCombo.x * screenRect.width();
                    float cy = screenRect.top + resizingCombo.y * screenRect.height();
                    float r = (float) Math.hypot(event.getX() - cx,
                            event.getY() - cy) / Math.max(1f, screenRect.height());
                    resizingCombo.radius = TouchCombo.clampRadius(r);
                    invalidate();
                    return true;
                }
                if (draggingStick) {
                    stick.set((event.getX() - screenRect.left) / screenRect.width(),
                            (event.getY() - screenRect.top) / screenRect.height());
                    invalidate();
                    return true;
                }
                if (draggingRight) {
                    right.zone.set((event.getX() - screenRect.left) / screenRect.width(),
                            (event.getY() - screenRect.top) / screenRect.height());
                    invalidate();
                    return true;
                }
                if (resizing != null) {
                    float cx = screenRect.left + resizing.x * screenRect.width();
                    float cy = screenRect.top + resizing.y * screenRect.height();
                    float radius = (float) Math.hypot(event.getX() - cx,
                            event.getY() - cy) / Math.max(1f, screenRect.height());
                    resizing.radius = TouchTarget.clampRadius(radius);
                    invalidate();
                    return true;
                }
                if (draggedCombo != null) {
                    draggedCombo.x = Math.max(0.02f, Math.min(0.98f,
                            (event.getX() - screenRect.left) / screenRect.width()));
                    draggedCombo.y = Math.max(0.02f, Math.min(0.98f,
                            (event.getY() - screenRect.top) / screenRect.height()));
                    invalidate();
                    return true;
                }
                if (dragged == null) return false;
                dragged.set((event.getX() - screenRect.left) / screenRect.width(),
                        (event.getY() - screenRect.top) / screenRect.height());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (resizingStick || resizingRight || resizingCombo != null) {
                    resizingStick = false;
                    resizingRight = false;
                    resizingCombo = null;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    if (listener != null) listener.onLayoutMoved(true);
                    return true;
                }
                if (draggingStick) {
                    boolean moved = Math.abs(event.getX() - downX) > Ui.dp(getContext(), 2)
                            || Math.abs(event.getY() - downY) > Ui.dp(getContext(), 2);
                    draggingStick = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    if (moved && listener != null) {
                        Haptics.click(this);
                        listener.onLayoutMoved(true);
                    }
                    return true;
                }
                if (draggingRight) {
                    boolean moved = Math.abs(event.getX() - downX) > Ui.dp(getContext(), 2)
                            || Math.abs(event.getY() - downY) > Ui.dp(getContext(), 2);
                    draggingRight = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    if (moved && listener != null) {
                        Haptics.click(this);
                        listener.onLayoutMoved(true);
                    }
                    return true;
                }
                if (resizing != null) {
                    resizing = null;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    if (listener != null) listener.onLayoutMoved(true);
                    return true;
                }
                if (draggedCombo != null) {
                    boolean moved = Math.abs(event.getX() - downX) > Ui.dp(getContext(), 2)
                            || Math.abs(event.getY() - downY) > Ui.dp(getContext(), 2);
                    draggedCombo = null;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    if (moved && listener != null) {
                        Haptics.click(this);
                        listener.onLayoutMoved(true);
                    }
                    return true;
                }
                if (dragged == null) return false;
                boolean moved = Math.abs(event.getX() - downX) > Ui.dp(getContext(), 2)
                        || Math.abs(event.getY() - downY) > Ui.dp(getContext(), 2);
                dragged = null;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                if (moved && listener != null) {
                    Haptics.click(this);
                    listener.onLayoutMoved(true);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }
}
