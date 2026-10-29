package com.ben.pavo;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

/** TextView that translates exact English catalogue strings as they are set. */
final class LTextView extends TextView {
    private boolean localize = true;

    void setLocalize(boolean value) {
        localize = value;
    }

    public LTextView(Context c) {
        super(c);
    }

    public LTextView(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    public LTextView(Context c, AttributeSet attrs, int defStyle) {
        super(c, attrs, defStyle);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(localize ? Loc.t(getContext(), text) : text, type);
    }

    @Override
    public void setContentDescription(CharSequence description) {
        super.setContentDescription(localize ? Loc.t(getContext(), description) : description);
    }
}
