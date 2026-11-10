package com.recordroom.vinyl;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A catalog requirement, never a claim about the listener's current membership. */
final class PlaybackAccessBadge extends TextView {
    PlaybackAccessBadge(Context context){
        super(context);
        setTextSize(11);setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        setTextColor(DesignSystem.onAmbient);setPadding(dp(7),dp(3),dp(7),dp(3));
        setBackground(DesignSystem.rounded(context,0x18ffffff,6));setMaxLines(2);setEllipsize(TextUtils.TruncateAt.END);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(false);setFocusable(false);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);params.topMargin=dp(6);setLayoutParams(params);
        bind("");
    }
    void bind(String label){String value=label==null?"":label;setText(value);setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);}
    private int dp(float value){return DesignSystem.dp(getContext(),value);}
}
