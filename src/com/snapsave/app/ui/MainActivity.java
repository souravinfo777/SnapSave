package com.snapsave.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.snapsave.app.App;
import com.snapsave.app.R;
import com.snapsave.app.util.Insets;
import com.snapsave.app.util.Util;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private FrameLayout content;
    private Screen[] screens;
    private TextView[] navLabels;
    private ImageView[] navIcons;
    private View[] navItems;
    private TextView navBadge;
    private TextView navDownloadsLabel;

    private int current = 0;

    /** Last clipboard link we already offered, so returning to the app doesn't re-prompt. */
    private String lastPromptedUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Insets.applyToContent(this);

        content = (FrameLayout) findViewById(R.id.content);
        navBadge = (TextView) findViewById(R.id.nav_downloads_badge);
        navDownloadsLabel = (TextView) findViewById(R.id.nav_downloads_label);

        screens = new Screen[]{
                new HomeScreen(this),
                new BrowseScreen(this),
                new DownloadsScreen(this),
                new FilesScreen(this)
        };
        navItems = new View[]{
                findViewById(R.id.nav_home), findViewById(R.id.nav_browse),
                findViewById(R.id.nav_downloads), findViewById(R.id.nav_files)
        };
        navLabels = new TextView[]{
                (TextView) findViewById(R.id.nav_home_label), (TextView) findViewById(R.id.nav_browse_label),
                navDownloadsLabel, (TextView) findViewById(R.id.nav_files_label)
        };
        navIcons = new ImageView[]{
                (ImageView) findViewById(R.id.nav_home_icon), (ImageView) findViewById(R.id.nav_browse_icon),
                (ImageView) findViewById(R.id.nav_downloads_icon), (ImageView) findViewById(R.id.nav_files_icon)
        };

        for (int i = 0; i < screens.length; i++) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
            content.addView(screens[i].root, lp);
            screens[i].root.setVisibility(View.GONE);
            final int idx = i;
            navItems[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                }
            });
        }

        App.get().downloads().addListener(refreshListener);
        int initialTab = savedInstanceState != null ? savedInstanceState.getInt("tab", 0) : 0;
        if (savedInstanceState == null && getIntent() != null && getIntent().hasExtra("tab")) {
            initialTab = getIntent().getIntExtra("tab", 0);
        }
        select(initialTab >= 0 && initialTab < screens.length ? initialTab : 0);
        requestRuntimePermissions();

        String url = getIntent() != null ? getIntent().getStringExtra("url") : null;
        if (url != null) {
            lastPromptedUrl = url;
            select(1);
            ((BrowseScreen) screens[1]).resolveUrl(url);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("tab")) {
            int tab = intent.getIntExtra("tab", 0);
            if (tab >= 0 && tab < screens.length) select(tab);
        }
        if (intent != null && intent.getStringExtra("url") != null) {
            lastPromptedUrl = intent.getStringExtra("url");
            select(1);
            ((BrowseScreen) screens[1]).resolveUrl(intent.getStringExtra("url"));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("tab", current);
        super.onSaveInstanceState(outState);
    }

    public void withStoragePermission(Runnable granted) {
        StoragePermission.run(this, granted);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        StoragePermission.onResult(this, requestCode, permissions, grantResults);
    }

    @Override
    protected void onDestroy() {
        DownloadSheet.dismissFor(this);
        StoragePermission.onDestroy(this);
        App.get().downloads().removeListener(refreshListener);
        if (screens != null) {
            for (Screen s : screens) {
                if (s != null) {
                    s.destroy();   // unregister app-wide listeners held by the singleton
                }
            }
        }
        super.onDestroy();
    }

    private final com.snapsave.app.engine.DownloadManager.Listener refreshListener =
            new com.snapsave.app.engine.DownloadManager.Listener() {
                @Override
                public void onDownloadsChanged() {
                    refreshBadge();
                }
            };

    @Override
    protected void onResume() {
        super.onResume();
        refreshBadge();
        maybePromptClipboard();
    }

    /**
     * SnapTube-style: when a freshly-copied link is on the clipboard, resolve it and drop the user
     * straight into the download-options popup — the same sheet the share flow opens — so no pasting
     * is needed anywhere.
     *
     * Gated by a setting (off by default) so simply opening the app never steals focus with a
     * popup. Browse's paste button offers the same link explicitly.
     */
    private void maybePromptClipboard() {
        if (isFinishing() || !com.snapsave.app.util.Prefs.clipboardPrompt()) {
            return;
        }
        String url = Util.clipboardUrl(this);
        if (url == null || url.equals(lastPromptedUrl)) {
            return;
        }
        // Only react to links we can actually resolve: otherwise every stray copied URL would
        // trigger a resolver request (and a FastSaver call) on app resume.
        if (!com.snapsave.app.engine.Resolvers.isSupportedLink(url)) {
            return;
        }
        lastPromptedUrl = url;
        Intent i = new Intent(this, ShareActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setData(android.net.Uri.parse(url));
        startActivity(i);
    }

    /**
     * Notifications are the only launch-time prompt. Legacy public Downloads access is
     * requested by StoragePermission when the user submits or resumes a download.
     */
    private void requestRuntimePermissions() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return;
        }
        List<String> need = new ArrayList<String>();
        addMissing(need, android.Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            try {
                requestPermissions(need.toArray(new String[need.size()]), 1001);
            } catch (Exception ignored) {
            }
        }
    }

    private void addMissing(List<String> list, String perm) {
        if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }

    private void refreshBadge() {
        int n = App.get().downloads().activeCount();
        if (n > 0) {
            navBadge.setVisibility(View.VISIBLE);
            navBadge.setText(String.valueOf(n));
        } else {
            navBadge.setVisibility(View.GONE);
        }
    }

    public void select(int index) {
        current = index;
        for (int i = 0; i < screens.length; i++) {
            screens[i].root.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        int on = getResources().getColor(R.color.primary);
        int off = getResources().getColor(R.color.text_tertiary);
        for (int i = 0; i < navLabels.length; i++) {
            navLabels[i].setTextColor(i == index ? on : off);
            navIcons[i].setImageTintList(android.content.res.ColorStateList.valueOf(i == index ? on : off));
        }
        screens[index].onShow();
    }

    public int currentTab() {
        return current;
    }

    public void openUrl(String url) {
        select(1);
        ((BrowseScreen) screens[1]).resolveUrl(url);
    }

    public void openSearch() {
        select(1);
    }
}
