package com.snapsave.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import com.snapsave.app.R;
import com.snapsave.app.engine.ResolvedMedia;
import com.snapsave.app.engine.Resolvers;
import com.snapsave.app.util.Insets;

import java.util.ArrayList;

/**
 * Share target. Receives a link from any app (ACTION_SEND), from "Open with" (ACTION_VIEW) or
 * a multi-share, resolves it, then shows the download sheet directly on top of the current app
 * — the SnapTube-style "share → download popup" flow.
 */
public class ShareActivity extends Activity {

    /**
     * The link this flow is currently resolving, if any. A duplicate share intent (or the
     * clipboard prompt firing while a share sheet is already up) must not stack a second sheet.
     * Cleared on finish so the same link can be shared again later on purpose.
     */
    private static volatile String handlingUrl;
    /** This instance's link — finish() only clears the guard when it is still ours. */
    private String myUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String url = extract(getIntent());
        if (url == null) {
            Toast.makeText(this, "No link found to download.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (url.equals(handlingUrl)) {
            finish();
            return;
        }
        myUrl = url;
        handlingUrl = url;

        setContentView(R.layout.activity_share);
        Insets.applyToContent(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                ResolvedMedia resolved = null;
                try {
                    resolved = Resolvers.resolve(url);
                } catch (Throwable ignored) {
                    // never let a resolver failure strand the "Preparing download" card
                }
                final ResolvedMedia r = resolved;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (r == null || !r.hasAnything()) {
                            Toast.makeText(ShareActivity.this,
                                    "Couldn't find a downloadable stream for this link.",
                                    Toast.LENGTH_LONG).show();
                            finish();
                            return;
                        }
                        String title = Resolvers.firstText(r.title, url);
                        DownloadSheet sheet = DownloadSheet.create(
                                ShareActivity.this, r, title, r.author, r.grad, false, r.thumbUrl);
                        if (sheet == null) {
                            finish();
                            return;
                        }
                        sheet.setOnDismiss(new DownloadSheet.OnDismiss() {
                            @Override
                            public void onDismiss() {
                                finish();
                            }
                        });
                        // hide the "preparing" card; the sheet is the interface now
                        View content = findViewById(android.R.id.content);
                        if (content != null) {
                            content.setAlpha(0f);
                        }
                        sheet.show();
                    }
                });
            }
        }, "share-resolve").start();
    }

    @Override
    public void finish() {
        if (myUrl != null && myUrl.equals(handlingUrl)) {
            handlingUrl = null;   // let the same link be shared again later
        }
        super.finish();
    }

    private String extract(Intent it) {
        if (it == null) {
            return null;
        }
        String action = it.getAction();
        if (Intent.ACTION_VIEW.equals(action) && it.getData() != null) {
            return it.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(action)) {
            String u = firstFromText(it.getCharSequenceExtra(Intent.EXTRA_TEXT));
            if (u == null) {
                u = firstFromText(it.getCharSequenceExtra(Intent.EXTRA_SUBJECT));
            }
            if (u == null) {
                u = fromUri(it.getParcelableExtra(Intent.EXTRA_STREAM));
            }
            if (u == null) {
                u = fromClip(it.getClipData());
            }
            return u;
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<CharSequence> list = it.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT);
            if (list != null) {
                for (CharSequence c : list) {
                    String u = firstFromText(c);
                    if (u != null) {
                        return u;
                    }
                }
            }
            // Multi-share normally carries EXTRA_STREAM (Uris), not EXTRA_TEXT.
            ArrayList<Uri> uris = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) {
                for (Uri uri : uris) {
                    String u = fromUri(uri);
                    if (u != null) {
                        return u;
                    }
                }
            }
            return fromClip(it.getClipData());
        }
        return null;
    }

    /** First http(s) link in a shared text, or null when the text has none. */
    private String firstFromText(CharSequence c) {
        return c == null ? null : Resolvers.firstUrl(c.toString());
    }

    private String fromUri(Object o) {
        if (!(o instanceof Uri)) {
            return null;
        }
        String s = o.toString();
        return s.startsWith("http") ? s : null;
    }

    private String fromClip(ClipData clip) {
        if (clip == null) {
            return null;
        }
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item == null) {
                continue;
            }
            String u = firstFromText(item.getText());
            if (u != null) {
                return u;
            }
            Uri uri = item.getUri();
            if (uri != null && uri.toString().startsWith("http")) {
                return uri.toString();
            }
        }
        return null;
    }
}