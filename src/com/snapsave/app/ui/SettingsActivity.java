package com.snapsave.app.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.engine.NativeStreams;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.Prefs;

public class SettingsActivity extends Activity {

    private LinearLayout box;

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

        add(R.drawable.ic_folder, "Download folder", App.get().downloads().dir().getAbsolutePath(), "");
        add(R.drawable.ic_tune, "Video quality", "Default quality for downloads", "Best");
        add(R.drawable.ic_music, "Audio format", "Preferred audio output", "MP3 • 320");
        add(R.drawable.ic_globe, "Download only on Wi-Fi", "Avoid mobile data usage", "Off");
        add(R.drawable.ic_settings, "Native media engine", "SnapTube FFmpeg / M4A engine",
                nativeOk ? "Loaded" : "Unavailable");

        View r = add(R.drawable.ic_link, "Resolver server",
                "YouTube • Instagram • Facebook • TikTok • Twitter • LinkedIn",
                host(resolver));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editResolver();
            }
        });

        View k = add(R.drawable.ic_check, "API keys",
                "FastSaver keys (one per line) — rotated automatically when a limit is hit",
                Prefs.keyCount() == 0 ? "Off" : Prefs.keyCount() + " key" + (Prefs.keyCount() > 1 ? "s" : ""));
        k.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editKeys();
            }
        });

        add(R.drawable.ic_person, "SnapSave account", "Sign in to sync", "Guest");
        add(R.drawable.ic_check, "About SnapSave", getString(R.string.version_line), "");
    }

    private static String host(String url) {
        if (url == null || url.isEmpty()) {
            return "Off";
        }
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "Set";
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

    private void editResolver() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(Prefs.DEFAULT_RESOLVER);
        input.setText(Prefs.resolver());
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF6B7175);
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Resolver server")
                .setMessage("POSTs {\"url\":\"...\"} (form) and reads back media links.")
                .setView(wrap)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setResolver(input.getText().toString());
                        Toast.makeText(SettingsActivity.this, "Saved", Toast.LENGTH_SHORT).show();
                        build();
                    }
                })
                .setNeutralButton("Default", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setResolver(Prefs.DEFAULT_RESOLVER);
                        build();
                    }
                })
                .setNegativeButton("Off", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setResolver("");
                        build();
                    }
                })
                .show();
    }

    private void editKeys() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(3);
        input.setHint("fs_sk_...\nfs_sk_...");
        input.setText(TextUtils.join("\n", Prefs.keys()));
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF6B7175);
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("API keys")
                .setMessage("Paste one FastSaver key per line. When a key hits its rate/credit limit the next one is used automatically.")
                .setView(wrap)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setKeys(input.getText().toString());
                        build();
                    }
                })
                .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.setKeys("");
                        build();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
