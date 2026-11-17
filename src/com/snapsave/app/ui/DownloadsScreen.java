package com.snapsave.app.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadManager;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaFiles;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DownloadsScreen extends Screen {
    private final DownloadManager dm;
    private final LinearLayout downloadingBox, completedBox;
    private final TextView completedHeader, tabDownloading, tabCompleted;
    private final TextView speedView, speedUnit, statSub, storageView, statBytes, pauseAll;
    private final ProgressBar statProgress;
    private final Map<String, Row> activeRows = new HashMap<String, Row>();
    private boolean showCompleted;
    private boolean destroyed;
    private String lastCompletedSignature = "";
    private long lastStorageUpdate;
    private View emptyActive;

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
        statBytes = (TextView) root.findViewById(R.id.stat_bytes);
        statProgress = (ProgressBar) root.findViewById(R.id.stat_progress);
        pauseAll = (TextView) root.findViewById(R.id.pause_all);
        tabDownloading.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCompleted = false; updateTabs(); refresh(); }
        });
        tabCompleted.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCompleted = true; updateTabs(); refresh(); }
        });
        pauseAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (anyPausable()) pauseTransfers();
                else resumeTransfers(false);
            }
        });
        root.findViewById(R.id.downloads_more).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showBulkActions(); }
        });
        dm.addListener(listener);
        updateTabs();
    }

    private final DownloadManager.Listener listener = new DownloadManager.Listener() {
        @Override public void onDownloadsChanged() {
            if (!destroyed && act.currentTab() == 2 && root.isShown()) refresh();
        }
    };

    @Override public void destroy() {
        destroyed = true;
        dm.removeListener(listener);
        activeRows.clear();
    }

    @Override public void onShow() { refresh(); }

    private static boolean pausable(DownloadTask t) {
        return t.state == DownloadTask.State.DOWNLOADING || t.state == DownloadTask.State.QUEUED
                || t.state == DownloadTask.State.WAITING_NETWORK;
    }

    private boolean anyPausable() {
        for (DownloadTask t : dm.active()) if (pausable(t)) return true;
        return false;
    }

    private void pauseTransfers() {
        for (DownloadTask t : dm.active()) if (pausable(t)) dm.pause(t);
    }

    private void resumeTransfers(final boolean failedOnly) {
        StoragePermission.run(act, new Runnable() {
            @Override public void run() {
                for (DownloadTask t : dm.active()) {
                    if (failedOnly ? t.state == DownloadTask.State.FAILED
                            : t.state == DownloadTask.State.PAUSED) dm.resume(t);
                }
            }
        });
    }

    private void resume(final DownloadTask task) {
        StoragePermission.run(act, new Runnable() {
            @Override public void run() { dm.resume(task); }
        });
    }

    private void updateTabs() {
        int on = act.getResources().getColor(R.color.primary);
        int off = act.getResources().getColor(R.color.text_tertiary);
        tabDownloading.setTextColor(showCompleted ? off : on);
        tabCompleted.setTextColor(showCompleted ? on : off);
        downloadingBox.setVisibility(showCompleted ? View.GONE : View.VISIBLE);
        completedHeader.setVisibility(showCompleted ? View.VISIBLE : View.GONE);
        completedBox.setVisibility(showCompleted ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        if (destroyed) return;
        List<DownloadTask> active = dm.active();
        tabDownloading.setText("In progress (" + active.size() + ")");
        List<DownloadTask> done = dm.completed();
        tabCompleted.setText("Completed (" + done.size() + ")");
        boolean hasPaused = false;
        double speed = 0;
        long bytes = 0, total = 0;
        int transfers = 0, waiting = 0, processing = 0, saving = 0, paused = 0, failed = 0;
        boolean unknown = false;
        for (DownloadTask t : active) {
            if (t.state == DownloadTask.State.DOWNLOADING) { speed += t.speed; transfers++; }
            if (t.state == DownloadTask.State.QUEUED || t.state == DownloadTask.State.WAITING_NETWORK) waiting++;
            if (t.state == DownloadTask.State.MERGING || t.state == DownloadTask.State.CONVERTING) processing++;
            if (t.state == DownloadTask.State.PUBLISHING) saving++;
            if (t.state == DownloadTask.State.PAUSED) { hasPaused = true; paused++; }
            if (t.state == DownloadTask.State.FAILED) failed++;
            if (t.state == DownloadTask.State.DOWNLOADING || t.state == DownloadTask.State.QUEUED
                    || t.state == DownloadTask.State.PUBLISHING) {
                long n = t.state == DownloadTask.State.PUBLISHING ? t.publishTotal : t.total();
                long d = t.state == DownloadTask.State.PUBLISHING ? t.publishDone : t.done();
                bytes += Math.max(0, d);
                total += Math.max(0, n);
                if (!knownSize(t)) unknown = true;
            }
        }
        boolean canPause = anyPausable();
        pauseAll.setText(canPause ? "Pause transfers" : hasPaused ? "Resume paused downloads"
                : processing + saving > 0 ? "Finishing downloads…" : "No paused downloads");
        pauseAll.setEnabled(canPause || hasPaused);
        pauseAll.setAlpha(canPause || hasPaused ? 1f : 0.5f);
        String sp = Util.speed(speed);
        int cut = sp == null ? -1 : sp.lastIndexOf(' ');
        speedView.setText(cut > 0 ? sp.substring(0, cut) : "0");
        speedUnit.setText(cut > 0 ? sp.substring(cut + 1) : "B/s");
        String status = transfers + " downloading • " + waiting + " waiting";
        if (processing > 0) status += " • " + processing + " processing";
        if (saving > 0) status += " • " + saving + " saving";
        if (paused > 0) status += " • " + paused + " paused";
        if (failed > 0) status += " • " + failed + " failed";
        statSub.setText(status + "\n" + netLabel());
        statProgress.setIndeterminate(unknown || processing > 0 && total <= 0);
        statProgress.setProgress(percent(bytes, total));
        statBytes.setText(total > 0 && !unknown ? percent(bytes, total) + "% • " + Util.bytes(bytes)
                + " / " + Util.bytes(total) : bytes > 0 ? Util.bytes(bytes) + " transferred"
                : active.isEmpty() ? "Ready for your next download" : "Waiting for progress");
        long now = System.currentTimeMillis();
        if (now - lastStorageUpdate > 5000) {
            lastStorageUpdate = now;
            try {
                StatFs sf = new StatFs(android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
                storageView.setText(Util.bytes(sf.getAvailableBytes()) + " free • " + MediaFiles.locationLabel());
            } catch (Exception ignored) { storageView.setText("Saves to " + MediaFiles.locationLabel()); }
        }
        if (showCompleted) renderCompleted(done);
        else updateActiveRows(active);
    }

    private void updateActiveRows(List<DownloadTask> tasks) {
        Set<String> wanted = new HashSet<String>();
        for (DownloadTask t : tasks) wanted.add(t.id);
        for (String id : new ArrayList<String>(activeRows.keySet())) {
            if (!wanted.contains(id)) downloadingBox.removeView(activeRows.remove(id).view);
        }
        if (emptyActive != null && !tasks.isEmpty()) { downloadingBox.removeView(emptyActive); emptyActive = null; }
        for (int i = 0; i < tasks.size(); i++) {
            DownloadTask t = tasks.get(i);
            Row row = activeRows.get(t.id);
            if (row == null) {
                row = new Row(downloadingBox, t);
                activeRows.put(t.id, row);
                downloadingBox.addView(row.view, i);
            } else if (downloadingBox.indexOfChild(row.view) != i) {
                downloadingBox.removeView(row.view);
                downloadingBox.addView(row.view, i);
            }
            row.update(t);
        }
        if (tasks.isEmpty() && emptyActive == null) {
            emptyActive = note("No downloads in progress.\nFind a video or paste a link in Browse.");
            downloadingBox.addView(emptyActive);
        }
    }

    private void renderCompleted(List<DownloadTask> tasks) {
        List<DownloadTask> done = new ArrayList<DownloadTask>(tasks);
        StringBuilder signature = new StringBuilder();
        for (DownloadTask t : done) signature.append(t.id).append(':').append(t.completedAt)
                .append(':').append(t.outputSize).append(':').append(t.outName).append(':')
                .append(t.outputUri).append(':').append(t.warning).append(':').append(t.error).append('|');
        if (signature.toString().equals(lastCompletedSignature) && completedBox.getChildCount() > 0) return;
        lastCompletedSignature = signature.toString();
        Collections.sort(done, new Comparator<DownloadTask>() {
            @Override public int compare(DownloadTask a, DownloadTask b) { return Long.compare(b.completedAt, a.completedAt); }
        });
        completedHeader.setText(done.isEmpty() ? "No completed downloads" : "Saved to your device");
        completedBox.removeAllViews();
        if (done.isEmpty()) completedBox.addView(note("Finished downloads will appear here and in My Files."));
        String previousDay = null;
        for (DownloadTask t : done) {
            String day = completionDay(t.completedAt);
            if (!day.equals(previousDay)) {
                TextView header = note(day);
                header.setTextColor(act.getResources().getColor(R.color.text_primary));
                header.setTypeface(null, android.graphics.Typeface.BOLD);
                completedBox.addView(header);
                previousDay = day;
            }
            Row row = new Row(completedBox, t);
            row.update(t);
            completedBox.addView(row.view);
        }
    }

    private final class Row {
        final View view, action;
        final TextView title, meta, label;
        final ProgressBar progress;
        final ImageView thumb, icon;
        DownloadTask task;
        String thumbKey;
        Row(ViewGroup parent, DownloadTask t) {
            view = LayoutInflater.from(act).inflate(R.layout.item_download, parent, false);
            action = view.findViewById(R.id.video_action);
            title = (TextView) view.findViewById(R.id.video_title);
            meta = (TextView) view.findViewById(R.id.video_meta);
            label = (TextView) view.findViewById(R.id.thumb_duration);
            progress = (ProgressBar) view.findViewById(R.id.video_progress);
            thumb = (ImageView) view.findViewById(R.id.thumb_bg);
            icon = (ImageView) view.findViewById(R.id.video_action_icon);
            task = t;
            View.OnClickListener click = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    switch (task.state) {
                        case COMPLETED: openPlayer(task); break;
                        case PAUSED: case FAILED: resume(task); break;
                        case DOWNLOADING: case WAITING_NETWORK: dm.pause(task); break;
                        default: showRowActions(task); break;
                    }
                }
            };
            view.setOnClickListener(click);
            action.setOnClickListener(click);
            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { showRowActions(task); return true; }
            });
        }
        void update(DownloadTask t) {
            task = t;
            title.setText(t.title);
            label.setText(t.label);
            label.setVisibility(t.label == null || t.label.isEmpty() ? View.GONE : View.VISIBLE);
            String key = t.outputUri + "|" + t.outFile + "|" + t.thumbUrl;
            if (!key.equals(thumbKey)) {
                thumbKey = key;
                LocalThumb.load(thumb, t, Util.grad(t.grad));
            }
            boolean publishing = t.state == DownloadTask.State.PUBLISHING;
            long d = publishing ? t.publishDone : t.done();
            long n = publishing ? t.publishTotal : t.total();
            boolean known = knownSize(t);
            progress.setVisibility(t.state == DownloadTask.State.COMPLETED ? View.GONE : View.VISIBLE);
            progress.setIndeterminate(!known && t.state != DownloadTask.State.PAUSED
                    && t.state != DownloadTask.State.FAILED && t.state != DownloadTask.State.WAITING_NETWORK);
            progress.setProgress(percent(d, n));
            String amount = (known ? percent(d, n) + "% • " : "") + Util.bytes(Math.max(0, d))
                    + (known ? " / " + Util.bytes(n) : " • size unknown");
            int image = R.drawable.ic_more;
            String actionText = "Download actions";
            switch (t.state) {
                case COMPLETED:
                    String warning = completedWarning(t);
                    meta.setText(Util.bytes(t.outputSize) + " • Saved to device"
                            + (warning.isEmpty() ? "" : "\n" + warning));
                    image = MediaTypes.isAudio(t) ? R.drawable.ic_music : R.drawable.ic_play;
                    actionText = "Open file";
                    break;
                case DOWNLOADING:
                    meta.setText("Downloading • " + amount + "\n" + Util.speed(Math.max(0, t.speed))
                            + (known && t.speed > 0 && n > d ? " • " + eta((long) ((n - d) / t.speed)) + " left" : ""));
                    image = R.drawable.ic_pause; actionText = "Pause download"; break;
                case PAUSED:
                    meta.setText("Paused • " + amount + (t.error == null || t.error.isEmpty() ? "" : "\n" + friendly(t.error)));
                    image = R.drawable.ic_play; actionText = "Resume download"; break;
                case FAILED:
                    meta.setText("Failed • " + friendly(t.error) + "\n" + amount + " • Tap to retry");
                    image = R.drawable.ic_download; actionText = "Retry download"; break;
                case QUEUED:
                    meta.setText("Queued • waiting for a free slot\n" + amount); break;
                case WAITING_NETWORK:
                    meta.setText("Waiting for network / Wi-Fi\n" + amount);
                    image = R.drawable.ic_pause; actionText = "Pause waiting download"; break;
                case MERGING:
                case CONVERTING:
                    progress.setIndeterminate(true);
                    meta.setText(t.state == DownloadTask.State.MERGING ? "Combining video and audio…\nTransfer complete • preparing your file"
                            : "Converting audio…\nTransfer complete • preparing your file");
                    break;
                case PUBLISHING:
                    meta.setText("Saving to " + MediaFiles.locationLabel() + "\n" + amount); break;
            }
            icon.setImageResource(image);
            action.setContentDescription(actionText);
        }
    }

    private void showRowActions(final DownloadTask t) {
        final List<String> items = new ArrayList<String>();
        if (t.state == DownloadTask.State.COMPLETED) {
            items.add("Open"); items.add("Share"); items.add("Delete file");
        } else {
            if (t.state == DownloadTask.State.PAUSED || t.state == DownloadTask.State.FAILED) items.add("Retry / resume");
            if (pausable(t)) items.add("Pause");
            items.add("Cancel download");
        }
        new AlertDialog.Builder(act).setTitle(t.title == null ? "Download" : t.title)
                .setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String item = items.get(which);
                        if ("Open".equals(item)) openPlayer(t);
                        else if ("Share".equals(item)) shareCompleted(t);
                        else if ("Pause".equals(item)) dm.pause(t);
                        else if ("Retry / resume".equals(item)) resume(t);
                        else confirmDelete(t);
                    }
                }).show();
    }

    private void showBulkActions() {
        final List<String> items = new ArrayList<String>();
        boolean paused = false, failed = false;
        for (DownloadTask t : dm.active()) {
            paused |= t.state == DownloadTask.State.PAUSED;
            failed |= t.state == DownloadTask.State.FAILED;
        }
        if (anyPausable()) items.add("Pause transfers");
        if (paused) items.add("Resume paused downloads");
        if (failed) items.add("Retry failed downloads");
        if (!dm.active().isEmpty()) items.add("Cancel all unfinished downloads");
        if (!dm.completed().isEmpty()) items.add("Delete completed files");
        if (items.isEmpty()) { Toast.makeText(act, "No downloads to manage yet.", Toast.LENGTH_SHORT).show(); return; }
        new AlertDialog.Builder(act).setTitle("Manage downloads")
                .setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String item = items.get(which);
                        if ("Pause transfers".equals(item)) pauseTransfers();
                        else if ("Resume paused downloads".equals(item)) resumeTransfers(false);
                        else if ("Retry failed downloads".equals(item)) resumeTransfers(true);
                        else confirmBulkDelete(item.startsWith("Delete"));
                    }
                }).show();
    }

    private void confirmBulkDelete(final boolean completed) {
        new AlertDialog.Builder(act).setTitle(completed ? "Delete completed files?" : "Cancel unfinished downloads?")
                .setMessage(completed ? "This deletes the saved files from your device. This cannot be undone."
                        : "Partial files will be deleted. Finished files are kept.")
                .setPositiveButton(completed ? "Delete files" : "Cancel downloads", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (completed) dm.deleteAllCompleted();
                        else for (DownloadTask t : new ArrayList<DownloadTask>(dm.active())) dm.remove(t);
                    }
                }).setNegativeButton("Keep", null).show();
    }

    private void confirmDelete(final DownloadTask t) {
        boolean completed = t.state == DownloadTask.State.COMPLETED;
        new AlertDialog.Builder(act).setTitle(completed ? "Delete this file?" : "Cancel this download?")
                .setMessage(completed ? "The saved file will be deleted from this device."
                        : "Partial files will be deleted. You can start a new download later.")
                .setPositiveButton(completed ? "Delete" : "Cancel download", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { dm.remove(t); }
                }).setNegativeButton("Keep", null).show();
    }

    private void shareCompleted(final DownloadTask t) { external(t, false); }

    private void external(final DownloadTask t, final boolean openAfter) {
        Runnable action = new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            final Uri uri = MediaExporter.export(act, t);
                            act.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (destroyed || act.isFinishing()) return;
                                    Intent intent;
                                    if (openAfter) intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, MediaExporter.mimeType(t));
                                    else {
                                        intent = new Intent(Intent.ACTION_SEND).setType(MediaExporter.mimeType(t));
                                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                                    }
                                    intent.setClipData(ClipData.newRawUri("Downloaded file", uri));
                                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                    try { act.startActivity(Intent.createChooser(intent, openAfter ? "Open file" : "Share file")); }
                                    catch (Exception e) { toast("No app can " + (openAfter ? "open" : "share") + " this file type."); }
                                }
                            });
                        } catch (final Exception e) {
                            act.runOnUiThread(new Runnable() {
                                @Override public void run() { if (!destroyed) toast("Could not access this file. " + friendly(e.getMessage())); }
                            });
                        }
                    }
                }, "download-external").start();
            }
        };
        if (t.outputUri != null && !t.outputUri.isEmpty()) action.run();
        else StoragePermission.run(act, action);
    }

    private void openPlayer(final DownloadTask t) {
        if (!MediaFiles.exists(act, t)) { toast("This file is no longer available."); return; }
        if (!MediaTypes.isAudio(t) && !MediaTypes.isVideo(t)) { external(t, true); return; }
        Intent i = new Intent(act, MediaTypes.isAudio(t) ? MusicPlayerActivity.class : PlayerActivity.class);
        i.putExtra("path", t.outputUri != null && !t.outputUri.isEmpty() ? t.outputUri
                : t.outFile == null ? "" : t.outFile.getAbsolutePath());
        i.putExtra("title", t.title); i.putExtra("author", t.author); i.putExtra("grad", t.grad);
        i.putExtra("mimeType", t.mimeType); i.putExtra("mediaType", MediaTypes.mediaType(t));
        act.startActivity(i);
    }

    private TextView note(String message) {
        TextView v = new TextView(act);
        v.setText(message); v.setTextColor(act.getResources().getColor(R.color.text_tertiary));
        v.setTextSize(14); v.setPadding(Util.dp(act, 8), Util.dp(act, 20), Util.dp(act, 8), Util.dp(act, 12));
        return v;
    }
    private void toast(String text) { Toast.makeText(act, text, Toast.LENGTH_SHORT).show(); }

    private String netLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni == null || !ni.isConnected() ? "Offline" : ni.getType() == ConnectivityManager.TYPE_WIFI ? "Wi-Fi" : "Mobile data";
        } catch (Exception e) { return "Network unavailable"; }
    }

    private static boolean knownSize(DownloadTask t) {
        if (t.state == DownloadTask.State.PUBLISHING) return t.publishTotal > 0;
        if (t.parts.isEmpty()) return false;
        for (DownloadTask.Part p : t.parts) if (p.total <= 0) return false;
        return true;
    }
    private static int percent(long done, long total) {
        return total <= 0 ? 0 : (int) Math.max(0, Math.min(100, done * 100.0 / total));
    }
    private static String eta(long seconds) {
        if (seconds < 60) return Math.max(1, seconds) + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + (seconds % 3600) / 60 + "m";
    }

    private String completionDay(long timestamp) {
        if (timestamp <= 0) return "Completion date unknown";
        Calendar completed = Calendar.getInstance(); completed.setTimeInMillis(timestamp);
        Calendar today = Calendar.getInstance();
        if (sameDay(completed, today)) return "Today";
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(completed, today)) return "Yesterday";
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(timestamp));
    }
    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
    private static String completedWarning(DownloadTask task) {
        String warning = task.warning == null ? "" : task.warning.trim();
        String legacy = task.error == null ? "" : task.error.trim();
        String message = warning.isEmpty() ? "" : friendly(warning);
        if (!legacy.isEmpty() && !legacy.equals(warning)) message += (message.isEmpty() ? "" : " • ") + friendly(legacy);
        return message;
    }
    private static String friendly(String error) {
        if (error == null || error.trim().isEmpty()) return "Please retry";
        String m = error.toLowerCase(Locale.US);
        if (m.contains("permission") || m.contains("eacces")) return "Storage access needed • tap Retry and allow access";
        if (m.contains("save_failed")) return "Could not save to " + MediaFiles.locationLabel() + " • tap to retry saving";
        if (m.contains("publish") || m.contains("public_media") || m.contains("download_folder")) return "Could not save to " + MediaFiles.locationLabel() + " • tap to retry saving";
        if (m.contains("background_start_blocked")) return "Open SnapSave and tap to resume";
        if (m.contains("video_only_no_audio")) return "Video saved without an audio track";
        if (m.contains("expired") || m.contains("404")) return "Link expired • reopen the source page";
        if (m.contains("insecure")) return "Only HTTPS links are supported";
        if (m.contains("merge_failed") || m.contains("mux_failed")) return "Could not combine video and audio";
        if (m.contains("unavailable") || m.contains("conversion_failed")) return "Media processing unavailable or failed";
        if (m.contains("416") || m.contains("range mismatch")) return "Server sent an invalid range • retry";
        if (m.contains("incomplete") || m.contains("eof")) return "Download was cut short • retry";
        if (m.contains("timeout") || m.contains("timed out") || m.contains("connection") || m.contains("network")
                || m.contains("socket") || m.contains("reset") || m.contains("broken pipe")) return "Network interrupted • retry";
        if (m.contains("403")) return "Blocked by server (403) • reopen the source page";
        if (m.contains("empty_output") || m.contains("empty output")) return "No data received • retry";
        return error;
    }
}
