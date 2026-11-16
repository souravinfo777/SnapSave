package com.snapsave.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
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

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.NativeStreams;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.Prefs;

import java.util.Locale;

public class SettingsActivity extends Activity {
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

        final String folder = App.get().downloads().dir().getAbsolutePath();
        actionable(add(R.drawable.ic_folder, "Download folder", folder, ""), new Action() {
            @Override
            public void run() {
                info("Download folder", "Downloads are stored in this app's download folder:\n\n" + folder);
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
        actionable(add(R.drawable.ic_settings, "Native media engine", "SnapTube FFmpeg / M4A engine",
                nativeOk ? "Loaded" : "Unavailable"), new Action() {
            @Override
            public void run() {
                info("Native media engine", "Conversion depends on the native media engine and the available source format. "
                        + "Your preferred output may be unavailable for some downloads.");
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
        add(R.drawable.ic_person, "Stored on this device", "Account sign-in and cloud sync are not available", "Local only");
        actionable(add(R.drawable.ic_check, "About SnapSave", getString(R.string.version_line), ""), new Action() {
            @Override
            public void run() {
                info("About SnapSave", getString(R.string.version_line));
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
