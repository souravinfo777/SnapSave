package com.snapsave.app.ui;

import android.app.Activity;
import android.content.Intent;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String url = extract(getIntent());
        if (url == null) {
            Toast.makeText(this, "No link found to download.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

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
                        if (isFinishing()) {
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

    private String extract(Intent it) {
        if (it == null) {
            return null;
        }
        String action = it.getAction();
        if (Intent.ACTION_VIEW.equals(action) && it.getData() != null) {
            return it.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence t = it.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t == null) {
                t = it.getStringExtra(Intent.EXTRA_SUBJECT);
            }
            return t == null ? null : Resolvers.firstUrl(t.toString());
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<CharSequence> list = it.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT);
            if (list != null) {
                for (CharSequence c : list) {
                    String u = Resolvers.firstUrl(c == null ? null : c.toString());
                    if (u != null) {
                        return u;
                    }
                }
            }
        }
        return null;
    }
}