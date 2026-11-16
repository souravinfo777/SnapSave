package com.snapsave.app.ui;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.snapsave.app.R;
import com.snapsave.app.engine.Resolvers;
import com.snapsave.app.model.MediaItem;
import com.snapsave.app.util.Util;

import java.util.List;

public class BrowseScreen extends Screen {

    private EditText input;
    private LinearLayout results;
    private TextView resultsCount;
    private boolean searching = false;

    public BrowseScreen(final MainActivity act) {
        super(act, R.layout.fragment_browse);

        input = (EditText) root.findViewById(R.id.search_input);
        results = (LinearLayout) root.findViewById(R.id.results_container);
        resultsCount = (TextView) root.findViewById(R.id.results_count);

        root.findViewById(R.id.search_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                showEmpty("Search any video, or share a link to SnapSave.");
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch(input.getText().toString());
                    return true;
                }
                return false;
            }
        });

        final TextView tabAll = (TextView) root.findViewById(R.id.tab_all);
        final TextView tabVideos = (TextView) root.findViewById(R.id.tab_videos);
        final TextView tabMusic = (TextView) root.findViewById(R.id.tab_music);
        final TextView tabPlaylists = (TextView) root.findViewById(R.id.tab_playlists);
        View.OnClickListener tabs = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (TextView t : new TextView[]{tabAll, tabVideos, tabMusic, tabPlaylists}) {
                    t.setTextColor(act.getResources().getColor(t == v ? R.color.primary : R.color.text_tertiary));
                }
            }
        };
        tabAll.setOnClickListener(tabs);
        tabVideos.setOnClickListener(tabs);
        tabMusic.setOnClickListener(tabs);
        tabPlaylists.setOnClickListener(tabs);

        root.findViewById(R.id.link_bar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = clipboardUrl();
                if (url != null) {
                    resolveUrl(url);
                }
            }
        });

        showEmpty("Search any video, or share a link to SnapSave.");
    }

    @Override
    public void onShow() {
        String url = clipboardUrl();
        root.findViewById(R.id.link_bar).setVisibility(url != null ? View.VISIBLE : View.GONE);
    }

    private String clipboardUrl() {
        return Util.clipboardUrl(act);
    }

    public void focusSearch() {
        input.requestFocus();
    }

    private void doSearch(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) {
            return;
        }
        String url = Resolvers.firstUrl(q);
        if (url != null && (Resolvers.isYouTube(url) || Resolvers.looksDirect(url))) {
            resolveUrl(url);
            return;
        }
        if (Resolvers.isYouTube(q)) {
            resolveUrl(q);
            return;
        }
        runSearch(q);
    }

    private void runSearch(final String q) {
        if (searching) {
            return;
        }
        searching = true;
        resultsCount.setText("Searching…");
        results.removeAllViews();
        results.addView(plain("Searching YouTube for \"" + q + "\"…", R.color.text_tertiary));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> items = Resolvers.search(q);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        searching = false;
                        if (act.isFinishing() || act.isDestroyed()) {
                            return;   // a stale response must not repopulate a dead screen
                        }
                        showResults(items, items.isEmpty()
                                ? "No results found"
                                : items.size() + " results");
                    }
                });
            }
        }, "search").start();
    }

    /** Resolve a URL and open the download interface. */
    public void resolveUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        String clean = Resolvers.firstUrl(url);
        if (clean == null) {
            clean = url.trim();
        }
        HomeScreen.openDetail(act, new MediaItem(
                Resolvers.isYouTube(clean) ? "YouTube" : prettyName(clean),
                Resolvers.isYouTube(clean) ? "YouTube" : urlHost(clean),
                "", "", Math.abs(clean.hashCode()) % 4, clean), false);
    }

    private static String prettyName(String url) {
        try {
            String n = url.substring(url.lastIndexOf('/') + 1);
            int q = n.indexOf('?');
            return q >= 0 ? n.substring(0, q) : n;
        } catch (Exception e) {
            return "Media";
        }
    }

    private static String urlHost(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "Link";
        }
    }

    private void showEmpty(String msg) {
        resultsCount.setText("");
        results.removeAllViews();
        results.addView(plain(msg, R.color.text_tertiary));
    }

    private TextView plain(String text, int colorRes) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(act.getResources().getColor(colorRes));
        t.setTextSize(15);
        t.setPadding(Util.dp(act, 8), Util.dp(act, 28), 0, 0);
        return t;
    }

    private void showResults(List<MediaItem> items, String countLabel) {
        results.removeAllViews();
        resultsCount.setText(countLabel);
        if (items == null || items.isEmpty()) {
            results.addView(plain("Nothing here. Try another search, or share a link to SnapSave.", R.color.text_tertiary));
            return;
        }
        for (final MediaItem m : items) {
            View row = LayoutInflater.from(act).inflate(R.layout.item_video, results, false);
            com.snapsave.app.util.Ui.bindThumb(row, m.thumbUrl, m.grad);
            ((TextView) row.findViewById(R.id.thumb_duration)).setText(m.duration == null ? "" : m.duration);
            ((TextView) row.findViewById(R.id.video_title)).setText(m.title);
            ((TextView) row.findViewById(R.id.video_meta)).setText(HomeScreen.metaLine(m));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HomeScreen.openDetail(act, m);
                }
            });
            row.findViewById(R.id.video_action).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HomeScreen.openDetail(act, m);
                }
            });
            results.addView(row);
        }
    }
}
