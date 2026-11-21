package io.github.lxsdd.yeelightclassicdj.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.Manifest;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transient system-consent broker within the original Classic Music flow.
 * It never renders a replacement Music UI, stores tokens or touches lamps.
 */
public final class ClassicInternalAudioConsentActivity extends Activity {
    private static final int CONSENT_REQUEST = 0x619;
    public static final String EXTRA_OWNER = ClassicDjService.EXTRA_UI_OWNER;
    private static final AtomicReference<Result> RESULT = new AtomicReference<>();
    private String owner;

    static final class Result {
        final String owner;
        final boolean started;
        Result(String owner, boolean started) { this.owner = owner; this.started = started; }
    }

    static Result takeResult() { return RESULT.getAndSet(null); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        owner = getIntent().getStringExtra(EXTRA_OWNER);
        if (!"io.lxsdd.yeelightdj".equals(getPackageName()) ||
                !ClassicUiSessionOwner.valid(owner) ||
                Build.VERSION.SDK_INT < 29 ||
                (Build.VERSION.SDK_INT >= 23 &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED) ||
                state != null) {
            reject();
            return;
        }
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            if (manager == null) { reject(); return; }
            startActivityForResult(manager.createScreenCaptureIntent(), CONSENT_REQUEST);
        } catch (RuntimeException blocked) {
            reject();
        }
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request != CONSENT_REQUEST) { reject(); return; }
        if (resultCode != RESULT_OK || data == null) { reject(); return; }
        Intent source = getIntent();
        Intent start = new Intent(this, ClassicDjService.class);
        start.setAction(ClassicDjService.ACTION_INTERNAL);
        start.putExtra(ClassicDjService.EXTRA_UI_OWNER, owner);
        start.putExtra(ClassicDjActivity.EXTRA_SELECTED_IPS, new String[0]);
        start.putExtra(ClassicDjService.EXTRA_CONSENT_CODE, resultCode);
        start.putExtra(ClassicDjService.EXTRA_CONSENT_DATA, data);
        start.putExtra(ClassicDjService.EXTRA_GAIN,
            source.getDoubleExtra(ClassicDjService.EXTRA_GAIN, 1.0));
        start.putExtra(ClassicDjService.EXTRA_SCENE,
            source.getStringExtra(ClassicDjService.EXTRA_SCENE));
        start.putExtra(ClassicDjService.EXTRA_PULSE_MODE,
            source.getStringExtra(ClassicDjService.EXTRA_PULSE_MODE));
        for (String key : new String[]{
                ClassicDjService.EXTRA_PULSE_BPM, ClassicDjService.EXTRA_PULSE_MIN,
                ClassicDjService.EXTRA_PULSE_MAX, ClassicDjService.EXTRA_PULSE_ATTACK,
                ClassicDjService.EXTRA_PULSE_DECAY, ClassicDjService.EXTRA_PULSE_GAMMA}) {
            start.putExtra(key, source.getDoubleExtra(key, 0.0));
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
            else startService(start);
            RESULT.set(new Result(owner, true));
        } catch (RuntimeException blocked) {
            reject();
            return;
        }
        finish();
    }

    private void reject() {
        if (owner != null) RESULT.set(new Result(owner, false));
        finish();
    }
}
