package com.snapsave.app.ui;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadManager;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.util.Util;

import java.util.List;

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
            if (t.isActive()) {
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
        List<DownloadTask> done = dm.completed();

        pauseAll.setText(act.getString(anyRunning() ? R.string.pause_all : R.string.resume_all));

        tabDownloading.setText("Downloading (" + active.size() + ")");

        downloadingBox.removeAllViews();
        completedBox.removeAllViews();

        double totalSpeed = 0;
        int activeNow = 0;
        int waiting = 0;
        long totalDone = 0, totalAll = 0;
        for (DownloadTask t : active) {
            downloadingBox.addView(buildRow(downloadingBox, t));
            if (t.state == DownloadTask.State.DOWNLOADING) {
                totalSpeed += t.speed;
                activeNow++;
            } else if (t.state == DownloadTask.State.QUEUED) {
                waiting++;
            }
            totalDone += t.done();
            totalAll += t.total();
        }

        for (DownloadTask t : done) {
            completedBox.addView(buildRow(completedBox, t));
        }
        if (done.isEmpty()) {
            completedHeader.setVisibility(View.GONE);
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
        long free = 0;
        try {
            StatFs sf = new StatFs(dm.dir().getAbsolutePath());
            free = sf.getAvailableBytes();
        } catch (Exception ignored) {
        }
        storageView.setText("Storage " + Util.bytes(free) + " free");

        View dlSection = downloadingBox;
        dlSection.setVisibility(showCompleted ? View.GONE : View.VISIBLE);
        completedHeader.setVisibility(showCompleted ? View.VISIBLE : View.GONE);
        completedBox.setVisibility(showCompleted ? View.VISIBLE : View.GONE);
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

        TextView meta = (TextView) row.findViewById(R.id.video_meta);
        ProgressBar pb = (ProgressBar) row.findViewById(R.id.video_progress);
        final ImageView icon = (ImageView) row.findViewById(R.id.video_action_icon);
        FrameLayout action = (FrameLayout) row.findViewById(R.id.video_action);

        switch (t.state) {
            case COMPLETED:
                pb.setVisibility(View.GONE);
                meta.setText(Util.bytes(t.outFile != null ? t.outFile.length() : 0) + " • Completed");
                icon.setImageResource(R.drawable.ic_play);
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
                meta.setText("Paused • " + t.percent() + "%");
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
                meta.setText("Failed • " + friendly(t.error));
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
            default: // DOWNLOADING
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

    private static String friendly(String e) {
        if (e == null || e.trim().isEmpty()) {
            return "tap to retry";
        }
        String m = e.toLowerCase();
        if (m.contains("link_expired") || m.contains("expired")) {
            return "link expired — open the page again to retry";
        }
        if (m.contains("insecure")) {
            return "only https links are supported";
        }
        if (m.contains("merge_failed")) {
            return "audio merge failed — saved video only";
        }
        if (m.contains("mp3_converter_unavailable") || m.contains("native_engine_unavailable")) {
            return "converter unavailable — saved original format";
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
        Intent i;
        if ("mp3".equals(t.kind) || "m4a".equals(t.kind)) {
            i = new Intent(act, MusicPlayerActivity.class);
        } else {
            i = new Intent(act, PlayerActivity.class);
        }
        i.putExtra("path", t.outFile.getAbsolutePath());
        i.putExtra("title", t.title);
        i.putExtra("author", t.author);
        i.putExtra("grad", t.grad);
        act.startActivity(i);
    }
}
