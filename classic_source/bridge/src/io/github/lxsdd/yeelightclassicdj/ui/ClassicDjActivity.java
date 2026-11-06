package io.github.lxsdd.yeelightclassicdj.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** New screen INSIDE the Classic private fork, not a separate application launcher. */
public final class ClassicDjActivity extends Activity {
    private TextView status;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(ClassicDjService.uiStatus());
            statusHandler.postDelayed(this, 500L);
        }
    };
    private static final int MIC_REQUEST = 41;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 50, 28, 28);
        layout.setGravity(Gravity.TOP);
        TextView title = new TextView(this);
        title.setText("Loft DJ · Yeelight Classic");
        title.setTextSize(22);
        layout.addView(title);
        status = new TextView(this);
        status.setText("Lokaler DJ-Dienst. Kein automatischer Lampenstart.");
        layout.addView(status);
        Button start = new Button(this);
        start.setText("Mikrofon starten");
        start.setOnClickListener(v -> requestMic());
        layout.addView(start);
        Button stop = new Button(this);
        stop.setText("DJ stoppen");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, ClassicDjService.class);
            i.setAction(ClassicDjService.ACTION_STOP);
            startService(i);
            status.setText("Stopp angefordert; Lampenstatus muss bestätigt werden.");
        });
        layout.addView(stop);
        setContentView(layout);
    }

    @Override protected void onResume() {
        super.onResume();
        statusHandler.post(statusRefresh);
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    private void requestMic() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        startMicService();
    }

    private void startMicService() {
        Intent i = new Intent(this, ClassicDjService.class);
        i.setAction(ClassicDjService.ACTION_MIC);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        status.setText("Mikrofonanalyse angefordert. Lampensteuerung noch nicht freigeschaltet.");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startMicService();
            else status.setText("Mikrofonberechtigung fehlt. Keine Steuerung gestartet.");
        }
    }
}
