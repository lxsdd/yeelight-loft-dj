package io.github.lxsdd.yeelightclassicdj.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Clone-only, on-device first-launch Nearby permission gate.
 * No auto-grant, Bluetooth scan, LAN command or OEM source change.
 */
public final class ClassicNearbyGateActivity extends Activity {
    private static final String META =
        "io.github.lxsdd.yeelightclassicdj.OEM_LAUNCHER";
    private static final int REQUEST = 0x49A;
    private boolean launched;
    private boolean requested;
    private String originalTarget;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!"io.lxsdd.yeelightdj".equals(getPackageName())) { finish(); return; }
        try {
            ActivityInfo info = getPackageManager().getActivityInfo(
                getComponentName(), PackageManager.GET_META_DATA);
            originalTarget = info.metaData == null ? null : info.metaData.getString(META);
        } catch (PackageManager.NameNotFoundException missing) {
            originalTarget = null;
        }
        if (originalTarget == null ||
                !originalTarget.startsWith("com.yeelight.cherry.") ||
                originalTarget.contains("/") || originalTarget.contains("$") ||
                originalTarget.length() > 180) {
            showGate("Originaler Classic-Startweg nicht verifiziert", false);
            return;
        }
        if (nearbyGranted()) openOriginal();
        else {
            showGate("Classic benötigt Zugriff auf Geräte in der Nähe.", true);
            requestNearbyOnce();
        }
    }

    private boolean nearbyGranted() {
        if (Build.VERSION.SDK_INT < 31) return true;
        boolean bluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED;
        return bluetooth && (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED);
    }

    private void requestNearbyOnce() {
        if (requested || Build.VERSION.SDK_INT < 31) return;
        requested = true;
        try {
            String[] permissions = Build.VERSION.SDK_INT >= 33 ?
                new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.NEARBY_WIFI_DEVICES} :
                new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
            requestPermissions(permissions, REQUEST);
        } catch (RuntimeException blocked) {
            showGate("Android konnte die Berechtigung nicht anfordern.", true);
        }
    }

    private void showGate(String message, boolean retryAllowed) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20f * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        TextView messageView = new TextView(this);
        messageView.setText(message);
        content.addView(messageView);
        if (retryAllowed) {
            Button retry = new Button(this);
            retry.setText("Zugriff erlauben");
            retry.setOnClickListener(v -> {
                if (nearbyGranted()) openOriginal();
                else { requested = false; requestNearbyOnce(); }
            });
            content.addView(retry);
        }
        setContentView(content);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST) return;
        if (nearbyGranted()) openOriginal();
        else showGate("Gerätezugriff abgelehnt. Classic bleibt geschlossen.", true);
    }

    private void openOriginal() {
        if (launched || !nearbyGranted() || originalTarget == null) return;
        launched = true;
        try {
            Intent original = new Intent(Intent.ACTION_MAIN);
            original.addCategory(Intent.CATEGORY_LAUNCHER);
            original.setClassName(getPackageName(), originalTarget);
            startActivity(original);
            finish();
        } catch (RuntimeException blocked) {
            launched = false;
            showGate("Der originale Classic-Einstieg ist nicht erreichbar.", false);
        }
    }
}
