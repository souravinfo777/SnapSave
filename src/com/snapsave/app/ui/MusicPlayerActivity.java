package com.snapsave.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.snapsave.app.R;
import com.snapsave.app.engine.MusicService;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.Util;

/**
 * Music UI backed by {@link MusicService}. Closing this screen only hides the controls; audio
 * keeps playing in the foreground service with notification controls.
 */
public class MusicPlayerActivity extends Activity {

    private ImageView playIcon;
    private SeekBar seek;
    private TextView cur;
    private TextView total;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging = false;
    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_player);
        Insets.applyToContent(this);

        path = getIntent().getStringExtra("path");
        String title = getIntent().getStringExtra("title");
        String author = getIntent().getStringExtra("author");
        int grad = getIntent().getIntExtra("grad", 0);

        findViewById(R.id.m_art).setBackgroundResource(Util.grad(grad));
        ((TextView) findViewById(R.id.m_title)).setText(title == null ? "Audio" : title);
        ((TextView) findViewById(R.id.m_artist)).setText(author == null ? "SnapSave" : author);

        playIcon = (ImageView) findViewById(R.id.m_play_icon);
        seek = (SeekBar) findViewById(R.id.m_seek);
        cur = (TextView) findViewById(R.id.m_cur);
        total = (TextView) findViewById(R.id.m_total);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Playback stays in the service; back only closes the controls.
                finish();
            }
        });

        if (path != null && !path.isEmpty() && !path.equals(MusicService.currentPath())) {
            MusicService.play(this, path, title, author, grad);
        }
        playIcon.setImageResource(MusicService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        findViewById(R.id.m_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicService.toggle(MusicPlayerActivity.this);
                playIcon.setImageResource(MusicService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    int duration = MusicService.duration();
                    if (duration > 0) {
                        cur.setText(Util.ms(duration * p / 100));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                int duration = MusicService.duration();
                if (duration > 0) {
                    MusicService.seek(MusicPlayerActivity.this, duration * s.getProgress() / 100);
                }
            }
        });

        handler.post(tick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || intent.getStringExtra("path") == null) {
            return;
        }
        setIntent(intent);
        path = intent.getStringExtra("path");
        String title = intent.getStringExtra("title");
        String author = intent.getStringExtra("author");
        int grad = intent.getIntExtra("grad", 0);
        findViewById(R.id.m_art).setBackgroundResource(Util.grad(grad));
        ((TextView) findViewById(R.id.m_title)).setText(title == null ? "Audio" : title);
        ((TextView) findViewById(R.id.m_artist)).setText(author == null ? "SnapSave" : author);
        seek.setProgress(0);
        total.setText(Util.ms(0));
        if (!path.equals(MusicService.currentPath())) {
            MusicService.play(this, path, title, author, grad);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int duration = MusicService.duration();
            int position = MusicService.position();
            if (duration > 0) {
                total.setText(Util.ms(duration));
                if (!dragging) {
                    seek.setProgress(position * 100 / duration);
                }
            }
            cur.setText(Util.ms(position));
            playIcon.setImageResource(MusicService.isPlaying()
                    ? R.drawable.ic_pause : R.drawable.ic_play);
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        // Never release the player here: the foreground service owns it.
        super.onDestroy();
    }
}