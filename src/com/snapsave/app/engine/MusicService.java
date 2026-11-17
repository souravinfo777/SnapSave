package com.snapsave.app.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.ui.MusicPlayerActivity;

import java.io.File;

/** Single-track background playback, with platform media and lock-screen controls. */
public class MusicService extends Service {
    private static final String CHANNEL = "music_playback";
    private static final int NOTIFICATION_ID = 43;
    private static final String ACTION_PLAY = "com.snapsave.app.music.PLAY";
    private static final String ACTION_TOGGLE = "com.snapsave.app.music.TOGGLE";
    private static final String ACTION_STOP = "com.snapsave.app.music.STOP";
    private static final String ACTION_SEEK = "com.snapsave.app.music.SEEK";
    private static volatile MusicService instance;

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    // Kept opaque so API 21-25 never load AudioFocusRequest.
    private Object focusRequest;
    private BroadcastReceiver noisyReceiver;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean prepared, preparing, playing, playWhenReady, completed, foreground;
    private boolean hasFocus, resumeOnFocusGain;
    private String path;
    private String title = "Audio", author = "SnapSave";
    private int grad, pendingSeekMs = -1;

    public static void play(Context context, String path, String title, String author, int grad) {
        start(context, new Intent(context, MusicService.class).setAction(ACTION_PLAY)
                .putExtra("path", path).putExtra("title", title)
                .putExtra("author", author).putExtra("grad", grad));
    }

    public static void toggle(Context context) {
        if (instance != null) start(context, new Intent(context, MusicService.class).setAction(ACTION_TOGGLE));
    }

    public static void seek(Context context, int positionMs) {
        if (instance != null) start(context, new Intent(context, MusicService.class)
                .setAction(ACTION_SEEK).putExtra("position", positionMs));
    }

    public static void stop(Context context) {
        if (instance != null) start(context, new Intent(context, MusicService.class).setAction(ACTION_STOP));
    }

    private static void start(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException e) {
            Toast.makeText(context, "Could not start audio playback. Open the player and retry.", Toast.LENGTH_LONG).show();
        }
    }

    public static boolean isPlaying() { MusicService s = instance; return s != null && s.playing; }
    public static boolean isPreparing() { MusicService s = instance; return s != null && s.preparing; }
    public static boolean isPrepared() { MusicService s = instance; return s != null && s.prepared; }
    public static String currentPath() { MusicService s = instance; return s == null ? null : s.path; }
    public static String currentTitle() { MusicService s = instance; return s == null ? "Audio" : s.title; }
    public static String currentAuthor() { MusicService s = instance; return s == null ? "SnapSave" : s.author; }
    public static int currentGrad() { MusicService s = instance; return s == null ? 0 : s.grad; }

    public static int position() {
        MusicService s = instance;
        if (s == null || s.player == null || !s.prepared) return 0;
        try { return s.player.getCurrentPosition(); }
        catch (IllegalStateException e) { return 0; }
    }

    public static int duration() {
        MusicService s = instance;
        if (s == null || s.player == null || !s.prepared) return 0;
        try { return s.player.getDuration(); }
        catch (IllegalStateException e) { return 0; }
    }

    private AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createChannel();
        session = new MediaSession(this, "SnapSaveMusic");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(true); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long position) { seekTo((int) Math.min(Integer.MAX_VALUE, Math.max(0L, position))); }
        }, handler);
        session.setActive(true);
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { pause(true); }
        };
        // This receiver only listens to a protected system broadcast (also valid on API 33+).
        registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService command is acknowledged, including an invalid/stop command.
        if (!promote()) return START_NOT_STICKY;
        if (intent == null) { stopPlayback(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            startTrack(intent.getStringExtra("path"), intent.getStringExtra("title"),
                    intent.getStringExtra("author"), intent.getIntExtra("grad", 0));
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else if (path == null || player == null) {
            stopPlayback();
        } else if (ACTION_TOGGLE.equals(action)) {
            if (playing || (preparing && playWhenReady)) pause(true);
            else resume();
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getIntExtra("position", 0));
        } else notifyChanged();
        return START_NOT_STICKY;
    }

    private void startTrack(String newPath, String newTitle, String newAuthor, int newGrad) {
        if (newPath == null || newPath.trim().isEmpty()) { fail("No audio file was selected."); return; }
        Uri uri = Uri.parse(newPath);
        if (uri.getScheme() == null) uri = Uri.fromFile(new File(newPath));
        // Resolve legacy raw paths to the same canonical URI used by the library.
        for (DownloadTask t : App.get().downloads().completed()) {
            if (uri.equals(MediaFiles.uri(t)) || (t.outFile != null && uri.equals(Uri.fromFile(t.outFile)))) {
                uri = MediaFiles.uri(t);
                newTitle = t.title;
                newAuthor = t.author;
                newGrad = t.grad;
                break;
            }
        }
        if (uri == null) { fail("This audio file is no longer available."); return; }
        String canonicalPath = uri.toString();
        if (canonicalPath.equals(path) && player != null && (prepared || preparing)) { resume(); return; }
        releasePlayer();
        abandonFocus();
        path = canonicalPath;
        title = newTitle == null || newTitle.trim().isEmpty() ? "Audio" : newTitle;
        author = newAuthor == null || newAuthor.trim().isEmpty() ? "SnapSave" : newAuthor;
        grad = newGrad;
        prepared = false;
        preparing = true;
        playing = false;
        completed = false;
        playWhenReady = true;
        resumeOnFocusGain = false;
        pendingSeekMs = -1;
        final MediaPlayer next = new MediaPlayer();
        player = next;
        next.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                if (mp != player) return;
                handler.removeCallbacks(prepareTimeout);
                preparing = false;
                prepared = true;
                if (pendingSeekMs >= 0) {
                    mp.seekTo(Math.min(pendingSeekMs, Math.max(0, duration() - 1)));
                    pendingSeekMs = -1;
                }
                if (playWhenReady) resume();
                else notifyChanged();
            }
        });
        next.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) {
                if (mp != player) return;
                completed = true;
                playing = false;
                playWhenReady = false;
                resumeOnFocusGain = false;
                abandonFocus();
                notifyChanged();
            }
        });
        next.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
            @Override public void onSeekComplete(MediaPlayer mp) { if (mp == player) notifyChanged(); }
        });
        next.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                if (mp == player) fail("Cannot play this audio file. Try opening it with another app.");
                return true;
            }
        });
        try {
            next.setAudioAttributes(attributes());
            next.setDataSource(this, uri);
            next.prepareAsync();
            handler.postDelayed(prepareTimeout, 30000);
            notifyChanged();
        } catch (Exception e) { fail("Cannot open this audio file. It may be missing or inaccessible."); }
    }

    private final Runnable prepareTimeout = new Runnable() {
        @Override public void run() { if (preparing) fail("Audio preparation timed out. Tap Play to retry."); }
    };

    private void resume() {
        if (player == null || path == null) { stopPlayback(); return; }
        playWhenReady = true;
        resumeOnFocusGain = false;
        if (!promote()) return;
        if (preparing) { notifyChanged(); return; }
        if (!prepared) { fail("Audio is not ready. Reopen the file to retry."); return; }
        if (!requestFocus()) {
            playWhenReady = false;
            playing = false;
            Toast.makeText(this, "Audio focus is unavailable. Pause other audio, then tap Play.", Toast.LENGTH_LONG).show();
            notifyChanged();
            return;
        }
        try {
            player.setVolume(1f, 1f);
            if (completed) { player.seekTo(0); completed = false; }
            player.start();
            playing = true;
            notifyChanged();
        } catch (RuntimeException e) { fail("Audio playback failed. Tap Play to retry."); }
    }

    private void pause(boolean abandon) {
        playWhenReady = false;
        if (abandon) { resumeOnFocusGain = false; abandonFocus(); }
        if (player != null && prepared && playing) {
            try { player.pause(); }
            catch (IllegalStateException e) { fail("Audio playback was interrupted."); return; }
        }
        playing = false;
        notifyChanged();
    }

    private void seekTo(int positionMs) {
        if (player == null) { stopPlayback(); return; }
        if (prepared) {
            try {
                int target = Math.max(0, Math.min(positionMs, Math.max(0, duration() - 1)));
                completed = false;
                player.seekTo(target);
            } catch (IllegalStateException e) { fail("Could not seek in this audio file."); return; }
        } else if (preparing) pendingSeekMs = Math.max(0, positionMs);
        notifyChanged();
    }

    private void releasePlayer() {
        handler.removeCallbacks(prepareTimeout);
        MediaPlayer old = player;
        player = null;
        if (old != null) old.release();
        prepared = false;
        preparing = false;
        playing = false;
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (session != null) session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_ERROR, 0, 0).setErrorMessage(message).build());
        stopPlayback();
    }

    private void stopPlayback() {
        playWhenReady = false;
        resumeOnFocusGain = false;
        path = null;
        releasePlayer();
        abandonFocus();
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0).build());
            session.setActive(false);
        }
        stopForeground(true);
        foreground = false;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private boolean promote() {
        if (foreground) return true;
        try {
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
            return true;
        } catch (RuntimeException e) {
            fail("Background audio playback is unavailable. Reopen the player to retry.");
            return false;
        }
    }

    private void notifyChanged() {
        if (path == null || session == null) return;
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, author)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_URI, path)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
        int state = preparing ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING
                : completed ? PlaybackState.STATE_STOPPED : PlaybackState.STATE_PAUSED;
        long supported = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
        if (prepared && duration() > 0) supported |= PlaybackState.ACTION_SEEK_TO;
        session.setPlaybackState(new PlaybackState.Builder().setActions(supported)
                .setState(state, position(), playing ? 1f : 0f, SystemClock.elapsedRealtime()).build());
        session.setActive(true);
        boolean active = playing || (preparing && playWhenReady);
        if (active && !promote()) return;
        if (!active && foreground) {
            stopForeground(false);
            foreground = false;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification());
    }

    private boolean requestFocus() {
        if (hasFocus) return true;
        if (audioManager == null) return false;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = FocusApi26.create(attributes(), focusListener);
            result = FocusApi26.request(audioManager, focusRequest);
        } else result = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) abandonFocus();
        return hasFocus;
    }

    private void abandonFocus() {
        hasFocus = false;
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) FocusApi26.abandon(audioManager, focusRequest);
        } else audioManager.abandonAudioFocus(focusListener);
    }

    private static final class FocusApi26 {
        static Object create(AudioAttributes attributes, AudioManager.OnAudioFocusChangeListener listener) {
            return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(listener).build();
        }
        static int request(AudioManager manager, Object request) {
            return manager.requestAudioFocus((AudioFocusRequest) request);
        }
        static void abandon(AudioManager manager, Object request) {
            manager.abandonAudioFocusRequest((AudioFocusRequest) request);
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) {
            if (path == null || player == null) return;
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                hasFocus = true;
                try { player.setVolume(1f, 1f); } catch (IllegalStateException ignored) { }
                if (resumeOnFocusGain) resume();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                try { player.setVolume(0.2f, 0.2f); } catch (IllegalStateException ignored) { }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                hasFocus = false;
                resumeOnFocusGain = playing || (preparing && playWhenReady);
                pause(false);
            } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
                pause(true);
            }
        }
    };

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Controls for audio playing in the background.");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, MusicPlayerActivity.class).putExtra("path", path)
                .putExtra("title", title).putExtra("author", author).putExtra("grad", grad)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, MusicService.class).setAction(ACTION_TOGGLE), flags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, MusicService.class).setAction(ACTION_STOP), flags);
        boolean active = playing || (preparing && playWhenReady);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_music).setContentTitle(title)
                .setContentText(preparing ? "Preparing audio…" : author).setContentIntent(content)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(active).setOnlyAlertOnce(true).setDeleteIntent(stop)
                .addAction(active ? R.drawable.ic_pause : R.drawable.ic_play, active ? "Pause" : "Play", toggle)
                .addAction(R.drawable.ic_close, "Stop", stop);
        if (session != null) builder.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1));
        return builder.build();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        abandonFocus();
        if (noisyReceiver != null) {
            try { unregisterReceiver(noisyReceiver); } catch (IllegalArgumentException ignored) { }
        }
        releasePlayer();
        if (session != null) { session.setActive(false); session.release(); session = null; }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
