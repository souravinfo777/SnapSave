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
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;

import com.snapsave.app.R;
import com.snapsave.app.ui.MusicPlayerActivity;

/**
 * Foreground music playback so audio continues with the screen off and offers play/pause/stop
 * from the notification. The player lives in this service, not in the activity.
 */
public class MusicService extends Service {

    private static final String CHANNEL = "music_playback";
    private static final int NOTIFICATION_ID = 43;

    private static final String ACTION_PLAY = "com.snapsave.app.music.PLAY";
    private static final String ACTION_TOGGLE = "com.snapsave.app.music.TOGGLE";
    private static final String ACTION_STOP = "com.snapsave.app.music.STOP";
    private static final String ACTION_SEEK = "com.snapsave.app.music.SEEK";

    private static volatile MusicService instance;

    private MediaPlayer player;
    private AudioManager audioManager;
    /** Opaque holder: AudioFocusRequest only exists on API 26+, so the field must not name it. */
    private Object focusRequest;
    private BroadcastReceiver noisyReceiver;
    private boolean prepared;
    private boolean playing;
    private String path;
    private String title = "Audio";
    private String author = "SnapSave";
    private int grad;
    private int pendingSeekMs = -1;

    // ------------------------------------------------------------ static controls

    public static void play(Context context, String path, String title, String author, int grad) {
        Intent intent = new Intent(context, MusicService.class)
                .setAction(ACTION_PLAY)
                .putExtra("path", path)
                .putExtra("title", title)
                .putExtra("author", author)
                .putExtra("grad", grad);
        start(context, intent);
    }

    public static void toggle(Context context) {
        start(context, new Intent(context, MusicService.class).setAction(ACTION_TOGGLE));
    }

    public static void seek(Context context, int positionMs) {
        start(context, new Intent(context, MusicService.class)
                .setAction(ACTION_SEEK).putExtra("position", positionMs));
    }

    public static void stop(Context context) {
        start(context, new Intent(context, MusicService.class).setAction(ACTION_STOP));
    }

    private static void start(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Starting from the background can be refused; the activity path is always allowed.
        }
    }

    public static boolean isPlaying() {
        MusicService s = instance;
        return s != null && s.playing;
    }

    public static boolean isPrepared() {
        MusicService s = instance;
        return s != null && s.prepared;
    }

    public static int position() {
        MusicService s = instance;
        if (s == null || s.player == null || !s.prepared) {
            return 0;
        }
        try {
            return s.player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public static int duration() {
        MusicService s = instance;
        if (s == null || s.player == null || !s.prepared) {
            return 0;
        }
        try {
            return s.player.getDuration();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public static String currentPath() {
        MusicService s = instance;
        return s == null ? null : s.path;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                prepared = true;
                if (pendingSeekMs > 0) {
                    mp.seekTo(pendingSeekMs);
                    pendingSeekMs = -1;
                }
                if (playing) {
                    mp.start();
                }
                notifyChanged();
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                playing = false;
                notifyChanged();
            }
        });
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                playing = false;
                prepared = false;
                notifyChanged();
                return true;
            }
        });
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        noisyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                pause();
            }
        };
        registerReceiver(noisyReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_PLAY.equals(action)) {
                startTrack(intent.getStringExtra("path"),
                        intent.getStringExtra("title"),
                        intent.getStringExtra("author"),
                        intent.getIntExtra("grad", 0));
            } else if (ACTION_TOGGLE.equals(action)) {
                if (playing) {
                    pause();
                } else {
                    resume();
                }
            } else if (ACTION_STOP.equals(action)) {
                stopPlayback();
                return START_NOT_STICKY;
            } else if (ACTION_SEEK.equals(action)) {
                seekTo(intent.getIntExtra("position", 0));
            }
        }
        return START_NOT_STICKY;
    }

    private void startTrack(String newPath, String newTitle, String newAuthor, int newGrad) {
        if (newPath == null || newPath.isEmpty()) {
            return;
        }
        if (newTitle != null && !newTitle.isEmpty()) {
            title = newTitle;
        }
        if (newAuthor != null && !newAuthor.isEmpty()) {
            author = newAuthor;
        }
        grad = newGrad;
        if (newPath.equals(path) && prepared) {
            playing = true;
            player.start();
            requestFocus();
            notifyChanged();
            return;
        }
        path = newPath;
        prepared = false;
        playing = true;
        pendingSeekMs = -1;
        try {
            player.reset();
            player.setDataSource(newPath);
            player.prepareAsync();
            requestFocus();
        } catch (Exception e) {
            playing = false;
            prepared = false;
        }
        notifyChanged();
    }

    private void resume() {
        if (player == null || !prepared) {
            return;
        }
        try {
            player.start();
            playing = true;
            requestFocus();
        } catch (IllegalStateException ignored) {
        }
        notifyChanged();
    }

    private void pause() {
        if (player != null && prepared) {
            try {
                player.pause();
            } catch (IllegalStateException ignored) {
            }
        }
        playing = false;
        notifyChanged();
    }

    private void seekTo(int positionMs) {
        if (player == null) {
            return;
        }
        if (prepared) {
            try {
                player.seekTo(Math.max(0, positionMs));
            } catch (IllegalStateException ignored) {
            }
        } else {
            pendingSeekMs = Math.max(0, positionMs);
        }
    }

    private void stopPlayback() {
        playing = false;
        prepared = false;
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.reset();
        }
        abandonFocus();
        stopForeground(true);
        stopSelf();
    }

    private void notifyChanged() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification());
        }
    }

    private void requestFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = FocusApi26.request(audioManager, focusListener);
            } else {
                FocusApi26.request(audioManager, focusListener, focusRequest);
            }
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonFocus() {
        if (audioManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) {
                FocusApi26.abandon(audioManager, focusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    /**
     * API 26+ audio focus is isolated here. The class is not loaded on Android 7 or older,
     * so the missing AudioFocusRequest type can never fail class verification there.
     */
    private static final class FocusApi26 {
        static Object request(AudioManager manager,
                              AudioManager.OnAudioFocusChangeListener listener) {
            AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build();
            manager.requestAudioFocus(request);
            return request;
        }

        static void request(AudioManager manager,
                            AudioManager.OnAudioFocusChangeListener listener, Object previous) {
            if (previous instanceof AudioFocusRequest) {
                manager.requestAudioFocus((AudioFocusRequest) previous);
            } else {
                request(manager, listener);
            }
        }

        static void abandon(AudioManager manager, Object request) {
            if (request instanceof AudioFocusRequest) {
                manager.abandonAudioFocusRequest((AudioFocusRequest) request);
            }
        }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        pause();
                    }
                }
            };

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "Music playback",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Controls for audio playing in the background.");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        Intent open = new Intent(this, MusicPlayerActivity.class)
                .putExtra("path", path)
                .putExtra("title", title)
                .putExtra("author", author)
                .putExtra("grad", grad)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Intent toggle = new Intent(this, MusicService.class).setAction(ACTION_TOGGLE);
        PendingIntent toggleIntent = PendingIntent.getService(this, 1, toggle, flags);
        Intent stop = new Intent(this, MusicService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(title)
                .setContentText(author)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "Pause" : "Play", toggleIntent)
                .addAction(R.drawable.ic_close, "Stop", stopIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        instance = null;
        abandonFocus();
        if (noisyReceiver != null) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {
            }
        }
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}