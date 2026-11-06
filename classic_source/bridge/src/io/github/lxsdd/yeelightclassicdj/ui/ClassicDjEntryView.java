package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;

/** Real Classic layout entry point: no redirect to the separate Loft-DJ app. */
public final class ClassicDjEntryView extends Button implements View.OnClickListener {
    public ClassicDjEntryView(Context context) { this(context, null); }
    public ClassicDjEntryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setText("Loft DJ");
        setContentDescription("Loft DJ öffnen");
        setAllCaps(false);
        setOnClickListener(this);
    }
    @Override public void onClick(View view) {
        Context context = getContext();
        Intent intent = new Intent(context, ClassicDjActivity.class);
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
