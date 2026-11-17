package com.snapsave.app.util;

import android.view.View;
import android.widget.ImageView;

import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadTask;

public final class Ui {

    private Ui() {
    }

    /** Bind a real preview, keeping a type icon if no preview is available. */
    public static void bindThumb(View row, String thumbUrl, int grad) {
        if (row == null) return;
        View v = row.findViewById(R.id.thumb_bg);
        if (v instanceof ImageView) {
            ImageLoader.load((ImageView) v, thumbUrl, Util.grad(grad));
        } else if (v != null) {
            v.setBackgroundResource(Util.grad(grad));
        }
    }

    /** URI-aware completed output binding for callers that own a DownloadTask. */
    public static void bindThumb(View row, DownloadTask task) {
        if (row == null) return;
        View v = row.findViewById(R.id.thumb_bg);
        int placeholder = Util.grad(task == null ? 0 : task.grad);
        if (v instanceof ImageView) {
            LocalThumb.load((ImageView) v, task, placeholder);
        } else if (v != null) {
            v.setBackgroundResource(placeholder);
        }
    }

    /** Call when discarding/recycling a row; detach also suspends its thumbnail automatically. */
    public static void cancelThumb(View row) {
        if (row == null) return;
        View v = row.findViewById(R.id.thumb_bg);
        if (v instanceof ImageView) ImageLoader.cancel((ImageView) v);
    }
}
