package com.snapsave.app.util;

import android.view.View;
import android.widget.ImageView;

import com.snapsave.app.R;

public final class Ui {

    private Ui() {
    }

    /** Bind a row's thumbnail: gradient placeholder + (optionally) the real preview image. */
    public static void bindThumb(View row, String thumbUrl, int grad) {
        View v = row.findViewById(R.id.thumb_bg);
        if (v instanceof ImageView) {
            ImageLoader.load((ImageView) v, thumbUrl, Util.grad(grad));
        } else if (v != null) {
            v.setBackgroundResource(Util.grad(grad));
        }
    }
}
