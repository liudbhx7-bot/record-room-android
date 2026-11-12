package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;

/** Album covers in the search display case. Each cover keeps its own album identity. */
final class SearchSleeves {
    private final Context context;
    private final ThemeStore themes;

    SearchSleeves(Context context) {
        this.context = context;
        themes = new ThemeStore(context);
    }

    View spread(List<Track> albums, Consumer<Track> open) {
        int count = Math.min(3, albums.size());
        FrameLayout deck = new FrameLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                int edge = Math.min(dp(138), Math.round(width * .39f));
                int gap = Math.min(edge * 2 / 3, (width - edge - dp(32)) / Math.max(1, count - 1));
                int start = (width - edge - (count - 1) * gap) / 2;
                for (int child = 0; child < count; child++) {
                    int position = count - 1 - child;
                    LayoutParams params = (LayoutParams) getChildAt(child).getLayoutParams();
                    params.width = params.height = edge;
                    params.leftMargin = start + position * gap;
                    params.topMargin = dp(12 + position * 2);
                }
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(edge + dp(34), MeasureSpec.EXACTLY));
            }
        };
        deck.setBackground(DesignSystem.settingsCard(context,16));
        for (int i = count - 1; i >= 0; i--) {
            Track track = albums.get(i);
            CoverView sleeve = cover(track);
            sleeve.setContentDescription("打开专辑 " + track.album);
            sleeve.setFocusable(true);
            sleeve.setRotation(count == 1 ? 0 : -7 + i * 6);
            sleeve.setElevation(dp(3 + count - i));
            sleeve.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            sleeve.setOnClickListener(v -> open.accept(track));
            deck.addView(sleeve, new FrameLayout.LayoutParams(dp(100), dp(100), Gravity.LEFT | Gravity.TOP));
        }
        return deck;
    }

    static final class Jacket {
        final Track seed;
        final AggregateSearch.AlbumResult result;
        final String name, subtitle, cover, key;
        Jacket(Track t) {seed=t;result=null;name=t.album;subtitle=t.year.isEmpty()?t.albumArtist:t.year+" · "+t.albumArtist;cover=t.artworkUri;key=t.albumKey();}
        Jacket(AggregateSearch.AlbumResult a){seed=null;result=a;name=a.album.name;subtitle=a.album.artist;cover=a.album.coverUrl;key=a.providerId+"\u001falbum\u001f"+a.album.id;}
    }
    View pair(List<Jacket> albums, int start, Consumer<Jacket> open) {
        LinearLayout row = new LinearLayout(context);
        row.setBaselineAligned(false);
        for (int i = start; i < start + 2; i++) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > start) params.leftMargin = dp(10);
            row.addView(card, params);
            if (i >= albums.size()) continue;
            Jacket track = albums.get(i);
            CoverView cover = new CoverView(context);
            cover.setBackground(DesignSystem.settingsCard(context,5));
            cover.setClipToOutline(true);
            if(track.seed!=null)cover.setTrack(track.seed,themes,368);else cover.setSource(track.cover,368);
            card.addView(cover, new LinearLayout.LayoutParams(-1, dp(112)));
            card.addView(label(track.name, 15, true));
            card.addView(label(track.subtitle, 12, false));
            card.setPadding(0,0,0,dp(8));
            card.setContentDescription("打开专辑 " + track.name + "，" + track.subtitle);
            card.setFocusable(true);
            card.setOnClickListener(v -> open.accept(track));
        }
        return row;
    }

    private CoverView cover(Track track) {
        CoverView cover = new CoverView(context);
        cover.setBackground(DesignSystem.settingsCard(context,5));
        cover.setClipToOutline(true);
        cover.setTrack(track, themes, 368);
        return cover;
    }

    private TextView label(String value, int size, boolean title) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(title ? DesignSystem.onAmbient : DesignSystem.onAmbientMuted);
        if (title) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(0,dp(title ? 6 : 2),0,0);
        return text;
    }
    private int dp(float value) { return DesignSystem.dp(context,value); }
}
