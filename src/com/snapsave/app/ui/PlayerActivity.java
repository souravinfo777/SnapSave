package com.snapsave.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaFiles;
import com.snapsave.app.engine.MediaTypes;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.LocalThumb;
import com.snapsave.app.util.MediaExporter;
import com.snapsave.app.util.Util;

import java.io.File;

public class PlayerActivity extends Activity {
    private VideoView video;
    private ImageView playIcon;
    private SeekBar seek;
    private TextView cur, total;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragging, prepared, completed, failed, foreground, fullscreen;
    private boolean playWhenReady = true;
    private int restorePosition;
    private Uri source;
    private DownloadTask task;
    private MediaActions actions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_player);
        Insets.applyToContent(this);
        String path = getIntent().getStringExtra("path");
        if (empty(path)) path = getIntent().getStringExtra("url");
        if (empty(path) && getIntent().getData() != null) path = getIntent().getData().toString();
        task = findTask(getIntent(), path);
        source = task == null ? sourceUri(path) : MediaFiles.uri(task);
        if (source == null) { toast("Nothing to play."); finish(); return; }
        String type = task == null ? MediaTypes.mediaType(getIntent().getStringExtra("mimeType"),
                getIntent().getStringExtra("mediaType"), null, path, null) : MediaTypes.mediaType(task);
        if (MediaTypes.AUDIO.equals(type)) {
            Intent audio = new Intent(this, MusicPlayerActivity.class);
            audio.putExtras(getIntent());
            audio.putExtra("path", source.toString());
            startActivity(audio);
            finish();
            return;
        }
        if (MediaTypes.IMAGE.equals(type)) { toast("Open this image from My Files."); finish(); return; }
        if ((task != null && !MediaFiles.exists(this, task)) || ("file".equals(source.getScheme())
                && (source.getPath() == null || !new File(source.getPath()).isFile()))) {
            toast("This file is no longer available."); finish(); return;
        }
        String title = task == null ? getIntent().getStringExtra("title") : task.title;
        String author = task == null ? getIntent().getStringExtra("author") : task.author;
        ((TextView) findViewById(R.id.player_title)).setText(empty(title) ? "Video" : title);
        ((TextView) findViewById(R.id.p_title)).setText(empty(title) ? "Video" : title);
        ((TextView) findViewById(R.id.p_meta)).setText(empty(author) ? "" : author);
        video = (VideoView) findViewById(R.id.video_view);
        playIcon = (ImageView) findViewById(R.id.play_pause_icon);
        seek = (SeekBar) findViewById(R.id.seek);
        cur = (TextView) findViewById(R.id.cur_time);
        total = (TextView) findViewById(R.id.total_time);
        seek.setEnabled(false);
        if (state != null) {
            restorePosition = state.getInt("position");
            playWhenReady = state.getBoolean("playing", false);
        }
        actions = new MediaActions(this, task, source, getIntent().getStringExtra("mimeType"));
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        findViewById(R.id.player_more).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { actions.more(); }
        });
        findViewById(R.id.player_fullscreen).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setFullscreen(!fullscreen); }
        });
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                prepared = true;
                failed = false;
                seek.setEnabled(true);
                mp.setLooping(false);
                total.setText(Util.ms(mp.getDuration()));
                if (restorePosition > 0) { video.seekTo(restorePosition); restorePosition = 0; }
                if (playWhenReady && foreground) video.start();
                updatePlayState();
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) {
                completed = true;
                playWhenReady = false;
                seek.setProgress(100);
                cur.setText(total.getText());
                updatePlayState();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                playbackError();
                return true;
            }
        });
        findViewById(R.id.play_pause).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (failed) { playWhenReady = true; openVideo(); return; }
                if (!prepared) { toast("Still preparing…"); return; }
                try {
                    if (video.isPlaying()) { video.pause(); playWhenReady = false; }
                    else {
                        if (completed) { video.seekTo(0); completed = false; }
                        video.start(); playWhenReady = true;
                    }
                    updatePlayState();
                } catch (RuntimeException e) { playbackError(); }
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && prepared && video.getDuration() > 0)
                    cur.setText(Util.ms((long) video.getDuration() * p / 100));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (prepared && video.getDuration() > 0) {
                    video.seekTo((int) ((long) video.getDuration() * s.getProgress() / 100));
                    completed = false;
                }
            }
        });
        buildActions();
        loadUpNext();
        openVideo();
        if (state != null && state.getBoolean("fullscreen")) setFullscreen(true);
    }

    private void openVideo() {
        prepared = false;
        completed = false;
        failed = false;
        seek.setEnabled(false);
        try { video.setVideoURI(source); }
        catch (RuntimeException e) { playbackError(); }
    }

    private void playbackError() {
        prepared = false;
        failed = true;
        playWhenReady = false;
        seek.setEnabled(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playIcon.setImageResource(R.drawable.ic_play);
        findViewById(R.id.play_pause).setContentDescription("Retry playback");
        toast("Cannot play this file. Tap Play to retry or use More to open another app.");
    }

    private void updatePlayState() {
        boolean playing = prepared && video.isPlaying();
        playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        findViewById(R.id.play_pause).setContentDescription(playing ? "Pause" : completed ? "Replay" : "Play");
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (prepared && !dragging && !completed) {
                int pos = video.getCurrentPosition(), dur = video.getDuration();
                if (dur > 0) seek.setProgress((int) ((long) pos * 100 / dur));
                cur.setText(Util.ms(pos));
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (video != null) { handler.removeCallbacks(tick); handler.post(tick); }
    }

    @Override protected void onPause() {
        foreground = false;
        handler.removeCallbacks(tick);
        if (video != null) {
            if (prepared) {
                if (video.isPlaying()) video.pause();
                updatePlayState();
            }
            playWhenReady = false;
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (video != null) state.putInt("position", prepared ? video.getCurrentPosition() : restorePosition);
        state.putBoolean("playing", playWhenReady);
        state.putBoolean("fullscreen", fullscreen);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (fullscreen) setFullscreen(false);
        else super.onBackPressed();
    }

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        findViewById(R.id.player_toolbar).setVisibility(enabled ? View.GONE : View.VISIBLE);
        findViewById(R.id.player_details).setVisibility(enabled ? View.GONE : View.VISIBLE);
        View surface = findViewById(R.id.player_surface);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) surface.getLayoutParams();
        params.height = enabled ? 0 : Util.dp(this, 220);
        params.weight = enabled ? 1 : 0;
        params.topMargin = enabled ? 0 : Util.dp(this, 8);
        surface.setLayoutParams(params);
        video.setLayoutParams(video.getLayoutParams());
        findViewById(R.id.player_fullscreen).setContentDescription(enabled ? "Exit fullscreen" : "Fullscreen");
        getWindow().getDecorView().setSystemUiVisibility(enabled
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        StoragePermission.onResult(this, code, permissions, results);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        StoragePermission.onDestroy(this);
        if (video != null) video.stopPlayback();
        super.onDestroy();
    }

    private void buildActions() {
        LinearLayout row = (LinearLayout) findViewById(R.id.action_row);
        row.removeAllViews();
        String[] labels = {"Share", "Save", "More"};
        int[] icons = {R.drawable.ic_share, R.drawable.ic_download, R.drawable.ic_more};
        for (int i = 0; i < labels.length; i++) {
            if (i == 1 && task == null) continue;
            final int action = i;
            View v = LayoutInflater.from(this).inflate(R.layout.item_action, row, false);
            ((ImageView) v.findViewById(R.id.action_icon)).setImageResource(icons[i]);
            ((TextView) v.findViewById(R.id.action_label)).setText(labels[i]);
            View.OnClickListener click = new View.OnClickListener() {
                @Override public void onClick(View v) { if (action == 2) actions.more(); else actions.run(action); }
            };
            v.setOnClickListener(click);
            v.findViewById(R.id.action_circle).setOnClickListener(click);
            v.findViewById(R.id.action_circle).setContentDescription(labels[i]);
            row.addView(v);
        }
    }

    private void loadUpNext() {
        LinearLayout container = (LinearLayout) findViewById(R.id.upnext_container);
        container.removeAllViews();
        for (final DownloadTask t : App.get().downloads().completed()) {
            final Uri uri = MediaFiles.uri(t);
            if (uri == null || uri.equals(source) || !MediaTypes.isVideo(t) || !MediaFiles.exists(this, t)) continue;
            View row = LayoutInflater.from(this).inflate(R.layout.item_video, container, false);
            LocalThumb.load((ImageView) row.findViewById(R.id.thumb_bg), t, Util.grad(t.grad));
            ((TextView) row.findViewById(R.id.thumb_duration)).setText(t.label == null ? "" : t.label);
            ((TextView) row.findViewById(R.id.video_title)).setText(t.title);
            ((TextView) row.findViewById(R.id.video_meta)).setText(Util.bytes(t.outputSize > 0
                    ? t.outputSize : MediaFiles.size(this, t)));
            row.findViewById(R.id.video_action).setVisibility(View.GONE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(PlayerActivity.this, PlayerActivity.class);
                    i.putExtra("path", uri.toString());
                    i.putExtra("taskId", t.id);
                    i.putExtra("title", t.title);
                    i.putExtra("author", t.author);
                    i.putExtra("grad", t.grad);
                    i.putExtra("mimeType", t.mimeType);
                    i.putExtra("mediaType", MediaTypes.mediaType(t));
                    startActivity(i);
                    finish();
                }
            });
            container.addView(row);
        }
        findViewById(R.id.upnext_title).setVisibility(container.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    static boolean empty(String text) { return text == null || text.trim().isEmpty(); }

    static Uri sourceUri(String path) {
        if (empty(path)) return null;
        Uri uri = Uri.parse(path);
        return uri.getScheme() == null ? Uri.fromFile(new File(path)) : uri;
    }

    /** Canonical output URI matching, with the old raw-file extra and task-ID extras retained. */
    static DownloadTask findTask(Intent intent, String path) {
        Uri source = sourceUri(path);
        for (DownloadTask t : App.get().downloads().completed()) {
            Uri uri = MediaFiles.uri(t);
            if (source != null && (source.equals(uri) || (t.outFile != null
                    && source.equals(Uri.fromFile(t.outFile))))) return t;
        }
        String id = intent.getStringExtra("taskId");
        if (empty(id)) id = intent.getStringExtra("id");
        if (!empty(id)) {
            DownloadTask t = App.get().downloads().findById(id);
            if (t != null && t.state == DownloadTask.State.COMPLETED) return t;
        }
        return null;
    }

    /** Shared only by the two player screens; publication always goes through MediaExporter. */
    static final class MediaActions {
        private final Activity activity;
        private final DownloadTask task;
        private final Uri source;
        private final String mime;
        private boolean busy;

        MediaActions(Activity activity, DownloadTask task, Uri source, String mime) {
            this.activity = activity;
            this.task = task;
            this.source = source;
            this.mime = task != null ? MediaExporter.mimeType(task) : empty(mime) ? "video/*" : mime;
        }

        void more() {
            new AlertDialog.Builder(activity).setTitle("Playback options")
                    .setItems(new String[]{"Open with another app", "File details"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) run(2);
                            else details();
                        }
                    }).show();
        }

        void details() {
            String name = task == null ? source.getLastPathSegment() : MediaFiles.displayName(activity, task);
            StringBuilder text = new StringBuilder(empty(name) ? "Media file" : name);
            text.append("\nType: ").append(mime);
            if (task != null) text.append("\nSize: ").append(Util.bytes(MediaFiles.size(activity, task)));
            text.append("\nLocation: ").append(source);
            new AlertDialog.Builder(activity).setTitle("File details").setMessage(text.toString())
                    .setPositiveButton("OK", null).show();
        }

        // 0 = share, 1 = save/reuse public output, 2 = open externally.
        void run(final int action) {
            if (busy) return;
            if (task == null) {
                if (action == 1) { message("Save downloaded files from My Files."); return; }
                if (source == null) { message("This file is no longer available."); return; }
                if ("file".equals(source.getScheme())) { message("Open this file from My Files to share or export it."); return; }
                launch(source, action);
                return;
            }
            Runnable access = new Runnable() {
                @Override public void run() {
                    if (busy || activity.isFinishing() || activity.isDestroyed()) return;
                    busy = true;
                    final Context context = activity.getApplicationContext();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                final Uri uri = MediaExporter.export(context, task);
                                if (uri == null) throw new IllegalStateException("Could not access this file.");
                                activity.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        busy = false;
                                        if (activity.isFinishing() || activity.isDestroyed()) return;
                                        if (action == 1) message("Saved file is available on your device.\n" + uri);
                                        else launch(uri, action);
                                    }
                                });
                            } catch (final Exception e) {
                                activity.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        busy = false;
                                        if (!activity.isFinishing() && !activity.isDestroyed())
                                            message(empty(e.getMessage()) ? "Could not access this file." : e.getMessage());
                                    }
                                });
                            }
                        }
                    }, "player-export").start();
                }
            };
            if (!empty(task.outputUri)) access.run();
            else StoragePermission.run(activity, access);
        }

        private void launch(Uri uri, int action) {
            Intent intent;
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            if (action == 0) {
                intent = new Intent(Intent.ACTION_SEND).setType(web ? "text/plain" : mime);
                if (web) intent.putExtra(Intent.EXTRA_TEXT, uri.toString());
                else intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime);
            if (!web) {
                intent.setClipData(ClipData.newRawUri("Downloaded file", uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            try { activity.startActivity(Intent.createChooser(intent, action == 0 ? "Share file" : "Open file")); }
            catch (RuntimeException e) { message("No app can " + (action == 0 ? "share" : "open") + " this file."); }
        }

        private void message(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    }
}
