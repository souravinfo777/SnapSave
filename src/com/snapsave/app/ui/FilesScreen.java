package com.snapsave.app.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FilesScreen extends Screen {
    private final DownloadManager dm;
    private final LinearLayout container;
    private final TextView tabVideos, tabAudio, tabFiles;
    private final EditText search;
    /** 0 = video, 1 = audio, 2 = all */
    private int filter = 2;
    private String lastSignature = "";
    private boolean destroyed;

    public FilesScreen(MainActivity act) {
        super(act, R.layout.fragment_files);
        dm = App.get().downloads();
        container = (LinearLayout) root.findViewById(R.id.files_container);
        tabVideos = (TextView) root.findViewById(R.id.ftab_videos);
        tabAudio = (TextView) root.findViewById(R.id.ftab_audio);
        tabFiles = (TextView) root.findViewById(R.id.ftab_files);
        search = (EditText) root.findViewById(R.id.files_search_input);
        final TextView[] tabs = {tabVideos, tabAudio, tabFiles};
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { filter = selected; updateTabs(); refresh(); }
            });
        }
        root.findViewById(R.id.files_search_toggle).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean opening = search.getVisibility() != View.VISIBLE;
                search.setVisibility(opening ? View.VISIBLE : View.GONE);
                InputMethodManager ime = (InputMethodManager) act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (opening) {
                    search.requestFocus();
                    if (ime != null) ime.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                } else {
                    search.setText("");
                    if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
                }
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        dm.addListener(listener);
        updateTabs();
    }

    private final DownloadManager.Listener listener = new DownloadManager.Listener() {
        @Override public void onDownloadsChanged() {
            if (!destroyed && act.currentTab() == 3 && root.isShown()) refresh();
        }
    };

    @Override public void destroy() { destroyed = true; dm.removeListener(listener); }
    @Override public void onShow() { refresh(); }

    private void updateTabs() {
        int on = act.getResources().getColor(R.color.primary);
        int off = act.getResources().getColor(R.color.text_tertiary);
        tabVideos.setTextColor(filter == 0 ? on : off);
        tabAudio.setTextColor(filter == 1 ? on : off);
        tabFiles.setTextColor(filter == 2 ? on : off);
        tabFiles.setText("All files");
    }

    private void refresh() {
        if (destroyed) return;
        final String query = search.getText().toString().trim().toLowerCase(Locale.US);
        List<DownloadTask> done = dm.completed();
        StringBuilder signature = new StringBuilder().append(filter).append('|').append(query);
        for (DownloadTask t : done) signature.append('|').append(t.id).append(':').append(t.completedAt)
                .append(':').append(t.outputSize).append(':').append(t.outName)
                .append(':').append(t.outputUri).append(':').append(t.title);
        if (signature.toString().equals(lastSignature)) return;
        lastSignature = signature.toString();
        Collections.sort(done, new Comparator<DownloadTask>() {
            @Override public int compare(DownloadTask a, DownloadTask b) { return Long.compare(b.completedAt, a.completedAt); }
        });
        container.removeAllViews();
        int shown = 0;
        for (final DownloadTask t : done) {
            boolean audio = MediaTypes.isAudio(t), video = MediaTypes.isVideo(t);
            if (filter == 0 && !video || filter == 1 && !audio) continue;
            String searchable = ((t.title == null ? "" : t.title) + " " + (t.author == null ? "" : t.author)
                    + " " + (t.outName == null ? "" : t.outName)).toLowerCase(Locale.US);
            if (!query.isEmpty() && !searchable.contains(query)) continue;
            View row = LayoutInflater.from(act).inflate(R.layout.item_download, container, false);
            LocalThumb.load((ImageView) row.findViewById(R.id.thumb_bg), t, Util.grad(t.grad));
            TextView label = (TextView) row.findViewById(R.id.thumb_duration);
            label.setText(t.label);
            label.setVisibility(MediaTypes.isImage(t) || t.label == null || t.label.isEmpty() ? View.GONE : View.VISIBLE);
            ((TextView) row.findViewById(R.id.video_title)).setText(t.title);
            ((TextView) row.findViewById(R.id.video_meta)).setText(
                    (t.author == null || t.author.isEmpty() ? "" : t.author + " • ") + Util.bytes(t.outputSize)
                            + (t.outName == null || t.outName.isEmpty() ? "" : "\n" + t.outName));
            row.findViewById(R.id.video_progress).setVisibility(View.GONE);
            ((ImageView) row.findViewById(R.id.video_action_icon)).setImageResource(R.drawable.ic_more);
            row.findViewById(R.id.video_action).setContentDescription("File actions");
            row.findViewById(R.id.video_action).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showActions(t); }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { open(t); }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { showActions(t); return true; }
            });
            container.addView(row);
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(act);
            empty.setText(!query.isEmpty() ? "No saved files match “" + search.getText().toString().trim()
                    + "”.\nTry another name or select All files." : filter == 2
                    ? "No saved files yet.\nCompleted downloads will appear here."
                    : "No " + (filter == 0 ? "videos" : "audio files") + " yet.\nSelect All files to see your other downloads.");
            empty.setTextColor(act.getResources().getColor(R.color.text_tertiary));
            empty.setPadding(Util.dp(act, 8), Util.dp(act, 40), Util.dp(act, 8), 0);
            empty.setGravity(android.view.Gravity.CENTER);
            container.addView(empty);
        }
    }

    private void showActions(final DownloadTask t) {
        final List<String> items = new ArrayList<String>();
        if (MediaTypes.isAudio(t) || MediaTypes.isVideo(t)) items.add("Play");
        items.add("Share");
        items.add("Open with another app");
        items.add("Rename");
        items.add("Delete");
        new AlertDialog.Builder(act).setTitle(t.title == null || t.title.isEmpty() ? "File" : t.title)
                .setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String label = items.get(which);
                        if ("Play".equals(label)) open(t);
                        else if ("Share".equals(label)) external(t, false);
                        else if ("Open with another app".equals(label)) external(t, true);
                        else if ("Rename".equals(label)) rename(t);
                        else if ("Delete".equals(label)) confirmDelete(t);
                    }
                }).show();
    }

    private void open(DownloadTask t) {
        if (!MediaFiles.exists(act, t)) { toast("This file is no longer available."); return; }
        if (!MediaTypes.isAudio(t) && !MediaTypes.isVideo(t)) { external(t, true); return; }
        Intent i = new Intent(act, MediaTypes.isAudio(t) ? MusicPlayerActivity.class : PlayerActivity.class);
        i.putExtra("path", t.outputUri != null && !t.outputUri.isEmpty() ? t.outputUri
                : t.outFile == null ? "" : t.outFile.getAbsolutePath());
        i.putExtra("title", t.title); i.putExtra("author", t.author); i.putExtra("grad", t.grad);
        i.putExtra("mimeType", t.mimeType); i.putExtra("mediaType", MediaTypes.mediaType(t));
        act.startActivity(i);
    }

    /** Existing public output is reused; only a legacy private download needs publishing. */
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
                                @Override public void run() {
                                    if (!destroyed) toast(e.getMessage() == null ? "Could not access this file." : e.getMessage());
                                }
                            });
                        }
                    }
                }, "file-external").start();
            }
        };
        if (t.outputUri != null && !t.outputUri.isEmpty()) action.run();
        else StoragePermission.run(act, action);
    }

    private void rename(final DownloadTask t) {
        final EditText input = new EditText(act);
        String name = MediaFiles.displayName(act, t);
        int dot = name.lastIndexOf('.');
        input.setText(dot > 0 ? name.substring(0, dot) : name);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        int pad = Util.dp(act, 18);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(act);
        wrap.setPadding(pad, pad / 2, pad, 0); wrap.addView(input);
        final AlertDialog dialog = new AlertDialog.Builder(act).setTitle("Rename file").setView(wrap)
                .setPositiveButton("Rename", null).setNegativeButton("Cancel", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (input.getText().toString().trim().isEmpty()) { input.setError("Enter a file name"); return; }
                if (!dm.rename(t, input.getText().toString())) { input.setError("Could not rename this file"); return; }
                dialog.dismiss(); refresh();
            }
        });
    }

    private void confirmDelete(final DownloadTask t) {
        new AlertDialog.Builder(act).setTitle("Delete file?")
                .setMessage("This removes the saved file from this device. It cannot be undone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { dm.remove(t); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void toast(String message) { Toast.makeText(act, message, Toast.LENGTH_SHORT).show(); }
}
