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
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/** New screen INSIDE the Classic private fork, not a separate application launcher. */
public final class ClassicDjActivity extends Activity {
    public static final String EXTRA_SELECTED_IPS = "io.github.lxsdd.yeelightclassicdj.SELECTED_LAMP_IPS";
    private TextView status;
    private EditText lampIps;
    private CheckBox musicOptIn;
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
        TextView warning = new TextView(this);
        warning.setText("Nur Color4/1S mit aktiviertem LAN-Zugriff. Music-Mode wird ausschließlich für gewählte Lampen gestartet. Bei Verbindungsfehler bitte Lampenstatus kontrollieren.");
        layout.addView(warning);
        lampIps = new EditText(this);
        lampIps.setSingleLine(false);
        lampIps.setHint("Color4-IP(s), durch Komma getrennt (max. 4)");
        layout.addView(lampIps);
        musicOptIn = new CheckBox(this);
        musicOptIn.setText("Diese Color4-Lampen ausdrücklich für DJ-Music auswählen");
        layout.addView(musicOptIn);
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
        java.util.ArrayList<String> ips = new java.util.ArrayList<>();
        if (musicOptIn.isChecked()) {
            String input = lampIps.getText().toString().trim();
            if (input.isEmpty()) {
                status.setText("Mindestens eine Color4-IP eingeben oder Auswahl deaktivieren.");
                return;
            }
            for (String part : input.split(",")) {
                String ip = part.trim();
                if (!validPrivateLanIp(ip) || ips.contains(ip)) {
                    status.setText("Ungültige oder doppelte Color4-IP: bitte prüfen.");
                    return;
                }
                ips.add(ip);
            }
            if (ips.size() > 4) { status.setText("Maximal vier Lampen gleichzeitig."); return; }
        }
        Intent i = new Intent(this, ClassicDjService.class);
        i.setAction(ClassicDjService.ACTION_MIC);
        i.putExtra(EXTRA_SELECTED_IPS, ips.toArray(new String[0]));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        status.setText(ips.isEmpty() ? "Mikrofonanalyse ohne Lampen gestartet." :
            "Music-Rückkanal für " + ips.size() + " gewählte Color4-Lampen wird aufgebaut.");
    }

    /** Fail closed: no DNS, public network, multicast or off-subnet fallback. */
    static boolean validPrivateLanIp(String value) {
        String[] fields = value.split("\\.", -1);
        if (fields.length != 4) return false;
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!fields[i].matches("[0-9]{1,3}") || (fields[i].length() > 1 && fields[i].charAt(0) == '0')) return false;
            try { v[i] = Integer.parseInt(fields[i]); } catch (NumberFormatException error) { return false; }
            if (v[i] > 255) return false;
        }
        return ((v[0] == 10) || (v[0] == 172 && v[1] >= 16 && v[1] <= 31) ||
            (v[0] == 192 && v[1] == 168)) && v[3] >= 1 && v[3] <= 254;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startMicService();
            else status.setText("Mikrofonberechtigung fehlt. Keine Steuerung gestartet.");
        }
    }
}
