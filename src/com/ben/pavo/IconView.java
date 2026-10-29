package com.ben.pavo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class IconView extends LinearLayout {
    private final ImageView image;
    private final TextView label;
    private final int iconSizePx;
    private final float labelSp;
    private Apps.Entry entry;

    IconView(Context c, int iconDp, float labelSp) {
        super(c);
        this.iconSizePx = Ui.dp(c, iconDp);
        this.labelSp = labelSp;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(Ui.dp(c, 2), Ui.dp(c, 4), Ui.dp(c, 2), Ui.dp(c, 4));
        setForeground(Ui.ripple(c, Ui.dpf(c, 18)));
        setClickable(true);
        setFocusable(true);

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(image, new LayoutParams(iconSizePx, iconSizePx));

        label = new TextView(c);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        label.setShadowLayer(Ui.dpf(c, 3), 0, Ui.dpf(c, 1), 0xB3000000);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 5);
        addView(label, lp);
    }

    void bind(Apps.Entry e, boolean showLabel) {
        entry = e;
        setContentDescription(e.label);
        label.setText(e.label);
        label.setVisibility(showLabel ? View.VISIBLE : View.GONE);

        if (e.icon != null) {
            image.setImageBitmap(e.icon);
            image.setBackground(null);
        } else {
            image.setImageDrawable(null);
            image.setBackground(Ui.rounded(0x1AFFFFFF, iconSizePx * 0.225f));
        }
    }

    Apps.Entry entry() {
        return entry;
    }

    void setIconBitmap(Bitmap b) {
        image.setImageBitmap(b);
        image.setBackground(null);
    }

    float labelSizeSp() {
        return labelSp;
    }
}
