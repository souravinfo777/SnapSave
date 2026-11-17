package com.snapsave.app.ui;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadManager;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaTypes;
import com.snapsave.app.util.LocalThumb;
import com.snapsave.app.util.MediaExporter;
import com.snapsave.app.util.Util;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DownloadsScreen extends Screen {

    private final DownloadManager dm;
    private LinearLayout downloadingBox;
    private LinearLayout completedBox;
    private TextView completedHeader;
    private TextView tabDownloading;
    private TextView tabCompleted;
    private TextView speedView;
    private TextView speedUnit;
    private TextView statSub;
    private TextView storageView;
    private ProgressBar statProgress;
    private TextView pauseAll;

    private boolean showCompleted = false;
    private String lastCompletedSignature = "";
    private long lastStorageUpdate;

    public DownloadsScreen(MainActivity act) {
        super(act, R.layout.fragment_downloads);
        dm = App.get().downloads();

        downloadingBox = (LinearLayout) root.findViewById(R.id.downloading_container);
        completedBox = (LinearLayout) root.findViewById(R.id.completed_container);
        completedHeader = (TextView) root.findViewById(R.id.completed_header);
        tabDownloading = (TextView) root.findViewById(R.id.dtab_downloading);
        tabCompleted = (TextView) root.findViewById(R.id.dtab_completed);
        speedView = (TextView) root.findViewById(R.id.stat_speed);
        speedUnit = (TextView) root.findViewById(R.id.stat_speed_unit);
        statSub = (TextView) root.findViewById(R.id.stat_sub);
        storageView = (TextView) root.findViewById(R.id.stat_storage);
        statProgress = (ProgressBar) root.findViewById(R.id.stat_progress);
        pauseAll = (TextView) root.findViewById(R.id.pause_all);

        tabDownloading.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCompleted = false;
                updateTabs();
                refresh();
            }
        });
        tabCompleted.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCompleted = true;
                updateTabs();
                refresh();
            }
        });
        pauseAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Derive the action from real task state, not from the button's own label:
                // a translated label made the first tap call resumeAll by mistake.
                if (anyRunning()) {
                    dm.pauseAll();
                } else {
                    dm.resumeAll();
                }
            }
        });

        dm.addListener(listener);
        updateTabs();
        refresh();
    }

    private final DownloadManager.Listener listener = new DownloadManager.Listener() {
        @Override
        public void onDownloadsChanged() {
            refresh();
        }
    };

    private boolean destroyed = false;

    @Override
    public void destroy() {
        destroyed = true;
        dm.removeListener(listener);
    }

    /** True when at least one task can still be paused (running or queued). */
    private boolean anyRunning() {
        for (DownloadTask t : dm.active()) {
            if (t.state == DownloadTask.State.DOWNLOADING || t.state == DownloadTask.State.QUEUED
                    || t.state == DownloadTask.State.WAITING_NETWORK) {
                return true;
            }
        }
        return false;
    }

    private void updateTabs() {
        int on = act.getResources().getColor(R.color.primary);
        int off = act.getResources().getColor(R.color.text_tertiary);
        tabDownloading.setTextColor(showCompleted ? off : on);
        tabCompleted.setTextColor(showCompleted ? on : off);
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        if (destroyed) {
            return;
        }
        List<DownloadTask> active = dm.active();
        List<DownloadTask> done = new ArrayList<DownloadTask>(dm.completed());
        Collections.sort(done, new Comparator<DownloadTask>() {
            @Override
            public int compare(DownloadTask a, DownloadTask b) {
                return Long.compare(b.completedAt, a.completedAt);
            }
        });

        pauseAll.setText(act.getString(anyRunning() ? R.string.pause_all : R.string.resume_all));
        tabDownloading.setText("Downloading (" + active.size() + ")");

        // Active rows are few and their progress changes on every tick, so they are rebuilt.
        downloadingBox.removeAllViews();

        double totalSpeed = 0;
        int activeNow = 0;
        int waiting = 0;
        long totalDone = 0, totalAll = 0;
        for (DownloadTask t : active) {
            downloadingBox.addView(buildRow(downloadingBox, t));
            if (t.state == DownloadTask.State.DOWNLOADING) {
                totalSpeed += t.speed;
                activeNow++;
            } else if (t.state == DownloadTask.State.QUEUED
                    || t.state == DownloadTask.State.WAITING_NETWORK) {
                waiting++;
            }
            totalDone += t.done();
            totalAll += t.total();
        }

        completedHeader.setText(done.isEmpty() ? "No completed downloads" : "Completed (" + done.size() + ")");

        // A long completed history does not change while a file downloads. Re-inflating it on
        // every 400 ms progress callback made the whole screen stutter on big libraries.
        String signature = completedSignature(done);
        if (!signature.equals(lastCompletedSignature)) {
            lastCompletedSignature = signature;
            completedBox.removeAllViews();
            String previousDay = null;
            for (DownloadTask t : done) {
                String day = completionDay(t.completedAt);
                if (!day.equals(previousDay)) {
                    TextView header = new TextView(act);
                    header.setText(day);
                    header.setTextColor(act.getResources().getColor(R.color.text_primary));
                    header.setTextSize(18);
                    header.setTypeface(null, android.graphics.Typeface.BOLD);
                    header.setPadding(0, Util.dp(act, 16), 0, Util.dp(act, 8));
                    completedBox.addView(header);
                    previousDay = day;
                }
                completedBox.addView(buildRow(completedBox, t));
            }
        }

        String sp = Util.speed(totalSpeed);
        if (sp == null || sp.isEmpty()) {
            speedView.setText("0.0");
            speedUnit.setText("KB/s");
        } else {
            int cut = sp.lastIndexOf(' ');
            speedView.setText(cut > 0 ? sp.substring(0, cut) : sp);
            speedUnit.setText(cut > 0 ? sp.substring(cut + 1) : "");
        }
        statSub.setText(activeNow + " active • " + waiting + " waiting • " + netLabel());
        int pct = totalAll > 0 ? (int) (totalDone * 100 / totalAll) : 0;
        statProgress.setProgress(pct);

        long now = System.currentTimeMillis();
        if (now - lastStorageUpdate > 5000) {
            lastStorageUpdate = now;
            long free = 0;
            try {
                StatFs sf = new StatFs(dm.dir().getAbsolutePath());
                free = sf.getAvailableBytes();
            } catch (Exception ignored) {
            }
            storageView.setText("Storage " + Util.bytes(free) + " free");
        }

        View dlSection = downloadingBox;
        dlSection.setVisibility(showCompleted ? View.GONE : View.VISIBLE);
        completedHeader.setVisibility(showCompleted ? View.VISIBLE : View.GONE);
        completedBox.setVisibility(showCompleted ? View.VISIBLE : View.GONE);
    }

    /** Cheap identity of the completed list: task id, completion time and published size. */
    private static String completedSignature(List<DownloadTask> done) {
        StringBuilder sb = new StringBuilder();
        for (DownloadTask t : done) {
            sb.append(t.id).append(':').append(t.completedAt).append(':')
                    .append(t.outFile == null ? 0 : t.outFile.length()).append('|');
        }
        return sb.toString();
    }

    private String netLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                return "Offline";
            }
            return ni.getType() == ConnectivityManager.TYPE_WIFI ? "Wi-Fi" : "Mobile data";
        } catch (Exception e) {
            return "Network";
        }
    }

    private View buildRow(ViewGroup parent, final DownloadTask t) {
        View row = LayoutInflater.from(act).inflate(R.layout.item_video, parent, false);
        ((View) row.findViewById(R.id.thumb_bg)).setBackgroundResource(Util.grad(t.grad));
        ((TextView) row.findViewById(R.id.thumb_duration)).setText(t.label);
        ((TextView) row.findViewById(R.id.video_title)).setText(t.title);
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showRowActions(t);
                return true;
            }
        });

        TextView meta = (TextView) row.findViewById(R.id.video_meta);
        ProgressBar pb = (ProgressBar) row.findViewById(R.id.video_progress);
        final ImageView icon = (ImageView) row.findViewById(R.id.video_action_icon);
        FrameLayout action = (FrameLayout) row.findViewById(R.id.video_action);

        switch (t.state) {
            case COMPLETED:
                pb.setVisibility(View.GONE);
                LocalThumb.load((ImageView) row.findViewById(R.id.thumb_bg), t.outFile,
                        MediaTypes.mediaType(t), Util.grad(t.grad));
                String warning = completedWarning(t);
                meta.setMaxLines(warning.isEmpty() ? 1 : 4);
                meta.setText(Util.bytes(t.outFile != null ? t.outFile.length() : 0) + " • Completed"
                        + (warning.isEmpty() ? "" : "\n" + warning));
                icon.setImageResource(MediaTypes.isAudio(t) ? R.drawable.ic_music
                        : MediaTypes.isVideo(t) ? R.drawable.ic_play : R.drawable.ic_folder);
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPlayer(t);
                    }
                });
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPlayer(t);
                    }
                });
                break;
            case PAUSED:
                pb.setVisibility(View.VISIBLE);
                pb.setProgress(t.percent());
                boolean hasPauseReason = t.error != null && !t.error.trim().isEmpty();
                meta.setMaxLines(hasPauseReason ? 4 : 1);
                meta.setText("Paused • " + t.percent() + "%"
                        + (hasPauseReason ? "\n" + friendly(t.error) : ""));
                icon.setImageResource(R.drawable.ic_play);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.resume(t);
                    }
                });
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.resume(t);
                    }
                });
                break;
            case FAILED:
                pb.setVisibility(View.GONE);
                meta.setMaxLines(4);
                meta.setText("Failed • " + friendly(t.error) + "\nTap to retry");
                icon.setImageResource(R.drawable.ic_download);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.resume(t);
                    }
                });
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.resume(t);
                    }
                });
                break;
            case MERGING:
            case CONVERTING:
                // The native mux/transcode step has no pause: offering a pause button that does
                // nothing is worse than saying what is happening.
                pb.setVisibility(View.VISIBLE);
                pb.setProgress(t.percent());
                meta.setText("Processing with native engine...");
                icon.setImageResource(R.drawable.ic_download);
                row.setOnClickListener(null);
                action.setOnClickListener(null);
                break;
            case QUEUED:
                pb.setVisibility(View.VISIBLE);
                pb.setIndeterminate(true);
                meta.setText(act.getString(R.string.queued_waiting));
                icon.setImageResource(R.drawable.ic_close);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.remove(t);   // drop it from the queue
                    }
                });
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.remove(t);   // drop it from the queue
                    }
                });
                break;
            case WAITING_NETWORK:
                pb.setVisibility(View.VISIBLE);
                pb.setIndeterminate(false);
                pb.setProgress(t.percent());
                meta.setMaxLines(5);
                meta.setText("Waiting for network/Wi-Fi • open SnapSave to continue if needed\nTap to pause");
                icon.setImageResource(R.drawable.ic_pause);
                View.OnClickListener pauseWaiting = new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.pause(t);
                    }
                };
                row.setOnClickListener(pauseWaiting);
                action.setOnClickListener(pauseWaiting);
                break;
            case DOWNLOADING:
                pb.setVisibility(View.VISIBLE);
                boolean known = t.total() > 0;
                pb.setIndeterminate(!known);
                if (known) {
                    pb.setProgress(t.percent());
                }
                String sp = t.speed > 0 ? Util.speed(t.speed) + " • " : "";
                if (known) {
                    meta.setText(sp + t.percent() + "% • " + Util.bytes(t.done()) + " of " + Util.bytes(t.total()));
                } else {
                    meta.setText(sp + Util.bytes(t.done()));
                }
                icon.setImageResource(R.drawable.ic_pause);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.pause(t);
                    }
                });
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dm.pause(t);
                    }
                });
                break;
        }
        return row;
    }

    private void showRowActions(final DownloadTask t) {
        final List<String> items = new ArrayList<String>();
        if (t.state == DownloadTask.State.COMPLETED) {
            items.add("Play");
            items.add("Share");
            items.add("Export to Gallery/Music");
            items.add("Delete");
        } else if (t.state == DownloadTask.State.FAILED || t.state == DownloadTask.State.PAUSED) {
            items.add("Resume");
            items.add("Delete");
        } else if (t.state == DownloadTask.State.QUEUED) {
            items.add("Remove from queue");
        } else {
            items.add("Pause");
            items.add("Delete");
        }
        new android.app.AlertDialog.Builder(act)
                .setTitle(t.title == null || t.title.isEmpty() ? "Download" : t.title)
                .setItems(items.toArray(new String[items.size()]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                String label = items.get(which);
                                if ("Play".equals(label)) {
                                    openPlayer(t);
                                } else if ("Resume".equals(label)) {
                                    dm.resume(t);
                                } else if ("Pause".equals(label)) {
                                    dm.pause(t);
                                } else if ("Share".equals(label)) {
                                    shareCompleted(t);
                                } else if (label.startsWith("Export")) {
                                    exportCompleted(t);
                                } else if ("Remove from queue".equals(label)) {
                                    dm.remove(t);
                                } else if ("Delete".equals(label)) {
                                    confirmDelete(t);
                                }
                            }
                        }).show();
    }

    private void confirmDelete(final DownloadTask t) {
        new android.app.AlertDialog.Builder(act).setTitle("Delete this download?")
                .setMessage("The partial or finished file will be removed from this device.")
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dm.remove(t);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void shareCompleted(DownloadTask t) {
        Uri uri = exportForShare(t);
        if (uri == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(MediaExporter.mimeType(t));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        act.startActivity(Intent.createChooser(send, "Share file"));
    }

    private void exportCompleted(DownloadTask t) {
        if (exportForShare(t) != null) {
            Toast.makeText(act, "Saved to the public SnapSave folder", Toast.LENGTH_SHORT).show();
        }
    }

    private Uri exportForShare(DownloadTask t) {
        if (MediaExporter.needsLegacyPermission() && Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1003);
            Toast.makeText(act, "Allow storage access, then try again.", Toast.LENGTH_SHORT).show();
            return null;
        }
        try {
            return MediaExporter.export(act, t);
        } catch (Exception e) {
            Toast.makeText(act, e.getMessage() == null ? "Export failed." : e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private String completionDay(long timestamp) {
        if (timestamp <= 0) {
            return "Completion date unknown";
        }
        Calendar completed = Calendar.getInstance();
        completed.setTimeInMillis(timestamp);
        Calendar today = Calendar.getInstance();
        if (completed.get(Calendar.ERA) == today.get(Calendar.ERA)
                && completed.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && completed.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return "Today";
        }
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (completed.get(Calendar.ERA) == today.get(Calendar.ERA)
                && completed.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && completed.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(timestamp));
    }

    private static String completedWarning(DownloadTask task) {
        String warning = task.warning == null ? "" : task.warning.trim();
        String legacy = task.error == null ? "" : task.error.trim();
        String message = warning.isEmpty() ? "" : friendly(warning);
        if (!legacy.isEmpty() && !legacy.equals(warning)) {
            message += (message.isEmpty() ? "" : " • ") + friendly(legacy);
        }
        return message;
    }

    private static String friendly(String e) {
        if (e == null || e.trim().isEmpty()) {
            return "tap to retry";
        }
        String m = e.toLowerCase(Locale.US);
        if (m.contains("background_start_blocked")) {
            return "Background start blocked — open SnapSave and tap to resume";
        }
        if (m.contains("video_only_no_audio")) {
            return "Video saved without an audio track";
        }
        if (m.contains("link_expired") || m.contains("expired")) {
            return "link expired — open the page again to retry";
        }
        if (m.contains("insecure")) {
            return "only https links are supported";
        }
        if (m.contains("merge_failed") || m.contains("mux_failed")) {
            return "audio merge failed";
        }
        if (m.contains("mp3_converter_unavailable") || m.contains("native_engine_unavailable")
                || m.contains("conversion_failed")) {
            return "media processing unavailable or failed";
        }
        if (m.contains("416") || m.contains("range mismatch")) {
            return "server sent a bad range — tap to retry";
        }
        if (m.contains("incomplete")) {
            return "download was cut short — tap to retry";
        }
        if (m.contains("abort") || m.contains("reset") || m.contains("timeout") || m.contains("timed out")
                || m.contains("eof") || m.contains("broken pipe") || m.contains("connection")
                || m.contains("network") || m.contains("socket")) {
            return "network dropped — tap to retry";
        }
        if (m.contains("403")) {
            return "blocked by server (403) — tap to retry";
        }
        if (m.contains("404")) {
            return "link expired (404) — tap to retry";
        }
        if (m.contains("empty output")) {
            return "no data received — tap to retry";
        }
        return e;
    }

    private void openPlayer(DownloadTask t) {
        if (t.outFile == null || !t.outFile.exists()) {
            android.widget.Toast.makeText(act, "This file is no longer available.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (MediaTypes.isImage(t)) {
            android.widget.Toast.makeText(act, "Image opening is not supported here yet.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!MediaTypes.isAudio(t) && !MediaTypes.isVideo(t)) {
            android.widget.Toast.makeText(act, "This file type cannot be opened here.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(act, MediaTypes.isAudio(t)
                ? MusicPlayerActivity.class : PlayerActivity.class);
        i.putExtra("path", t.outFile.getAbsolutePath());
        i.putExtra("title", t.title);
        i.putExtra("author", t.author);
        i.putExtra("grad", t.grad);
        i.putExtra("mimeType", t.mimeType);
        i.putExtra("mediaType", MediaTypes.mediaType(t));
        act.startActivity(i);
    }
}
