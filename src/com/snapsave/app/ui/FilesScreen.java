package com.snapsave.app.ui;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.DownloadManager;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.util.Util;

import java.util.List;

public class FilesScreen extends Screen {

    private final DownloadManager dm;
    private LinearLayout container;
    private TextView tabVideos;
    private TextView tabAudio;
    private TextView tabFiles;

    /** 0 = video, 1 = audio, 2 = all */
    private int filter = 0;

    public FilesScreen(MainActivity act) {
        super(act, R.layout.fragment_files);
        dm = App.get().downloads();
        container = (LinearLayout) root.findViewById(R.id.files_container);
        tabVideos = (TextView) root.findViewById(R.id.ftab_videos);
        tabAudio = (TextView) root.findViewById(R.id.ftab_audio);
        tabFiles = (TextView) root.findViewById(R.id.ftab_files);

        tabVideos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = 0;
                updateTabs();
                refresh();
            }
        });
        tabAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = 1;
                updateTabs();
                refresh();
            }
        });
        tabFiles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = 2;
                updateTabs();
                refresh();
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

    private void updateTabs() {
        int on = act.getResources().getColor(R.color.primary);
        int off = act.getResources().getColor(R.color.text_tertiary);
        tabVideos.setTextColor(filter == 0 ? on : off);
        tabAudio.setTextColor(filter == 1 ? on : off);
        tabFiles.setTextColor(filter == 2 ? on : off);
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        container.removeAllViews();
        List<DownloadTask> done = dm.completed();
        int shown = 0;
        for (final DownloadTask t : done) {
            boolean audio = "mp3".equals(t.kind) || "m4a".equals(t.kind);
            if (filter == 0 && audio) {
                continue;
            }
            if (filter == 1 && !audio) {
                continue;
            }
            View row = LayoutInflater.from(act).inflate(R.layout.item_video, container, false);
            ((View) row.findViewById(R.id.thumb_bg)).setBackgroundResource(Util.grad(t.grad));
            ((TextView) row.findViewById(R.id.thumb_duration)).setText(t.label);
            ((TextView) row.findViewById(R.id.video_title)).setText(t.title);
            ((TextView) row.findViewById(R.id.video_meta)).setText(
                    (t.author == null || t.author.isEmpty() ? "" : t.author + " • ")
                            + Util.bytes(t.outFile != null ? t.outFile.length() : 0));
            row.findViewById(R.id.video_action_icon).setVisibility(View.GONE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(t);
                }
            });
            container.addView(row);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(act);
            empty.setText("No files here yet.\nDownloads you complete will appear in My Files.");
            empty.setTextColor(act.getResources().getColor(R.color.text_tertiary));
            empty.setPadding(0, Util.dp(act, 40), 0, 0);
            empty.setGravity(android.view.Gravity.CENTER);
            container.addView(empty);
        }
    }

    private void open(DownloadTask t) {
        if (t.outFile == null || !t.outFile.exists()) {
            return;
        }
        boolean audio = "mp3".equals(t.kind) || "m4a".equals(t.kind);
        Intent i = new Intent(act, audio ? MusicPlayerActivity.class : PlayerActivity.class);
        i.putExtra("path", t.outFile.getAbsolutePath());
        i.putExtra("title", t.title);
        i.putExtra("author", t.author);
        i.putExtra("grad", t.grad);
        act.startActivity(i);
    }
}
