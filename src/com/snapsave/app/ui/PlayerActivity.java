package com.snapsave.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.util.Util;
import com.snapsave.app.util.Insets;

import java.util.List;

public class PlayerActivity extends Activity {

    private VideoView video;
    private ImageView playIcon;
    private SeekBar seek;
    private TextView cur;
    private TextView total;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging = false;
    /** True once MediaPlayer has prepared: controls must not touch the player before that. */
    private boolean prepared = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        Insets.applyToContent(this);

        final String path = getIntent().getStringExtra("path");
        final String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        String author = getIntent().getStringExtra("author");

        video = (VideoView) findViewById(R.id.video_view);
        playIcon = (ImageView) findViewById(R.id.play_pause_icon);
        seek = (SeekBar) findViewById(R.id.seek);
        cur = (TextView) findViewById(R.id.cur_time);
        total = (TextView) findViewById(R.id.total_time);

        ((TextView) findViewById(R.id.player_title)).setText(title == null ? "" : title);
        ((TextView) findViewById(R.id.p_title)).setText(title == null ? "" : title);
        ((TextView) findViewById(R.id.p_meta)).setText(author == null ? "" : author);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        String src = path != null ? path : url;
        if (src == null || src.trim().isEmpty()) {
            android.widget.Toast.makeText(this, "Nothing to play.", android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!src.startsWith("http") && !new java.io.File(src).exists()) {
            android.widget.Toast.makeText(this, "This file is missing.", android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        {
            Uri uri = src.startsWith("http") ? Uri.parse(src) : Uri.fromFile(new java.io.File(src));
            video.setVideoURI(uri);
            video.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(android.media.MediaPlayer mp) {
                    prepared = true;
                    mp.setLooping(false);
                    total.setText(Util.ms(mp.getDuration()));
                    video.start();
                    playIcon.setImageResource(R.drawable.ic_pause);
                }
            });
            video.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(android.media.MediaPlayer mp) {
                    playIcon.setImageResource(R.drawable.ic_play);
                }
            });
            video.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                    prepared = false;
                    android.widget.Toast.makeText(PlayerActivity.this, "Cannot play this file.", android.widget.Toast.LENGTH_SHORT).show();
                    playIcon.setImageResource(R.drawable.ic_play);
                    return true;
                }
            });
        }

        findViewById(R.id.play_pause).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!prepared) {
                    android.widget.Toast.makeText(PlayerActivity.this, "Still preparing…",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    if (video.isPlaying()) {
                        video.pause();
                        playIcon.setImageResource(R.drawable.ic_play);
                    } else {
                        video.start();
                        playIcon.setImageResource(R.drawable.ic_pause);
                    }
                } catch (IllegalStateException e) {
                    prepared = false;
                    android.widget.Toast.makeText(PlayerActivity.this, "Playback error.",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && prepared && video.getDuration() > 0) {
                    cur.setText(Util.ms(video.getDuration() * p / 100));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (prepared && video.getDuration() > 0) {
                    video.seekTo(video.getDuration() * s.getProgress() / 100);
                }
            }
        });

        buildActions();
        loadUpNext();
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (video != null && video.isPlaying()) {
                    int pos = video.getCurrentPosition();
                    int dur = video.getDuration();
                    if (!dragging && dur > 0) {
                        seek.setProgress(pos * 100 / dur);
                    }
                    cur.setText(Util.ms(pos));
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (prepared && video != null && video.isPlaying()) {
                video.pause();
                playIcon.setImageResource(R.drawable.ic_play);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        try {
            if (video != null) {
                video.stopPlayback();     // release the decoder; onDestroy otherwise leaks it
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void buildActions() {
        LinearLayout row = (LinearLayout) findViewById(R.id.action_row);
        row.removeAllViews();
        String[] labels = {"Share", "Save", "More"};
        int[] icons = {R.drawable.ic_share, R.drawable.ic_heart, R.drawable.ic_more};
        for (int i = 0; i < labels.length; i++) {
            View v = LayoutInflater.from(this).inflate(R.layout.item_action, row, false);
            ((ImageView) v.findViewById(R.id.action_icon)).setImageResource(icons[i]);
            ((TextView) v.findViewById(R.id.action_label)).setText(labels[i]);
            row.addView(v);
        }
    }

    /** "Up next" = the user's own recent downloads (no invented content). */
    private void loadUpNext() {
        LinearLayout container = (LinearLayout) findViewById(R.id.upnext_container);
        View upTitle = findViewById(R.id.upnext_title);
        if (upTitle != null) {
            upTitle.setVisibility(View.VISIBLE);
        }
        container.removeAllViews();
        List<DownloadTask> done = App.get().downloads().completed();
        int shown = 0;
        for (final DownloadTask t : done) {
            if (t.outFile == null || !t.outFile.exists() || "mp3".equals(t.kind) || "m4a".equals(t.kind)) {
                continue;
            }
            View row = LayoutInflater.from(this).inflate(R.layout.item_video, container, false);
            row.findViewById(R.id.thumb_bg).setBackgroundResource(Util.grad(t.grad));
            ((TextView) row.findViewById(R.id.thumb_duration)).setText(t.label);
            ((TextView) row.findViewById(R.id.video_title)).setText(t.title);
            ((TextView) row.findViewById(R.id.video_meta)).setText(Util.bytes(t.outFile.length()));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(PlayerActivity.this, PlayerActivity.class);
                    i.putExtra("path", t.outFile.getAbsolutePath());
                    i.putExtra("title", t.title);
                    i.putExtra("author", t.author);
                    startActivity(i);
                }
            });
            container.addView(row);
            shown++;
        }
        if (shown == 0 && upTitle != null) {
            upTitle.setVisibility(View.GONE);
        }
    }
}
