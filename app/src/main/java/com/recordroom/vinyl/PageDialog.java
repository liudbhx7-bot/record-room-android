package com.recordroom.vinyl;

import android.app.Dialog;
import android.content.Context;

/** Gives toolbar and system Back the same return transition; dismiss stays immediate. */
final class PageDialog extends Dialog {
    private Runnable returnAction;
    PageDialog(Context context) { super(context, android.R.style.Theme_Material_Light_NoActionBar); }
    void setReturnAction(Runnable action) { returnAction=action; }
    @Override public void onBackPressed() {
        if(returnAction!=null) returnAction.run(); else super.onBackPressed();
    }
}
