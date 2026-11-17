package com.snapsave.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.NativeStreams;
import com.snapsave.app.engine.DownloadTask;
import com.snapsave.app.engine.MediaFiles;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.Prefs;

import java.util.Locale;

public class SettingsActivity extends Activity {
    /** Result code for the system folder picker. */
    private static final int REQUEST_FOLDER = 1105;
    private LinearLayout box;

    private interface Action {
        void run();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Insets.applyToContent(this);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        box = (LinearLayout) findViewById(R.id.settings_container);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        if (box != null) build();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1002) {
            build();
            if (grantResults.length > 0 && grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED)
                info("Notifications", "Notifications are off. You can enable them in Android app settings.");
        }
    }

    private void build() {
        box.removeAllViews();
        boolean nativeOk = NativeStreams.available();
        String resolver = Prefs.resolver();
        int keyCount = Prefs.keyCount();
        boolean hasToken = !Prefs.resolverToken().isEmpty();
        String credentialError = Prefs.credentialError();
        if (!credentialError.isEmpty()) {
            final String message = credentialError;
            actionable(add(R.drawable.ic_settings, "Credential storage needs attention", message, "Error"), new Action() {
                @Override
                public void run() {
                    info("Credential storage", message);
                }
            });
        }

        actionable(add(R.drawable.ic_folder, "Download folder", "Where finished downloads are saved",
                MediaFiles.locationLabel()), new Action() {
            @Override
            public void run() {
                chooseDownloadFolder();
            }
        });
        actionable(add(R.drawable.ic_tune, "Video quality", "Preferred maximum height for new downloads",
                Prefs.videoQuality() == 0 ? "Best" : Prefs.videoQuality() + "p"), new Action() {
            @Override
            public void run() {
                chooseVideoQuality();
            }
        });
        actionable(add(R.drawable.ic_music, "Audio format", "Preferred audio output", Prefs.audioFormat().toUpperCase(Locale.US)), new Action() {
            @Override
            public void run() {
                chooseAudioFormat();
            }
        });
        actionable(add(R.drawable.ic_music, "MP3 bitrate", "Quality used when converting audio to MP3",
                Prefs.mp3Bitrate() + " kbps"), new Action() {
            @Override
            public void run() {
                chooseMp3Bitrate();
            }
        });
        actionable(add(R.drawable.ic_globe, "Download only on Wi-Fi", "Avoid mobile data usage",
                Prefs.wifiOnly() ? "On" : "Off"), new Action() {
            @Override
            public void run() {
                chooseWifiOnly();
            }
        });
        actionable(add(R.drawable.ic_download, "Simultaneous downloads",
                "Transfers running at once on Wi-Fi or mobile data",
                Prefs.simultaneousDownloads() + " at once"), new Action() {
            @Override
            public void run() {
                chooseSimultaneous();
            }
        });
        actionable(add(R.drawable.ic_tune, "Connections per download",
                "Parallel range connections for faster single-file speed",
                Prefs.downloadConnections() == 1 ? "Single" : Prefs.downloadConnections() + " connections"),
                new Action() {
                    @Override
                    public void run() {
                        chooseConnections();
                    }
                });
        actionable(add(R.drawable.ic_settings, "Native media engine", "SnapTube FFmpeg / M4A engine",
                nativeOk ? "Loaded" : "Unavailable"), new Action() {
            @Override
            public void run() {
                showNativeEngine(nativeOk);
            }
        });
        actionable(add(R.drawable.ic_link, "Resolver server", "Optional HTTPS endpoint; changing origin clears its token",
                host(resolver)), new Action() {
            @Override
            public void run() {
                editResolver();
            }
        });
        actionable(add(R.drawable.ic_check, "Resolver token", "Only sent to the configured HTTPS origin; separate from FastSaver",
                hasToken ? "Set" : "Off"), new Action() {
            @Override
            public void run() {
                editResolverToken();
            }
        });
        actionable(add(R.drawable.ic_check, "FastSaver API keys", "FastSaver only; encrypted on this device and rotated automatically",
                keyCount == 0 ? "Off" : keyCount + " key" + (keyCount > 1 ? "s" : "")), new Action() {
            @Override
            public void run() {
                editKeys();
            }
        });
        actionable(add(R.drawable.ic_link, "Clipboard link", "Open the download popup for a link you just copied",
                Prefs.clipboardPrompt() ? "On" : "Off"), new Action() {
            @Override
            public void run() {
                Prefs.setClipboardPrompt(!Prefs.clipboardPrompt());
                build();
            }
        });
        actionable(add(R.drawable.ic_download, "Notifications", "Progress and completion alerts for downloads",
                com.snapsave.app.engine.Notifier.enabled(this) ? "On" : "Off"), new Action() {
            @Override
            public void run() {
                openNotificationSettings();
            }
        });
        actionable(add(R.drawable.ic_person, "Stored on this device", "Downloaded files and history on this device", storageValue()),
                new Action() {
                    @Override
                    public void run() {
                        showStorage();
                    }
                });
        actionable(add(R.drawable.ic_check, "About SnapSave", getString(R.string.version_line), ""), new Action() {
            @Override
            public void run() {
                showAbout();
            }
        });
    }

    private void chooseVideoQuality() {
        final int[] values = {0, 2160, 1440, 1080, 720, 480, 360, 240, 144};
        String[] labels = {"Best available", "2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p"};
        new AlertDialog.Builder(this).setTitle("Video quality")
                .setSingleChoiceItems(labels, indexOf(values, Prefs.videoQuality()), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, final int which) {
                        saveChoice(d, new Action() {
                            @Override
                            public void run() {
                                Prefs.setVideoQuality(values[which]);
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseAudioFormat() {
        final String[] formats = {"mp3", "m4a"};
        new AlertDialog.Builder(this).setTitle("Audio format")
                .setSingleChoiceItems(new String[]{"MP3", "M4A"}, "m4a".equals(Prefs.audioFormat()) ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, final int which) {
                                saveChoice(d, new Action() {
                                    @Override
                                    public void run() {
                                        Prefs.setAudioFormat(formats[which]);
                                    }
                                });
                            }
                        }).setNegativeButton("Cancel", null).show();
    }

    private void chooseMp3Bitrate() {
        final int[] values = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " kbps";
        }
        new AlertDialog.Builder(this).setTitle("MP3 bitrate")
                .setSingleChoiceItems(labels, indexOf(values, Prefs.mp3Bitrate()), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, final int which) {
                        saveChoice(d, new Action() {
                            @Override
                            public void run() {
                                Prefs.setMp3Bitrate(values[which]);
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseWifiOnly() {
        new AlertDialog.Builder(this).setTitle("Download only on Wi-Fi")
                .setSingleChoiceItems(new String[]{"Off — allow mobile data", "On — Wi-Fi only"}, Prefs.wifiOnly() ? 1 : 0,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, final int which) {
                                saveChoice(d, new Action() {
                                    @Override
                                    public void run() {
                                        Prefs.setWifiOnly(which == 1);
                                        App.get().downloads().onNetworkChanged();
                                    }
                                });
                            }
                        }).setNegativeButton("Cancel", null).show();
    }

    private void chooseSimultaneous() {
        final int[] values = {1, 2, 3, 4, 6, 8};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + (values[i] == 1 ? " download at a time" : " downloads at a time");
        }
        new AlertDialog.Builder(this).setTitle("Simultaneous downloads")
                .setSingleChoiceItems(labels, indexOf(values, Prefs.simultaneousDownloads()),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, final int which) {
                                saveChoice(d, new Action() {
                                    @Override
                                    public void run() {
                                        Prefs.setSimultaneousDownloads(values[which]);
                                        App.get().downloads().wakeQueue();
                                    }
                                });
                            }
                        }).setNegativeButton("Cancel", null).show();
    }

    private void chooseConnections() {
        final int[] values = {1, 2, 3, 4, 6, 8};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] == 1 ? "1 — single stream"
                    : values[i] + " — parallel range streams";
        }
        new AlertDialog.Builder(this).setTitle("Connections per download")
                .setSingleChoiceItems(labels, indexOf(values, Prefs.downloadConnections()),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, final int which) {
                                saveChoice(d, new Action() {
                                    @Override
                                    public void run() {
                                        Prefs.setDownloadConnections(values[which]);
                                    }
                                });
                            }
                        }).setNegativeButton("Cancel", null).show();
    }

    /** Built-in folders plus the system picker, so any folder the provider exposes can be used. */
    private void chooseDownloadFolder() {
        final String[] labels = {
                "Download — the phone's Download folder",
                "Download/SnapSave — keep SnapSave files together",
                "Another folder — pick one with the system picker"};
        int current = Prefs.FOLDER_SNAPSAVE.equals(Prefs.downloadFolder()) ? 1
                : Prefs.FOLDER_CUSTOM.equals(Prefs.downloadFolder()) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("Download folder")
                .setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, final int which) {
                        if (which == 2) {
                            d.dismiss();
                            openFolderPicker();
                            return;
                        }
                        saveChoice(d, new Action() {
                            @Override
                            public void run() {
                                Prefs.setDownloadFolder(which == 1
                                        ? Prefs.FOLDER_SNAPSAVE : Prefs.FOLDER_DOWNLOAD);
                            }
                        });
                    }
                })
                .setNeutralButton("Folder info", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        showDownloadFolder();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void openFolderPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_FOLDER);
        } catch (RuntimeException e) {
            info("Download folder",
                    "This device has no folder picker. Choose Download or Download/SnapSave instead.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri tree = data.getData();
        if (tree == null) {
            return;
        }
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (RuntimeException e) {
            info("Download folder", "Android did not grant lasting access to that folder. Pick it again.");
            return;
        }
        String previous = Prefs.downloadFolderUri();
        try {
            Prefs.setDownloadFolderCustom(tree.toString(), folderName(tree));
        } catch (IllegalStateException e) {
            showSaveError(e);
            return;
        }
        releaseTree(previous, tree.toString());
        build();
    }

    /** Folder name for the settings row; falls back to the last segment of the tree URI. */
    private String folderName(Uri tree) {
        try (android.database.Cursor c = getContentResolver().query(
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (Exception ignored) { }
        String last = tree.getLastPathSegment();
        int colon = last == null ? -1 : last.lastIndexOf(':');
        String fallback = last == null ? "" : last.substring(colon + 1);
        return fallback.isEmpty() ? "Chosen folder" : fallback;
    }

    private void releaseTree(String oldUri, String newUri) {
        if (oldUri == null || oldUri.isEmpty() || oldUri.equals(newUri)) {
            return;
        }
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(oldUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    private void showDownloadFolder() {
        String detail = Prefs.FOLDER_CUSTOM.equals(Prefs.downloadFolder())
                ? Prefs.downloadFolderUri() : MediaFiles.publicFolder().getAbsolutePath();
        new AlertDialog.Builder(this).setTitle("Download folder")
                .setMessage("Finished downloads are saved in " + MediaFiles.locationLabel() + ":\n\n" + detail
                        + "\n\nYou can find them in your device's Files app. Share and Open reuse the saved file. "
                        + "Changing the folder affects new downloads only; files you already saved stay where they "
                        + "are. Unfinished transfers always stay private until they are complete.")
                .setPositiveButton("Copy path", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("SnapSave download folder",
                                    MediaFiles.locationLabel()));
                            Toast.makeText(SettingsActivity.this, "Folder name copied", Toast.LENGTH_SHORT).show();
                        }
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void showNativeEngine(boolean nativeOk) {
        boolean mux = false;
        boolean mp3 = false;
        try {
            mux = NativeStreams.canMux();
        } catch (Throwable ignored) {
        }
        try {
            mp3 = NativeStreams.canMp3();
        } catch (Throwable ignored) {
        }
        String message = "Libraries loaded: " + (nativeOk ? "yes" : "no")
                + "\nVideo + audio muxing: " + (mux ? "ready" : "unavailable")
                + "\nM4A to MP3 conversion: " + (mp3 ? "ready" : "unavailable")
                + "\n\nConversion depends on the native engine and the source format. "
                + "If the engine is unavailable, downloads keep their original container.";
        new AlertDialog.Builder(this).setTitle("Native media engine").setMessage(message)
                .setPositiveButton("Recheck", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        build();
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void showStorage() {
        int completed = 0;
        long bytes = 0;
        java.util.Set<String> outputs = new java.util.HashSet<String>();
        for (DownloadTask task : App.get().downloads().completed()) {
            android.net.Uri uri = MediaFiles.uri(task);
            if (uri != null && outputs.add(uri.toString()) && MediaFiles.exists(this, task)) {
                completed++;
                bytes += Math.max(0, MediaFiles.size(this, task));
            }
        }
        new AlertDialog.Builder(this).setTitle("Stored on this device")
                .setMessage(completed + " finished download" + (completed == 1 ? "" : "s")
                        + "\n" + humanBytes(bytes) + " in saved files"
                        + "\n\nIncludes public downloads and older private files tracked by SnapSave. "
                        + "Temporary transfers and app cache are separate.")
                .setPositiveButton("Delete all files", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        confirmDeleteAll();
                    }
                }).setNegativeButton("Close", null).show();
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this).setTitle("Delete all downloaded files?")
                .setMessage("This deletes every completed file tracked by SnapSave, wherever it was saved (including "
                        + MediaFiles.locationLabel() + " and any earlier folder). It cannot be undone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        App.get().downloads().deleteAllCompleted();
                        Toast.makeText(SettingsActivity.this, "Downloaded files deleted",
                                Toast.LENGTH_SHORT).show();
                        build();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void showAbout() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "unknown";
        String message = getString(R.string.version_line)
                + "\nPackage: " + getPackageName()
                + "\nAndroid API: " + Build.VERSION.SDK_INT
                + "\nPrimary ABI: " + abi
                + "\n\nSnapSave saves media you are authorized to download. "
                + "Provider availability and DRM-protected streams are not guaranteed.";
        new AlertDialog.Builder(this).setTitle("About SnapSave").setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    private String storageValue() {
        int n = App.get().downloads().completed().size();
        return n + " file" + (n == 1 ? "" : "s");
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private void saveChoice(DialogInterface dialog, Action action) {
        try {
            action.run();
            dialog.dismiss();
            build();
        } catch (IllegalStateException e) {
            showSaveError(e);
        }
    }

    private void actionable(View row, final Action action) {
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    action.run();
                } catch (IllegalStateException e) {
                    showSaveError(e);
                }
            }
        });
    }

    private void info(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    private void showSaveError(IllegalStateException error) {
        // Prefs/SecureCredentials errors are deliberately fixed strings without provider details.
        build();
        info("Settings not saved", error.getMessage() == null ? "Could not save settings. Please retry." : error.getMessage());
    }

    /** Runtime permission on Android 13+, then the native app notification settings. */
    private void openNotificationSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
                return;
            }
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                info("Notifications", "Open this app's notification settings in Android Settings.");
            }
        }
    }

    private static String host(String url) {
        if (url == null || url.isEmpty()) {
            return "Off";
        }
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return "Off";
        }
    }

    private View add(int icon, String title, String sub, String value) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_setting, box, false);
        ((ImageView) row.findViewById(R.id.set_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.set_title)).setText(title);
        ((TextView) row.findViewById(R.id.set_sub)).setText(sub);
        TextView v = (TextView) row.findViewById(R.id.set_value);
        v.setText(value);
        if (value == null || value.isEmpty()) {
            v.setVisibility(View.GONE);
        }
        box.addView(row);
        return row;
    }

    private EditText input(boolean secret, boolean multiline) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_URI) | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(multiline ? 3 : 1);
        input.setSingleLine(!multiline);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF6B7175);
        if (secret) {
            input.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            input.setSaveEnabled(false);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            }
        }
        return input;
    }

    /** Keep the editor open on save failure so the user can fix or retry the entry. */
    private void editDialog(String title, String message, final EditText input,
                            final Action save, String clearLabel, final Action clear, boolean secret) {
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message).setView(wrap)
                .setPositiveButton("Save", null).setNeutralButton(clearLabel, null).setNegativeButton("Cancel", null).create();
        if (secret && dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveChoice(dialog, save);
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveChoice(dialog, clear);
            }
        });
        if (secret) {
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    input.setText("");
                }
            });
        }
    }

    private void editResolver() {
        final EditText input = input(false, false);
        input.setHint("https://resolver.example/api");
        input.setText(Prefs.resolver());
        editDialog("Resolver server", "Optional HTTPS POST endpoint for media links. "
                        + "A change to the host or effective port clears the saved resolver token. "
                        + "Never put credentials in this URL.", input, new Action() {
                    @Override
                    public void run() {
                        Prefs.setResolver(input.getText().toString());
                    }
                }, "Off", new Action() {
                    @Override
                    public void run() {
                        Prefs.setResolver("");
                    }
                }, false);
    }

    private void editKeys() {
        final EditText input = input(true, true);
        input.setHint("One FastSaver key per line");
        editDialog("FastSaver API keys", "Enter replacement keys, one per line. Saved keys are hidden. "
                        + "Save replaces all keys; an empty entry or Clear disables FastSaver. "
                        + "Keys are encrypted on this device and are never resolver tokens.", input, new Action() {
                    @Override
                    public void run() {
                        Prefs.setKeys(input.getText().toString());
                    }
                }, "Clear", new Action() {
                    @Override
                    public void run() {
                        Prefs.setKeys("");
                    }
                }, true);
    }

    private void editResolverToken() {
        final EditText input = input(true, false);
        input.setHint("Resolver bearer token (optional)");
        editDialog("Resolver token", "Enter a replacement token for the currently configured HTTPS origin. "
                        + "Saved tokens are hidden. Save replaces the token; an empty entry or Clear removes it. "
                        + "FastSaver keys are configured separately.", input, new Action() {
                    @Override
                    public void run() {
                        Prefs.setResolverToken(input.getText().toString());
                    }
                }, "Clear", new Action() {
                    @Override
                    public void run() {
                        Prefs.setResolverToken("");
                    }
                }, true);
    }
}
