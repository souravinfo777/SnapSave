package com.snapsave.app.ui;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.snapsave.app.R;
import com.snapsave.app.engine.Resolvers;
import com.snapsave.app.model.MediaItem;
import com.snapsave.app.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BrowseScreen extends Screen {

    private EditText input;
    private LinearLayout results;
    private TextView resultsCount;
    private boolean searching = false;

    private int category = 0; // all, videos, music, playlists
    private int filter = 0;   // any, HD, this week, subtitles
    private List<MediaItem> currentItems = new ArrayList<MediaItem>();
    private String currentQuery = "";
    private TextView[] categoryTabs;
    private TextView[] filterChips;

    public BrowseScreen(final MainActivity act) {
        super(act, R.layout.fragment_browse);

        input = (EditText) root.findViewById(R.id.search_input);
        results = (LinearLayout) root.findViewById(R.id.results_container);
        resultsCount = (TextView) root.findViewById(R.id.results_count);

        root.findViewById(R.id.search_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                currentItems.clear();
                currentQuery = "";
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
        categoryTabs = new TextView[]{tabAll, tabVideos, tabMusic, tabPlaylists};
        View.OnClickListener tabs = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < categoryTabs.length; i++) {
                    if (categoryTabs[i] == v) {
                        category = i;
                        break;
                    }
                }
                updateTabs();
                if (category == 3) {
                    showUnsupported("Playlist browsing is not available from the current search source.\nSearch returns individual videos.");
                } else if (currentQuery.isEmpty()) {
                    showEmpty(category == 2
                            ? "Search for a song or artist to filter music videos."
                            : "Search any video, or share a link to SnapSave.");
                } else {
                    showResults(currentItems, null);
                }
            }
        };
        tabAll.setOnClickListener(tabs);
        tabVideos.setOnClickListener(tabs);
        tabMusic.setOnClickListener(tabs);
        tabPlaylists.setOnClickListener(tabs);

        filterChips = findFilterChips();
        View.OnClickListener filters = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < filterChips.length; i++) {
                    if (filterChips[i] == v) {
                        filter = i;
                        break;
                    }
                }
                updateFilters();
                if (category == 3) {
                    showUnsupported("Playlist browsing is not available from the current search source.\nSearch returns individual videos.");
                } else if (currentQuery.isEmpty()) {
                    showEmpty(filterMessage());
                } else {
                    showResults(currentItems, null);
                }
            }
        };
        for (TextView chip : filterChips) {
            chip.setOnClickListener(filters);
        }

        root.findViewById(R.id.link_bar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = clipboardUrl();
                if (url != null) {
                    resolveUrl(url);
                }
            }
        });

        updateTabs();
        updateFilters();
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
        // Every pasted absolute URL is a link to resolve. Searching a page URL produces misleading
        // catalogue results and makes direct links impossible to download from Browse.
        if (url != null) {
            resolveUrl(url);
            return;
        }
        // Resolvers currently accepts a bare YouTube id; turn it into a canonical page URL before
        // opening detail so the resolver does not reject the non-URL identifier later.
        if (Resolvers.isYouTube(q)) {
            String id = Resolvers.videoId(q);
            resolveUrl("https://www.youtube.com/watch?v=" + id);
            return;
        }
        runSearch(q);
    }

    private void runSearch(final String q) {
        if (category == 3) {
            currentQuery = q;
            currentItems.clear();
            showUnsupported("Playlist browsing is not available from the current search source.\nSearch returns individual videos.");
            return;
        }
        if (searching) {
            return;
        }
        searching = true;
        currentQuery = q;
        final int requestedCategory = category;
        resultsCount.setText("Searching…");
        results.removeAllViews();
        results.addView(plain(requestedCategory == 2
                ? "Searching videos to apply the Music filter…"
                : "Searching YouTube for \"" + q + "\"…", R.color.text_tertiary));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> items = Resolvers.search(q);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        searching = false;
                        if (act.isFinishing() || act.isDestroyed()) {
                            return;
                        }
                        currentItems = items == null
                                ? new ArrayList<MediaItem>() : new ArrayList<MediaItem>(items);
                        showResults(currentItems, null);
                    }
                });
            }
        }, "search").start();
    }

    /** Resolve a URL and open the download interface. */
    public void resolveUrl(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        String clean = Resolvers.firstUrl(url);
        if (clean == null) {
            clean = url.trim();
        }
        if (Resolvers.isYouTube(clean) && !clean.startsWith("http://")
                && !clean.startsWith("https://")) {
            clean = "https://www.youtube.com/watch?v=" + Resolvers.videoId(clean);
        }
        final String target = clean;
        resultsCount.setText("Resolving link…");
        results.removeAllViews();
        results.addView(plain("Preparing this link…", R.color.text_tertiary));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Resolvers.Outcome outcome = Resolvers.resolveDetailed(target);
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (act.isFinishing() || act.isDestroyed()) {
                            return;
                        }
                        if (outcome == null || outcome.media == null) {
                            String message = outcome == null ? "Couldn't resolve this link."
                                    : Resolvers.firstText(outcome.message, "Couldn't resolve this link.");
                            resultsCount.setText("Link unavailable");
                            results.removeAllViews();
                            results.addView(plain(message, R.color.text_tertiary));
                            Toast.makeText(act, message, Toast.LENGTH_LONG).show();
                            return;
                        }
                        String title = Resolvers.firstText(outcome.media.title,
                                Resolvers.isYouTube(target) ? "YouTube" : prettyName(target));
                        String author = Resolvers.firstText(outcome.media.author,
                                Resolvers.isYouTube(target) ? "YouTube" : urlHost(target));
                        resultsCount.setText("Link ready");
                        results.removeAllViews();
                        results.addView(plain(title, R.color.text_secondary));
                        DownloadSheet.open(act, outcome.media, title, author,
                                outcome.media.grad, false);
                    }
                });
            }
        }, "browse-resolve").start();
    }

    private TextView[] findFilterChips() {
        ArrayList<TextView> chips = new ArrayList<TextView>();
        collectFilterChips(root, chips);
        return chips.toArray(new TextView[chips.size()]);
    }

    private void collectFilterChips(View view, ArrayList<TextView> chips) {
        if (view instanceof HorizontalScrollView) {
            View child = ((HorizontalScrollView) view).getChildAt(0);
            if (child instanceof ViewGroup) {
                ViewGroup row = (ViewGroup) child;
                for (int i = 0; i < row.getChildCount(); i++) {
                    View item = row.getChildAt(i);
                    if (item instanceof TextView) {
                        chips.add((TextView) item);
                    }
                }
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectFilterChips(group.getChildAt(i), chips);
            }
        }
    }

    private void updateTabs() {
        int on = act.getResources().getColor(R.color.primary);
        int off = act.getResources().getColor(R.color.text_tertiary);
        if (categoryTabs != null) {
            for (int i = 0; i < categoryTabs.length; i++) {
                categoryTabs[i].setTextColor(category == i ? on : off);
            }
        }
    }

    private void updateFilters() {
        if (filterChips == null) {
            return;
        }
        for (int i = 0; i < filterChips.length; i++) {
            filterChips[i].setBackgroundResource(i == filter
                    ? R.drawable.bg_chip_active : R.drawable.bg_chip);
            filterChips[i].setTextColor(act.getResources().getColor(i == filter
                    ? R.color.on_primary : R.color.text_secondary));
            filterChips[i].setTypeface(null, i == filter ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
    }

    private String filterMessage() {
        if (filter == 1) {
            return "HD filter ready. Search to show results marked with HD, 720p, 1080p, or 4K.";
        }
        if (filter == 2) {
            return "This week filter needs upload dates, which this search source does not provide.";
        }
        if (filter == 3) {
            return "Subtitle filtering needs subtitle metadata, which this search source does not provide.";
        }
        return category == 2 ? "Search for a song or artist to filter music videos."
                : "Search any video, or share a link to SnapSave.";
    }

    private boolean matches(MediaItem m) {
        if (m == null) {
            return false;
        }
        if (category == 2 && !isMusic(m)) {
            return false;
        }
        if (filter == 1) {
            String text = textOf(m);
            return text.contains(" hd") || text.startsWith("hd") || text.contains("720p")
                    || text.contains("1080p") || text.contains("1440p") || text.contains("2160p")
                    || text.contains("4k");
        }
        // There is no date or subtitle field in MediaItem. Keep these filters visible and explain
        // why they cannot produce trustworthy matches instead of claiming backend support.
        return filter != 2 && filter != 3;
    }

    private boolean isMusic(MediaItem m) {
        String text = textOf(m);
        return text.contains("music") || text.contains("song") || text.contains("audio")
                || text.contains("official lyric") || text.contains("lyrics") || text.contains("mix")
                || text.contains("album") || text.contains("concert") || text.contains("soundtrack");
    }

    private String textOf(MediaItem m) {
        return ((m.title == null ? "" : m.title) + " "
                + (m.author == null ? "" : m.author) + " "
                + (m.meta == null ? "" : m.meta)).toLowerCase(Locale.US);
    }

    private void showEmpty(String msg) {
        resultsCount.setText("");
        results.removeAllViews();
        results.addView(plain(msg, R.color.text_tertiary));
    }

    private void showUnsupported(String msg) {
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

    private void showResults(List<MediaItem> items, String ignored) {
        results.removeAllViews();
        if (category == 3) {
            showUnsupported("Playlist browsing is not available from the current search source.\nSearch returns individual videos.");
            return;
        }
        List<MediaItem> shownItems = new ArrayList<MediaItem>();
        if (items != null) {
            for (MediaItem item : items) {
                if (matches(item)) {
                    shownItems.add(item);
                }
            }
        }
        resultsCount.setText(shownItems.isEmpty() ? "No matching results" : shownItems.size() + " results");
        if (shownItems.isEmpty()) {
            if (filter == 2 || filter == 3) {
                results.addView(plain(filterMessage(), R.color.text_tertiary));
            } else if (category == 2) {
                results.addView(plain("No music videos matched this search. Try a song or artist name.",
                        R.color.text_tertiary));
            } else {
                results.addView(plain("Nothing here. Try another search, or share a link to SnapSave.",
                        R.color.text_tertiary));
            }
            return;
        }
        for (final MediaItem m : shownItems) {
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
}
