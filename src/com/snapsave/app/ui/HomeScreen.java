package com.snapsave.app.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.snapsave.app.R;
import com.snapsave.app.engine.Resolvers;
import com.snapsave.app.model.MediaItem;
import com.snapsave.app.util.ImageLoader;
import com.snapsave.app.util.Ui;
import com.snapsave.app.util.Util;

import java.util.List;

public class HomeScreen extends Screen {

    private static final String[] SITES = {"YouTube", "Facebook", "Instagram", "TikTok",
            "DailyMotion", "Vimeo", "Twitch", "More sites"};
    private static final String[] LETTERS = {"YT", "f", "IG", "TT", "DM", "Vm", "Tw", "+"};

    private LinearLayout trendingContainer;
    private boolean loadingTrending = false;
    private long lastAttempt = 0;

    public HomeScreen(MainActivity act) {
        super(act, R.layout.fragment_home);
        trendingContainer = (LinearLayout) root.findViewById(R.id.trending_container);
        buildSites();
        loadTrending();

        root.findViewById(R.id.search_bar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.openSearch();
            }
        });
        root.findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.startActivity(new Intent(act, SettingsActivity.class));
            }
        });
        root.findViewById(R.id.trending_see_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.openSearch();
            }
        });
    }

    @Override
    public void onShow() {
        if (trendingContainer.getChildCount() == 0) {
            loadTrending();
        }
    }

    // -------- popular sites --------

    private void buildSites() {
        GridLayout grid = (GridLayout) root.findViewById(R.id.sites_grid);
        grid.removeAllViews();
        for (int i = 0; i < SITES.length; i++) {
            View item = LayoutInflater.from(act).inflate(R.layout.item_site, grid, false);
            ((TextView) item.findViewById(R.id.site_letter)).setText(LETTERS[i]);
            ((TextView) item.findViewById(R.id.site_name)).setText(SITES[i]);
            item.findViewById(R.id.site_bg).setBackground(siteBg(i));
            final String siteName = SITES[i];
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    promptLink(siteName);
                }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(0, 0, 0, Util.dp(act, 14));
            grid.addView(item, lp);
        }
    }

    private GradientDrawable siteBg(int i) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        int a, b;
        switch (i) {
            case 0: a = 0xFFFF0000; b = a; break;
            case 1: a = 0xFF1877F2; b = a; break;
            case 2: a = 0xFF833AB4; b = 0xFFFD1D1D; break;
            case 3: a = 0xFF16181A; b = a; break;
            case 4: a = 0xFF0A3B63; b = 0xFF0E6E8A; break;
            case 5: a = 0xFF1FA0B8; b = a; break;
            case 6: a = 0xFF9146FF; b = a; break;
            default: a = 0xFF1A1E22; b = a; break;
        }
        if (a == b) {
            d.setColor(a);
        } else {
            d.setOrientation(GradientDrawable.Orientation.TL_BR);
            d.setColors(new int[]{a, b});
        }
        if (i == 7) {
            d.setStroke(Util.dp(act, 1), 0xFF3A4046);
        }
        return d;
    }

    // -------- live trending --------

    private void loadTrending() {
        if (loadingTrending) {
            return;
        }
        loadingTrending = true;
        lastAttempt = System.currentTimeMillis();
        trendingContainer.removeAllViews();
        trendingContainer.addView(note("Loading trending…", R.color.text_tertiary));

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<MediaItem> items = Resolvers.trending();
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadingTrending = false;
                        if (act.isFinishing() || act.isDestroyed()) {
                            return;   // the host activity is gone — never touch dead views
                        }
                        renderTrending(items);
                    }
                });
            }
        }, "trending").start();
    }

    private void renderTrending(List<MediaItem> items) {
        trendingContainer.removeAllViews();
        if (items == null || items.isEmpty()) {
            TextView empty = note("Couldn't load trending.\nTap to retry, or share a video link to SnapSave.",
                    R.color.text_tertiary);
            empty.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (System.currentTimeMillis() - lastAttempt > 3000) {
                        loadTrending();
                    }
                }
            });
            trendingContainer.addView(empty);
            return;
        }
        MediaItem feat = items.get(0);
        View fv = LayoutInflater.from(act).inflate(R.layout.item_video_featured, trendingContainer, false);
        ImageLoader.load((ImageView) fv.findViewById(R.id.feat_bg), feat.thumbUrl, Util.grad(feat.grad));
        ((TextView) fv.findViewById(R.id.feat_title)).setText(feat.title);
        ((TextView) fv.findViewById(R.id.feat_meta)).setText(metaLine(feat));
        fv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail(feat);
            }
        });
        fv.findViewById(R.id.feat_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail(feat);
            }
        });
        trendingContainer.addView(fv);

        for (int i = 1; i < items.size(); i++) {
            trendingContainer.addView(buildRow(trendingContainer, items.get(i)));
        }
    }

    private TextView note(String text, int colorRes) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(act.getResources().getColor(colorRes));
        t.setTextSize(14);
        t.setPadding(0, Util.dp(act, 18), 0, Util.dp(act, 18));
        return t;
    }

    static String metaLine(MediaItem m) {
        StringBuilder sb = new StringBuilder();
        if (m.author != null && !m.author.isEmpty()) sb.append(m.author);
        if (m.meta != null && !m.meta.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(m.meta);
        }
        if (m.duration != null && !m.duration.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(m.duration);
        }
        return sb.toString();
    }

    private View buildRow(ViewGroup parent, final MediaItem m) {
        View row = LayoutInflater.from(act).inflate(R.layout.item_video, parent, false);
        Ui.bindThumb(row, m.thumbUrl, m.grad);
        ((TextView) row.findViewById(R.id.thumb_duration)).setText(m.duration == null ? "" : m.duration);
        ((TextView) row.findViewById(R.id.video_title)).setText(m.title);
        ((TextView) row.findViewById(R.id.video_meta)).setText(metaLine(m));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail(m);
            }
        });
        row.findViewById(R.id.video_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDetail(m);
            }
        });
        return row;
    }

    public static void openDetail(Context c, MediaItem m) {
        openDetail(c, m, false);
    }

    public static void openDetail(Context c, MediaItem m, boolean autoSheet) {
        Intent i = new Intent(c, VideoDetailActivity.class);
        i.putExtra("title", m.title);
        i.putExtra("author", m.author);
        i.putExtra("duration", m.duration);
        i.putExtra("meta", m.meta);
        i.putExtra("grad", m.grad);
        i.putExtra("url", m.pageUrl);
        i.putExtra("thumbUrl", m.thumbUrl);
        i.putExtra("autoSheet", autoSheet);
        c.startActivity(i);
    }

    private void openDetail(MediaItem m) {
        openDetail(act, m, false);
    }

    private void promptLink(String site) {
        final EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("Paste a " + site + " link");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF6B7175);
        FrameLayout wrap = new FrameLayout(act);
        int pad = Util.dp(act, 18);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);
        new AlertDialog.Builder(act)
                .setTitle(site)
                .setView(wrap)
                .setPositiveButton("Download", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String text = input.getText().toString().trim();
                        String url = Resolvers.firstUrl(text);
                        if (url == null && !text.isEmpty() && Resolvers.isYouTube(text)) {
                            url = text;
                        }
                        if (url != null) {
                            act.openUrl(url);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
