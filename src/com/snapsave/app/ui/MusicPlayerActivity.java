package com.snapsave.app.ui;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.snapsave.app.R;
import com.snapsave.app.util.Util;
import com.snapsave.app.util.Insets;

public class MusicPlayerActivity extends Activity {

    private MediaPlayer mp;
    private ImageView playIcon;
    private SeekBar seek;
    private TextView cur;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_player);
        Insets.applyToContent(this);

        String path = getIntent().getStringExtra("path");
        String title = getIntent().getStringExtra("title");
        String author = getIntent().getStringExtra("author");
        int grad = getIntent().getIntExtra("grad", 0);

        findViewById(R.id.m_art).setBackgroundResource(Util.grad(grad));
        ((TextView) findViewById(R.id.m_title)).setText(title == null ? "Audio" : title);
        ((TextView) findViewById(R.id.m_artist)).setText(author == null ? "SnapSave" : author);

        playIcon = (ImageView) findViewById(R.id.m_play_icon);
        seek = (SeekBar) findViewById(R.id.m_seek);
        cur = (TextView) findViewById(R.id.m_cur);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        try {
            mp = new MediaPlayer();
            mp.setDataSource(path);
            mp.prepare();
            ((TextView) findViewById(R.id.m_total)).setText(Util.ms(mp.getDuration()));
            mp.start();
            playIcon.setImageResource(R.drawable.ic_pause);
        } catch (Exception e) {
            playIcon.setImageResource(R.drawable.ic_play);
        }

        findViewById(R.id.m_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mp == null) {
                    return;
                }
                if (mp.isPlaying()) {
                    mp.pause();
                    playIcon.setImageResource(R.drawable.ic_play);
                } else {
                    mp.start();
                    playIcon.setImageResource(R.drawable.ic_pause);
                }
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && mp != null) {
                    cur.setText(Util.ms(mp.getDuration() * p / 100));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (mp != null) {
                    mp.seekTo(mp.getDuration() * s.getProgress() / 100);
                }
            }
        });

        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mp != null) {
                try {
                    int pos = mp.getCurrentPosition();
                    int dur = mp.getDuration();
                    if (!dragging && dur > 0) {
                        seek.setProgress(pos * 100 / dur);
                    }
                    cur.setText(Util.ms(pos));
                } catch (Exception ignored) {
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        super.onDestroy();
    }
}
