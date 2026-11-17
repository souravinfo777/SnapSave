package com.snapsave.app.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.snapsave.app.R;
import com.snapsave.app.engine.Resolvers;
import com.snapsave.app.model.MediaItem;
import com.snapsave.app.util.Util;

import java.util.ArrayList;
import java.util.List;

public class BrowseScreen extends Screen {
    private final EditText input;
    private final LinearLayout results;
    private final TextView resultsCount;
    private final TextView[] tabs;
    private List<MediaItem> currentItems = new ArrayList<MediaItem>();
    private String currentQuery = "";
    private int category;
    private int generation;
    private boolean destroyed;
    private Runnable pendingResult;

    public BrowseScreen(final MainActivity act) {
        super(act, R.layout.fragment_browse);
        input = (EditText) root.findViewById(R.id.search_input);
        results = (LinearLayout) root.findViewById(R.id.results_container);
        resultsCount = (TextView) root.findViewById(R.id.results_count);
        root.findViewById(R.id.browse_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideKeyboard(); act.select(0); }
        });
        root.findViewById(R.id.search_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                generation++; pendingResult = null;
                input.setText(""); currentItems.clear(); currentQuery = "";
                showEmpty("Search for a video, or paste a media link.\nMusic, playlists and metadata filters are unavailable from this search source.");
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP) {
                    doSearch(input.getText().toString()); return true;
                }
                return false;
            }
        });
        tabs = new TextView[]{(TextView) root.findViewById(R.id.tab_all), (TextView) root.findViewById(R.id.tab_videos),
                (TextView) root.findViewById(R.id.tab_music), (TextView) root.findViewById(R.id.tab_playlists)};
        for (int i = 0; i < tabs.length; i++) {
            final int selected = i;
            if (i < 2) tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { category = selected; updateTabs(); }
            });
            else {
                tabs[i].setEnabled(false); tabs[i].setAlpha(0.4f);
                tabs[i].setContentDescription(tabs[i].getText() + " unavailable from the current search source");
            }
        }
        List<TextView> chips = new ArrayList<TextView>();
        collectFilterChips(root, chips);
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            if (i == 0) chip.setText("All results");
            else {
                chip.setText(chip.getText() + " • unavailable");
                chip.setEnabled(false); chip.setAlpha(0.4f);
                chip.setContentDescription(chip.getText() + ": search source does not provide this metadata");
            }
        }
        root.findViewById(R.id.link_bar).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = Util.clipboardUrl(act);
                if (url != null) resolveUrl(url);
            }
        });
        updateTabs();
        showEmpty("Search for a video, or paste a media link.\nMusic, playlists and metadata filters are unavailable from this search source.");
    }

    @Override public void onShow() {
        root.findViewById(R.id.link_bar).setVisibility(Util.clipboardUrl(act) == null ? View.GONE : View.VISIBLE);
        if (pendingResult != null) {
            Runnable result = pendingResult; pendingResult = null; result.run();
        }
    }
    @Override public void destroy() { destroyed = true; generation++; pendingResult = null; }
    public void focusSearch() { input.requestFocus(); }

    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }
    private void updateTabs() {
        for (int i = 0; i < tabs.length; i++) tabs[i].setTextColor(act.getResources().getColor(
                category == i ? R.color.primary : R.color.text_tertiary));
    }

    private void doSearch(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        String url = Resolvers.firstUrl(q);
        if (url != null) { resolveUrl(url); return; }
        if (Resolvers.isYouTube(q)) { resolveUrl("https://www.youtube.com/watch?v=" + Resolvers.videoId(q)); return; }
        runSearch(q);
    }

    private void runSearch(final String q) {
        final int request = ++generation;
        pendingResult = null; currentQuery = q;
        resultsCount.setText("Searching…"); results.removeAllViews();
        results.addView(plain("Searching YouTube for “" + q + "”…", R.color.text_tertiary));
        new Thread(new Runnable() {
            @Override public void run() {
                final List<MediaItem> items = Resolvers.search(q);
                deliver(request, new Runnable() {
                    @Override public void run() {
                        currentItems = items == null ? new ArrayList<MediaItem>() : new ArrayList<MediaItem>(items);
                        showResults();
                    }
                });
            }
        }, "search").start();
    }

    /** Resolve an absolute media URL, preserving the provider's failure message. */
    public void resolveUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        hideKeyboard();
        String clean = Resolvers.firstUrl(url);
        if (clean == null) clean = url.trim();
        if (Resolvers.isYouTube(clean) && !clean.startsWith("http://") && !clean.startsWith("https://"))
            clean = "https://www.youtube.com/watch?v=" + Resolvers.videoId(clean);
        final String target = clean;
        final int request = ++generation;
        pendingResult = null;
        resultsCount.setText("Resolving link…"); results.removeAllViews();
        results.addView(plain("Preparing this link…", R.color.text_tertiary));
        new Thread(new Runnable() {
            @Override public void run() {
                final Resolvers.Outcome outcome = Resolvers.resolveDetailed(target);
                deliver(request, new Runnable() {
                    @Override public void run() {
                        if (outcome == null || outcome.media == null) {
                            String message = outcome == null ? "Couldn't resolve this link."
                                    : Resolvers.firstText(outcome.message, "Couldn't resolve this link.");
                            resultsCount.setText("Link unavailable"); results.removeAllViews();
                            results.addView(plain(message, R.color.text_tertiary));
                            Toast.makeText(act, message, Toast.LENGTH_LONG).show(); return;
                        }
                        String title = Resolvers.firstText(outcome.media.title, "Media");
                        String author = Resolvers.firstText(outcome.media.author, host(target));
                        resultsCount.setText("Link ready"); results.removeAllViews();
                        results.addView(plain(title, R.color.text_secondary));
                        DownloadSheet.open(act, outcome.media, title, author, outcome.media.grad, false);
                    }
                });
            }
        }, "browse-resolve").start();
    }

    private void deliver(final int request, final Runnable result) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (destroyed || act.isFinishing() || act.isDestroyed() || generation != request) return;
                if (act.currentTab() != 1 || !root.isShown()) pendingResult = result;
                else result.run();
            }
        });
    }
    private void showEmpty(String message) {
        resultsCount.setText(""); results.removeAllViews(); results.addView(plain(message, R.color.text_tertiary));
    }
    private TextView plain(String text, int color) {
        TextView view = new TextView(act); view.setText(text);
        view.setTextColor(act.getResources().getColor(color)); view.setTextSize(15);
        view.setPadding(Util.dp(act, 8), Util.dp(act, 24), Util.dp(act, 8), Util.dp(act, 16));
        return view;
    }
    private void showResults() {
        results.removeAllViews();
        resultsCount.setText(currentItems.size() + " results • " + currentQuery);
        if (currentItems.isEmpty()) { results.addView(plain("No results. Try another search or paste a media link.", R.color.text_tertiary)); return; }
        for (final MediaItem item : currentItems) {
            View row = LayoutInflater.from(act).inflate(R.layout.item_video, results, false);
            com.snapsave.app.util.Ui.bindThumb(row, item.thumbUrl, item.grad);
            ((TextView) row.findViewById(R.id.thumb_duration)).setText(item.duration == null ? "" : item.duration);
            ((TextView) row.findViewById(R.id.video_title)).setText(item.title);
            ((TextView) row.findViewById(R.id.video_meta)).setText(HomeScreen.metaLine(item));
            View.OnClickListener open = new View.OnClickListener() {
                @Override public void onClick(View v) { HomeScreen.openDetail(act, item); }
            };
            row.setOnClickListener(open); row.findViewById(R.id.video_action).setOnClickListener(open);
            results.addView(row);
        }
    }
    private void collectFilterChips(View view, List<TextView> chips) {
        if (view instanceof HorizontalScrollView) {
            View child = ((HorizontalScrollView) view).getChildAt(0);
            if (child instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) child;
                for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i) instanceof TextView)
                    chips.add((TextView) group.getChildAt(i));
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectFilterChips(group.getChildAt(i), chips);
        }
    }
    private static String host(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Exception e) { return "Link"; }
    }
}
