package com.snapsave.app.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

import java.util.ArrayList;
import java.util.List;

public class FilesScreen extends Screen {

    private static final int REQUEST_STORAGE = 1003;

    private final DownloadManager dm;
    private LinearLayout container;
    private TextView tabVideos;
    private TextView tabAudio;
    private TextView tabFiles;

    /** 0 = video, 1 = audio, 2 = all */
    private int filter = 0;
    /** Completed files do not change during a transfer; skip needless re-inflation. */
    private String lastSignature = "";

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

    private boolean destroyed = false;

    @Override
    public void destroy() {
        destroyed = true;
        dm.removeListener(listener);
    }

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
        if (destroyed) {
            return;
        }
        List<DownloadTask> done = dm.completed();
        StringBuilder signature = new StringBuilder().append(filter);
        for (DownloadTask t : done) {
            signature.append('|').append(t.id).append(':').append(t.completedAt).append(':')
                    .append(t.outFile == null ? 0 : t.outFile.length()).append(':')
                    .append(t.outFile == null ? "" : t.outFile.getName());
        }
        if (signature.toString().equals(lastSignature)) {
            return;
        }
        lastSignature = signature.toString();

        container.removeAllViews();
        int shown = 0;
        for (final DownloadTask t : done) {
            String mediaType = MediaTypes.mediaType(t);
            boolean audio = MediaTypes.AUDIO.equals(mediaType);
            boolean video = MediaTypes.VIDEO.equals(mediaType);
            if (filter == 0 && !video) {
                continue;
            }
            if (filter == 1 && !audio) {
                continue;
            }
            View row = LayoutInflater.from(act).inflate(R.layout.item_video, container, false);
            ImageView thumb = (ImageView) row.findViewById(R.id.thumb_bg);
            if (audio || video || MediaTypes.IMAGE.equals(mediaType)) {
                LocalThumb.load(thumb, t.outFile, mediaType, Util.grad(t.grad));
            } else {
                thumb.setBackgroundResource(Util.grad(t.grad));
            }
            if (MediaTypes.IMAGE.equals(mediaType)) {
                row.findViewById(R.id.thumb_duration).setVisibility(View.GONE);
            }
            ((TextView) row.findViewById(R.id.thumb_duration)).setText(t.label);
            ((TextView) row.findViewById(R.id.video_title)).setText(t.title);
            ((TextView) row.findViewById(R.id.video_meta)).setText(
                    (t.author == null || t.author.isEmpty() ? "" : t.author + " • ")
                            + Util.bytes(t.outFile != null ? t.outFile.length() : 0));
            ImageView actionIcon = (ImageView) row.findViewById(R.id.video_action_icon);
            actionIcon.setImageResource(R.drawable.ic_more);
            actionIcon.setVisibility(View.VISIBLE);
            row.findViewById(R.id.video_action).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showActions(t);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(t);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showActions(t);
                    return true;
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

    private void showActions(final DownloadTask t) {
        final String type = MediaTypes.mediaType(t);
        final boolean audio = MediaTypes.AUDIO.equals(type);
        final boolean image = MediaTypes.IMAGE.equals(type);
        final boolean playable = audio || MediaTypes.VIDEO.equals(type);
        final List<String> items = new ArrayList<String>();
        if (playable) {
            items.add("Play");
        }
        items.add("Share");
        items.add("Export to " + (audio ? "Music" : image ? "Pictures" : "Gallery"));
        items.add("Open with another app");
        items.add("Rename");
        items.add("Delete");
        new AlertDialog.Builder(act)
                .setTitle(t.title == null || t.title.isEmpty() ? "File" : t.title)
                .setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String label = items.get(which);
                        if ("Play".equals(label)) {
                            open(t);
                        } else if ("Share".equals(label)) {
                            share(t);
                        } else if (label.startsWith("Export")) {
                            export(t, false);
                        } else if ("Open with another app".equals(label)) {
                            export(t, true);
                        } else if ("Rename".equals(label)) {
                            rename(t);
                        } else if ("Delete".equals(label)) {
                            confirmDelete(t);
                        }
                    }
                }).show();
    }

    private void open(DownloadTask t) {
        if (t.outFile == null || !t.outFile.exists()) {
            toast("This file is no longer available.");
            return;
        }
        String mediaType = MediaTypes.mediaType(t);
        if (MediaTypes.IMAGE.equals(mediaType)) {
            export(t, true);
            return;
        }
        if (!MediaTypes.AUDIO.equals(mediaType) && !MediaTypes.VIDEO.equals(mediaType)) {
            toast("This file type cannot be opened here.");
            return;
        }
        boolean audio = MediaTypes.AUDIO.equals(mediaType);
        Intent i = new Intent(act, audio ? MusicPlayerActivity.class : PlayerActivity.class);
        i.putExtra("path", t.outFile.getAbsolutePath());
        i.putExtra("title", t.title);
        i.putExtra("author", t.author);
        i.putExtra("grad", t.grad);
        i.putExtra("mimeType", t.mimeType);
        i.putExtra("mediaType", mediaType);
        act.startActivity(i);
    }

    private void share(final DownloadTask t) {
        final Uri uri = exportNow(t);
        if (uri == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(MediaExporter.mimeType(t));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        act.startActivity(Intent.createChooser(send, "Share file"));
    }

    private void export(final DownloadTask t, final boolean openAfter) {
        final Uri uri = exportNow(t);
        if (uri == null) {
            return;
        }
        toast("Saved to the public SnapSave folder");
        if (openAfter) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, MediaExporter.mimeType(t));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                act.startActivity(view);
            } catch (Exception e) {
                toast("No app can open this file type.");
            }
        }
    }

    private Uri exportNow(DownloadTask t) {
        if (MediaExporter.needsLegacyPermission() && Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            toast("Allow storage access, then tap Export again.");
            return null;
        }
        try {
            return MediaExporter.export(act, t);
        } catch (Exception e) {
            toast(e.getMessage() == null ? "Export failed." : e.getMessage());
            return null;
        }
    }

    private void rename(final DownloadTask t) {
        final EditText input = new EditText(act);
        String name = t.outName != null ? t.outName
                : (t.outFile != null ? t.outFile.getName() : "");
        int dot = name.lastIndexOf('.');
        input.setText(dot > 0 ? name.substring(0, dot) : name);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(act).setTitle("Rename file").setView(input)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!dm.rename(t, input.getText().toString())) {
                            toast("Could not rename this file.");
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(final DownloadTask t) {
        new AlertDialog.Builder(act).setTitle("Delete file?")
                .setMessage("This removes the file from this device. It cannot be undone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dm.remove(t);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void toast(String message) {
        Toast.makeText(act, message, Toast.LENGTH_SHORT).show();
    }
}