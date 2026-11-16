package com.snapsave.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.R;
import com.snapsave.app.engine.ResolvedMedia;
import com.snapsave.app.engine.Resolvers;
import com.snapsave.app.model.MediaItem;
import com.snapsave.app.model.StreamOption;
import com.snapsave.app.util.ImageLoader;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.Ui;
import com.snapsave.app.util.Util;

import java.util.List;

public class VideoDetailActivity extends Activity {

    private String title, author, duration, meta, url, thumbUrl;
    private int grad;
    private boolean autoSheet;

    private LinearLayout downloadBtn;
    private TextView downloadLabel;
    private ImageView previewImg;
    private ResolvedMedia resolved;
    private boolean resolving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_detail);
        Insets.applyToContent(this);

        title = getIntent().getStringExtra("title");
        author = getIntent().getStringExtra("author");
        duration = getIntent().getStringExtra("duration");
        meta = getIntent().getStringExtra("meta");
        thumbUrl = getIntent().getStringExtra("thumbUrl");
        grad = getIntent().getIntExtra("grad", 0);
        url = getIntent().getStringExtra("url");
        autoSheet = getIntent().getBooleanExtra("autoSheet", false);
        if (title == null || title.isEmpty()) title = "Media";
        if (author == null) author = "";
        if (duration == null) duration = "";
        if (meta == null) meta = "";

        previewImg = (ImageView) findViewById(R.id.preview_img);
        findViewById(R.id.preview).setBackgroundResource(Util.grad(grad));
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            ImageLoader.load(previewImg, thumbUrl, Util.grad(grad));
        }

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ((TextView) findViewById(R.id.detail_title)).setText(title);
        ((TextView) findViewById(R.id.detail_meta)).setText(buildMeta());
        ((TextView) findViewById(R.id.preview_duration)).setText(duration.isEmpty() ? "" : duration);
        ((TextView) findViewById(R.id.url_text)).setText(url == null ? "" : url);
        ((TextView) findViewById(R.id.channel_letter))
                .setText(author.isEmpty() ? "•" : author.substring(0, 1).toUpperCase());
        ((TextView) findViewById(R.id.channel_name)).setText(author.isEmpty() ? hostOf(url) : author);
        findViewById(R.id.channel_subs).setVisibility(View.GONE);
        findViewById(R.id.btn_subscribe).setVisibility(View.GONE);

        buildActions();
        loadUpNext();

        downloadBtn = (LinearLayout) findViewById(R.id.btn_download_video);
        downloadLabel = (TextView) downloadBtn.getChildAt(1);
        downloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDownloadClicked();
            }
        });

        resolveAsync();
    }

    private String buildMeta() {
        StringBuilder sb = new StringBuilder();
        if (!meta.isEmpty()) sb.append(meta);
        if (!author.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(author);
        }
        return sb.toString();
    }

    private static String hostOf(String u) {
        try {
            return new java.net.URL(u).getHost();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private void buildActions() {
        LinearLayout row = (LinearLayout) findViewById(R.id.action_row);
        row.removeAllViews();
        addAction(row, R.drawable.ic_music, "MP3", new Runnable() {
            @Override
            public void run() {
                if (resolved != null && resolved.hasAnything()) {
                    DownloadSheet.open(VideoDetailActivity.this, resolved, title, author, grad, true);
                } else {
                    Toast.makeText(VideoDetailActivity.this, "Still resolving…", Toast.LENGTH_SHORT).show();
                }
            }
        });
        addAction(row, R.drawable.ic_share, "Share", new Runnable() {
            @Override
            public void run() {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, url == null ? title : url);
                startActivity(Intent.createChooser(send, "Share"));
            }
        });
        addAction(row, R.drawable.ic_globe, "Browser", new Runnable() {
            @Override
            public void run() {
                if (url != null && url.startsWith("http")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        Toast.makeText(VideoDetailActivity.this, "No browser found", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void addAction(LinearLayout parent, int icon, String label, final Runnable onClick) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_action, parent, false);
        ((ImageView) v.findViewById(R.id.action_icon)).setImageResource(icon);
        ((TextView) v.findViewById(R.id.action_label)).setText(label);
        v.findViewById(R.id.action_circle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        parent.addView(v);
    }

    private void loadUpNext() {
        final LinearLayout container = (LinearLayout) findViewById(R.id.upnext_container);
        final View upTitle = findViewById(R.id.upnext_title);
        container.removeAllViews();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> items = Resolvers.trending();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (items == null || items.isEmpty()) {
                            upTitle.setVisibility(View.GONE);
                            return;
                        }
                        upTitle.setVisibility(View.VISIBLE);
                        for (final MediaItem m : items) {
                            if (m.pageUrl != null && url != null && m.pageUrl.equals(url)) {
                                continue;
                            }
                            View row = LayoutInflater.from(VideoDetailActivity.this)
                                    .inflate(R.layout.item_video, container, false);
                            Ui.bindThumb(row, m.thumbUrl, m.grad);
                            ((TextView) row.findViewById(R.id.thumb_duration)).setText(m.duration == null ? "" : m.duration);
                            ((TextView) row.findViewById(R.id.video_title)).setText(m.title);
                            ((TextView) row.findViewById(R.id.video_meta)).setText(HomeScreen.metaLine(m));
                            row.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    HomeScreen.openDetail(VideoDetailActivity.this, m);
                                }
                            });
                            container.addView(row);
                        }
                    }
                });
            }
        }, "upnext").start();
    }

    private void resolveAsync() {
        if (url == null || url.isEmpty()) {
            downloadLabel.setText("No link");
            downloadBtn.setEnabled(false);
            return;
        }
        resolving = true;
        downloadLabel.setText("Resolving…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                ResolvedMedia resolvedMedia = null;
                try {
                    resolvedMedia = Resolvers.resolve(url);
                } catch (Throwable ignored) {
                    // fall through with a null result so the button never stays on "Resolving..."
                }
                final ResolvedMedia r = resolvedMedia;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        resolved = r;
                        resolving = false;
                        if (r != null) {
                            if (r.title != null && !r.title.isEmpty()) {
                                ((TextView) findViewById(R.id.detail_title)).setText(r.title);
                                title = r.title;
                            }
                            if (r.author != null && !r.author.isEmpty()) {
                                ((TextView) findViewById(R.id.channel_name)).setText(r.author);
                                ((TextView) findViewById(R.id.channel_letter))
                                        .setText(r.author.substring(0, 1).toUpperCase());
                            }
                            if (r.duration != null && !r.duration.isEmpty()) {
                                ((TextView) findViewById(R.id.preview_duration)).setText(r.duration);
                            }
                            if (r.thumbUrl != null && !r.thumbUrl.isEmpty()) {
                                ImageLoader.load(previewImg, r.thumbUrl, Util.grad(grad));
                            }
                            String q = bestQuality(r);
                            if (q != null) {
                                ((TextView) findViewById(R.id.preview_badge)).setText(q);
                            }
                        }
                        downloadLabel.setText("Download Video");
                        if (autoSheet && resolved != null && resolved.hasAnything()) {
                            DownloadSheet.open(VideoDetailActivity.this, resolved, title, author, grad, false);
                        }
                    }
                });
            }
        }, "resolve").start();
    }

    private String bestQuality(ResolvedMedia r) {
        int h = 0;
        for (StreamOption o : r.videoStreams) {
            if (o.height > h) {
                h = o.height;
            }
        }
        return h > 0 ? "HD " + h + "p" : null;
    }

    private void onDownloadClicked() {
        if (resolving) {
            Toast.makeText(this, "Resolving streams…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (resolved != null && resolved.hasAnything()) {
            DownloadSheet.open(this, resolved, title, author, grad, false);
        } else {
            Toast.makeText(this, "No downloadable streams found for this link.", Toast.LENGTH_LONG).show();
        }
    }
}