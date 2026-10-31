package com.ben.pavo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.View;

/**
 * The live content behind the app drawer, rendered inside the card only.
 *
 * The home layer is drawn once into this view and the glass RenderEffect chain
 * is attached to this view, so the blur, saturation and edge refraction are
 * clipped to the rounded drawer card instead of tinting the whole screen.
 */
final class LiveBackdropView extends View {
    private View source;
    private float radiusPx;
    private float edgeInsetPx;
    private boolean edgeOnly;
    private boolean enabled;
    private boolean paused;
    /** Resting card position, used when the card is scaled or sliding. */
    private int restX = -1;
    private int restY = -1;
    private boolean recordPending;
    private Bitmap snapshot;
    private Bitmap videoFrame;
    private int videoX, videoY, videoW, videoH;
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final Paint snapshotPaint = new Paint(Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private RenderNode node;
    private RenderEffect effect;
    private int effectWidth;
    private int effectHeight;
    private float effectRadius;
    private static final float RENDER_SCALE = 0.5f;
    private boolean refreshPosted;
    private float glassSaturation = 1.5f;
    private float glassDarkness;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refreshPosted = false;
            if (!enabled || paused || getWindowVisibility() != VISIBLE) return;
            invalidate();
            postDelayed(this, 16L);
            refreshPosted = true;
        }
    };

    LiveBackdropView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setRadiusDp(26f);
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.5f);
        snapshotPaint.setColorFilter(new ColorMatrixColorFilter(saturation));
    }

    void setRadiusDp(float dp) {
        radiusPx = Ui.dpf(getContext(), dp);
        invalidate();
    }

    void setEdgeOnlyDp(float dp) {
        edgeOnly = true;
        edgeInsetPx = Ui.dpf(getContext(), dp);
        invalidate();
    }

    void setSource(View source) {
        this.source = source;
        updateEffect();
        invalidate();
    }

    void setGlassTuning(float saturation, float darkness) {
        float sat = Math.max(0f, saturation);
        float dark = Math.max(0f, Math.min(1f, darkness));
        if (sat == glassSaturation && dark == glassDarkness) return;
        glassSaturation = sat;
        glassDarkness = dark;
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(sat);
        if (dark > 0f) {
            float k = 1f - dark;
            ColorMatrix dim = new ColorMatrix(new float[]{
                    k, 0f, 0f, 0f, 0f,
                    0f, k, 0f, 0f, 0f,
                    0f, 0f, k, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f});
            matrix.postConcat(dim);
        }
        snapshotPaint.setColorFilter(new ColorMatrixColorFilter(matrix));
        effect = null;
        node = null;
        invalidate();
    }

    void setSnapshot(Bitmap bitmap) {
        if (snapshot != null && snapshot != bitmap) snapshot.recycle();
        snapshot = bitmap;
        invalidate();
    }

    /**
     * Stop re-recording the home layer while the drawer is moving. The last
     * recorded node still draws with its blur, so the material looks the same
     * but costs a blit instead of a full home redraw per frame.
     */
    void setLivePaused(boolean value) {
        if (paused == value) return;
        paused = value;
        if (paused) {
            removeCallbacks(refresh);
            refreshPosted = false;
        } else {
            scheduleRefresh();
        }
    }

    void setRestPosition(int x, int y) {
        restX = x;
        restY = y;
    }

    /** Re-record the frozen frame with the current geometry. */
    void invalidateRecording() {
        recordPending = true;
        invalidate();
    }

    /** Latest video-wallpaper frame, in screen coordinates, for the live capture. */
    void setVideoFrame(Bitmap frame, int x, int y, int w, int h) {
        if (videoFrame != null && videoFrame != frame) videoFrame.recycle();
        videoFrame = frame;
        videoX = x;
        videoY = y;
        videoW = w;
        videoH = h;
        invalidate();
    }

    void clearVideoFrame() {
        if (videoFrame != null) {
            videoFrame.recycle();
            videoFrame = null;
        }
    }

    void setBackdropEnabled(boolean value) {
        enabled = value;
        if (value) {
            updateEffect();
            invalidate();
            scheduleRefresh();
        } else {
            removeCallbacks(refresh);
            refreshPosted = false;
        }
    }

    private void scheduleRefresh() {
        if (!refreshPosted && enabled && !paused) {
            refreshPosted = true;
            postDelayed(refresh, 33L);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateEffect();
    }

    private void updateEffect() {
        effect = null;
        node = null;
        invalidate();
    }

    private RenderEffect effectFor(int width, int height, float scale) {
        if (effect != null && effectWidth == width && effectHeight == height
                && effectRadius == radiusPx) {
            return effect;
        }
        effect = LiveGlassEffect.build(
                getResources().getDisplayMetrics().density,
                0, 0, width, height, radiusPx * scale, scale, 8f, false,
                glassSaturation, glassDarkness);
        effectWidth = width;
        effectHeight = height;
        effectRadius = radiusPx;
        return effect;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!enabled || source == null || source.getWidth() <= 0
                || source.getHeight() <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated()) {
            int save = canvas.save();
            int[] here = new int[2];
            int[] sourceOnScreen = new int[2];
            getLocationOnScreen(here);
            source.getLocationOnScreen(sourceOnScreen);
            canvas.translate(sourceOnScreen[0] - here[0], sourceOnScreen[1] - here[1]);
            if (snapshot != null && !snapshot.isRecycled()
                    && source.getWidth() > 0 && source.getHeight() > 0) {
                canvas.drawBitmap(snapshot, null,
                        new android.graphics.RectF(0, 0, source.getWidth(), source.getHeight()),
                        snapshotPaint);
            } else {
                source.draw(canvas);
            }
            canvas.restoreToCount(save);
            scheduleRefresh();
            return;
        }
        int width = getWidth();
        int height = getHeight();
        if (paused && !recordPending && node != null) {
            int save = canvas.save();
            canvas.scale(width / (float) Math.max(1, Math.round(width * RENDER_SCALE)),
                    height / (float) Math.max(1, Math.round(height * RENDER_SCALE)));
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);
            return;
        }
        recordPending = false;
        int sw = Math.max(1, Math.round(width * RENDER_SCALE));
        int sh = Math.max(1, Math.round(height * RENDER_SCALE));
        float scale = sw / (float) width;
        if (node == null) node = new RenderNode("aurora-drawer-backdrop");
        node.setPosition(0, 0, sw, sh);
        node.setRenderEffect(effectFor(sw, sh, scale));

        int[] here = new int[2];
        int[] sourceOnScreen = new int[2];
        getLocationOnScreen(here);
        source.getLocationOnScreen(sourceOnScreen);
        int hx = restX >= 0 ? restX : here[0];
        int hy = restY >= 0 ? restY : here[1];
        RecordingCanvas recording = node.beginRecording(sw, sh);
        recording.scale(scale, scale);
        recording.translate(sourceOnScreen[0] - hx, sourceOnScreen[1] - hy);
        if (edgeOnly) {
            Path outer = new Path();
            outer.addRoundRect(0, 0, width, height, radiusPx, radiusPx,
                    Path.Direction.CW);
            Path inner = new Path();
            float inset = edgeInsetPx;
            inner.addRoundRect(inset, inset, width - inset, height - inset,
                    Math.max(0f, radiusPx - inset), Math.max(0f, radiusPx - inset),
                    Path.Direction.CW);
            outer.op(inner, Path.Op.DIFFERENCE);
            recording.clipPath(outer);
        }
        source.draw(recording);
        if (videoFrame != null && !videoFrame.isRecycled() && videoW > 0 && videoH > 0) {
            // TextureView content never lands in a render node, so the video
            // wallpaper is stamped in from its latest frame.
            recording.drawBitmap(videoFrame, null,
                    new android.graphics.RectF(videoX - hx, videoY - hy,
                            videoX + videoW - hx, videoY + videoH - hy), framePaint);
        }
        node.endRecording();

        int save = canvas.save();
        canvas.scale(1f / scale, 1f / scale);
        canvas.drawRenderNode(node);
        canvas.restoreToCount(save);
        scheduleRefresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (enabled) scheduleRefresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        refreshPosted = false;
        super.onDetachedFromWindow();
    }
}
