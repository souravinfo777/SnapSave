package com.snapsave.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.TextView;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

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
    private static java.lang.ref.WeakReference<ShareActivity> handling =
            new java.lang.ref.WeakReference<ShareActivity>(null);
    private String myUrl;
    private Thread resolverThread;
    private int generation;
    private DownloadSheet sheet;
    private AlertDialog errorDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);
        Insets.applyToContent(this);
        String url = savedInstanceState == null ? extract(getIntent()) : savedInstanceState.getString("shareUrl");
        begin(url);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = extract(intent);
        if (url != null && url.equals(myUrl)) return;
        begin(url);
    }

    private void begin(String url) {
        ShareActivity owner = handling.get();
        if (url != null && owner != null && owner != this && !owner.isFinishing()
                && !owner.isDestroyed() && url.equals(owner.myUrl)) {
            finish();
            return;
        }
        if (resolverThread != null) resolverThread.interrupt();
        generation++;
        if (sheet != null) {
            sheet.setOnDismiss(null);
            sheet.dismiss();
            sheet = null;
        }
        if (errorDialog != null) errorDialog.dismiss();
        myUrl = url;
        handling = new java.lang.ref.WeakReference<ShareActivity>(this);
        if (url == null) {
            showError("No web link was found. Share the TikTok link as text and try again.");
        } else {
            resolveAsync();
        }
    }

    private void resolveAsync() {
        if (isFinishing() || isDestroyed()) return;
        final int request = ++generation;
        final String url = myUrl;
        View content = findViewById(android.R.id.content);
        if (content != null) content.setAlpha(1f);
        ((TextView) findViewById(R.id.share_status)).setText("Preparing download…");
        resolverThread = new Thread(new Runnable() {
            @Override public void run() {
                final Resolvers.Outcome outcome = Resolvers.resolveDetailed(url);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed() || request != generation) return;
                        if (!outcome.isSuccess()) {
                            showError(outcome.message);
                            return;
                        }
                        ResolvedMedia r = outcome.media;
                        sheet = DownloadSheet.create(ShareActivity.this, r,
                                Resolvers.firstText(r.title, "Shared media"), r.author, r.grad, false, r.thumbUrl);
                        if (sheet == null) {
                            showError("Could not open download options. Please retry.");
                            return;
                        }
                        sheet.setOnDismiss(new DownloadSheet.OnDismiss() {
                            @Override public void onDismiss() {
                                if (!isFinishing() && !isDestroyed()) finish();
                            }
                        });
                        View content = findViewById(android.R.id.content);
                        if (content != null) content.setAlpha(0f);
                        sheet.show();
                    }
                });
            }
        }, "share-resolve");
        resolverThread.start();
    }

    private void showError(String message) {
        View content = findViewById(android.R.id.content);
        if (content != null) content.setAlpha(0f);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Download unavailable")
                .setMessage(message)
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { finish(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface dialog) { finish(); }
                });
        if (myUrl != null) {
            builder.setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { resolveAsync(); }
            });
        }
        errorDialog = builder.show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        StoragePermission.onResult(this, requestCode, permissions, grantResults);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("shareUrl", myUrl);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        generation++;
        if (resolverThread != null) resolverThread.interrupt();
        if (sheet != null) sheet.setOnDismiss(null);
        DownloadSheet.dismissFor(this);
        StoragePermission.onDestroy(this);
        if (errorDialog != null) errorDialog.dismiss();
        if (handling.get() == this) handling.clear();
        super.onDestroy();
    }

    @Override
    public void finish() {
        generation++;
        if (handling.get() == this) handling.clear();
        super.finish();
    }

    private String extract(Intent it) {
        if (it == null) return null;
        try {
            return extractLink(it);
        } catch (RuntimeException malformedExtras) {
            return null;
        }
    }

    private String extractLink(Intent it) {
        String action = it.getAction();
        if (Intent.ACTION_VIEW.equals(action) && it.getData() != null) {
            return fromUri(it.getData());
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
        Uri uri = (Uri) o;
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null ? uri.toString() : null;
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
            u = fromUri(item.getUri());
            if (u != null) return u;
        }
        return null;
    }
}