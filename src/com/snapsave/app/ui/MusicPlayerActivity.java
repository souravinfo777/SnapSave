package com.snapsave.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaFiles;
import com.snapsave.app.engine.MusicService;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.LocalThumb;
import com.snapsave.app.util.Util;

/** Single-track controls; the service owns playback when this screen closes. */
public class MusicPlayerActivity extends Activity {
    private ImageView playIcon, art;
    private SeekBar seek;
    private TextView cur, total, status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging;
    private String path, title, author;
    private int grad;
    private long pendingUntil;
    private DownloadTask task;
    private PlayerActivity.MediaActions actions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_music_player);
        Insets.applyToContent(this);
        art = (ImageView) findViewById(R.id.m_art);
        playIcon = (ImageView) findViewById(R.id.m_play_icon);
        seek = (SeekBar) findViewById(R.id.m_seek);
        cur = (TextView) findViewById(R.id.m_cur);
        total = (TextView) findViewById(R.id.m_total);
        status = (TextView) findViewById(R.id.m_status);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.m_play).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!path.equals(MusicService.currentPath()) || (!MusicService.isPrepared() && !MusicService.isPreparing()))
                    startTrack();
                else MusicService.toggle(MusicPlayerActivity.this);
            }
        });
        findViewById(R.id.m_share).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { actions.run(0); }
        });
        findViewById(R.id.m_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { actions.run(1); }
        });
        findViewById(R.id.m_more).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MusicPlayerActivity.this).setTitle("Audio options")
                        .setItems(new String[]{"Open with another app", "File details", "Stop playback"},
                                new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface dialog, int which) {
                                        if (which == 0) actions.run(2);
                                        else if (which == 1) actions.details();
                                        else MusicService.stop(MusicPlayerActivity.this);
                                    }
                                }).show();
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean user) {
                if (user) cur.setText(Util.ms((long) MusicService.duration() * progress / 100));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                int duration = MusicService.duration();
                if (path.equals(MusicService.currentPath()) && duration > 0)
                    MusicService.seek(MusicPlayerActivity.this, (int) ((long) duration * s.getProgress() / 100));
            }
        });
        readIntent(getIntent(), state == null);
    }

    private void readIntent(Intent intent, boolean autoPlay) {
        String requested = intent.getStringExtra("path");
        if (PlayerActivity.empty(requested)) requested = intent.getStringExtra("url");
        if (PlayerActivity.empty(requested) && intent.getData() != null) requested = intent.getData().toString();
        if (PlayerActivity.empty(requested)) requested = MusicService.currentPath();
        task = PlayerActivity.findTask(intent, requested);
        Uri uri = task == null ? PlayerActivity.sourceUri(requested) : MediaFiles.uri(task);
        if (uri == null) {
            Toast.makeText(this, "No audio file selected.", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        path = uri.toString();
        title = task == null ? intent.getStringExtra("title") : task.title;
        author = task == null ? intent.getStringExtra("author") : task.author;
        grad = task == null ? intent.getIntExtra("grad", 0) : task.grad;
        if (path.equals(MusicService.currentPath())) {
            title = MusicService.currentTitle(); author = MusicService.currentAuthor(); grad = MusicService.currentGrad();
        }
        renderTrack();
        if (autoPlay && !path.equals(MusicService.currentPath())) startTrack();
        updateControls();
    }

    private void renderTrack() {
        ((TextView) findViewById(R.id.m_title)).setText(PlayerActivity.empty(title) ? "Audio" : title);
        ((TextView) findViewById(R.id.m_artist)).setText(PlayerActivity.empty(author) ? "SnapSave" : author);
        if (task != null) LocalThumb.load(art, task, Util.grad(grad));
        else LocalThumb.loadUri(art, Uri.parse(path), "audio", Util.grad(grad));
        String mime = task == null ? getIntent().getStringExtra("mimeType") : task.mimeType;
        actions = new PlayerActivity.MediaActions(this, task, Uri.parse(path), PlayerActivity.empty(mime) ? "audio/*" : mime);
        findViewById(R.id.m_save).setVisibility(task == null ? View.GONE : View.VISIBLE);
        seek.setProgress(0);
        cur.setText(Util.ms(0));
        total.setText(Util.ms(0));
    }

    private void startTrack() {
        if (task != null && !MediaFiles.exists(this, task)) {
            Toast.makeText(this, "This audio file is no longer available.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingUntil = SystemClock.elapsedRealtime() + 2000;
        MusicService.play(this, path, title, author, grad);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
        readIntent(intent, true);
    }

    private void updateControls() {
        if (path == null) return;
        String current = MusicService.currentPath();
        if (path.equals(current)) pendingUntil = 0;
        if (current != null && !path.equals(current) && SystemClock.elapsedRealtime() >= pendingUntil) {
            path = current;
            task = PlayerActivity.findTask(new Intent(), path);
            title = MusicService.currentTitle(); author = MusicService.currentAuthor(); grad = MusicService.currentGrad();
            renderTrack();
        }
        boolean selected = path.equals(current);
        boolean playing = selected && MusicService.isPlaying();
        boolean preparing = selected && MusicService.isPreparing();
        int duration = selected ? MusicService.duration() : 0;
        int position = selected ? MusicService.position() : 0;
        total.setText(Util.ms(duration));
        seek.setEnabled(selected && MusicService.isPrepared() && duration > 0);
        if (!dragging) {
            seek.setProgress(duration > 0 ? (int) ((long) position * 100 / duration) : 0);
            cur.setText(Util.ms(position));
        }
        playIcon.setImageResource(playing || preparing ? R.drawable.ic_pause : R.drawable.ic_play);
        findViewById(R.id.m_play).setContentDescription(playing || preparing ? "Pause" : "Play or replay");
        status.setText(preparing ? "Preparing audio…" : playing ? "Playing • continues when you leave this screen"
                : selected && MusicService.isPrepared() ? "Paused or finished • tap Play to continue"
                : SystemClock.elapsedRealtime() < pendingUntil ? "Opening audio…" : "Stopped • tap Play to start or retry");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { updateControls(); handler.postDelayed(this, 500); }
    };

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        if (path != null) handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        StoragePermission.onResult(this, code, permissions, results);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        StoragePermission.onDestroy(this);
        LocalThumb.cancel(art);
        super.onDestroy();
    }
}
