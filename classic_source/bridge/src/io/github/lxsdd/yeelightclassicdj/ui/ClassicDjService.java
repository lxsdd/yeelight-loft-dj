package io.github.lxsdd.yeelightclassicdj.ui;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import io.github.lxsdd.yeelightclassicdj.runtime.DjRuntime;

/** One foreground owner for music. Never falls back to regular TCP-RGB on failure. */
public final class ClassicDjService extends Service {
    public static final String ACTION_MIC = "io.github.lxsdd.yeelightclassicdj.MIC";
    public static final String ACTION_STOP = "io.github.lxsdd.yeelightclassicdj.STOP";
    public static final String ACTION_CONFIG = "io.github.lxsdd.yeelightclassicdj.CONFIG";
    public static final String EXTRA_GAIN = "io.github.lxsdd.yeelightclassicdj.LIVE_GAIN";
    public static final String EXTRA_SCENE = "io.github.lxsdd.yeelightclassicdj.LIVE_SCENE";
    public static final String EXTRA_PULSE_MODE = "io.github.lxsdd.yeelightclassicdj.PULSE_MODE";
    public static final String EXTRA_PULSE_COLOR = "io.github.lxsdd.yeelightclassicdj.PULSE_COLOR";
    public static final String EXTRA_PULSE_BPM = "io.github.lxsdd.yeelightclassicdj.PULSE_BPM";
    public static final String EXTRA_PULSE_MIN = "io.github.lxsdd.yeelightclassicdj.PULSE_MIN";
    public static final String EXTRA_PULSE_MAX = "io.github.lxsdd.yeelightclassicdj.PULSE_MAX";
    public static final String EXTRA_PULSE_ATTACK = "io.github.lxsdd.yeelightclassicdj.PULSE_ATTACK";
    public static final String EXTRA_PULSE_DECAY = "io.github.lxsdd.yeelightclassicdj.PULSE_DECAY";
    public static final String EXTRA_PULSE_GAMMA = "io.github.lxsdd.yeelightclassicdj.PULSE_GAMMA";
    private static final String CHANNEL = "classic-loft-dj";
    private static final int NOTIFICATION_ID = 4812;
    private DjRuntime runtime;
    private final Object sessionGuard = new Object();
    private final ClassicSessionFence sessionFence = new ClassicSessionFence();
    private final android.os.Handler cleanupHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean shuttingDown;
    private long currentToken;
    private Thread startupWorker;
    private static volatile String currentState = "Bereit · ausgewählte Color4-Lampen";
    private static volatile double lastBpm = 0.0;
    private static volatile float lastLevel = 0f;
    private static volatile boolean activeSession = false;

    /** Same-process observation only; not a device power-state assertion. */
    public static boolean hasActiveSession() { return activeSession; }

    public static String uiStatus() {
        String bpm = lastBpm > 0.0 ? String.format(java.util.Locale.ROOT, "%.0f BPM", lastBpm) : "BPM wird analysiert";
        return currentState + " · " + bpm + " · Pegel " + Math.round(lastLevel * 100.0f) + "%";
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private DjRuntime obtainExistingCheckpoint() {
        // The private Kotlin checkpoint implements this adapter once compiled into sidecar dex.
        // Missing/changed implementation is NOT silently replaced by speculative LAN commands.
        try {
            Class<?> klass = Class.forName("io.github.lxsdd.yeelightclassicdj.runtime.CheckpointRuntime");
            return (DjRuntime) klass.getDeclaredConstructor().newInstance();
        } catch (Exception | LinkageError error) {
            Log.w("ClassicDJ", "Private checkpoint runtime not available");
            return null;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession(0L, "Gestoppt", true);
            return START_NOT_STICKY;
        }
        if (ACTION_CONFIG.equals(action)) {
            double gain = intent.getDoubleExtra(EXTRA_GAIN, Double.NaN);
            String scene = intent.getStringExtra(EXTRA_SCENE);
            synchronized (sessionGuard) {
                if (sessionFence.owns(currentToken) && !shuttingDown &&
                    runtime != null && configureRuntime(runtime, intent, gain, scene)) {
                    currentState = "DJ-Einstellung live aktualisiert";
                }
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_MIC.equals(action)) return START_NOT_STICKY;
        final double requestedGain = intent.getDoubleExtra(EXTRA_GAIN, 1.0);
        final String requestedScene = intent.getStringExtra(EXTRA_SCENE) == null ?
            "SPECTRUM" : intent.getStringExtra(EXTRA_SCENE);
        if (!Double.isFinite(requestedGain) || requestedGain < 0.25 ||
            requestedGain > 4.0 ||
            !java.util.Arrays.asList("SPECTRUM", "AMBIENT", "BEAT", "PULSE").contains(requestedScene)) {
            currentState = "Ungültige DJ-Live-Einstellung";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            currentState = "Mikrofonberechtigung fehlt";
            stopSelf();
            return START_NOT_STICKY;
        }
        final String[] selectedIps = intent.getStringArrayExtra(ClassicDjActivity.EXTRA_SELECTED_IPS);
        final java.util.List<String> ips = selectedIps == null ? java.util.Collections.emptyList()
            : java.util.Arrays.asList(selectedIps);
        if (ips.size() > 4 || new java.util.HashSet<>(ips).size() != ips.size()) {
            currentState = "Ungültige Lampenauswahl";
            stopSelf();
            return START_NOT_STICKY;
        }
        final DjRuntime session;
        final long token;
        synchronized (sessionGuard) {
            if (shuttingDown) return START_NOT_STICKY;
            if (sessionFence.owns(sessionFenceCurrent())) return START_NOT_STICKY;
            session = obtainExistingCheckpoint();
            if (session == null) {
                currentState = "Runtime fehlt · keine Lampensteuerung";
                stopSelf();
                return START_NOT_STICKY;
            }
            // Stage the user's settings BEFORE any microphone or lamp socket.
            if (!configureRuntime(session, intent, requestedGain, requestedScene)) {
                currentState = "DJ-Einstellungen nicht unterstützt";
                stopSelf();
                return START_NOT_STICKY;
            }
            token = sessionFence.begin();
            if (token == 0L) return START_NOT_STICKY;
            currentToken = token;
            runtime = session;
            activeSession = true;
            currentState = "Mikrofon-Analyse wird vorbereitet";
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Loft DJ", NotificationManager.IMPORTANCE_LOW));
            }
            startForeground(NOTIFICATION_ID, notification());
            final DjRuntime.Listener callback = new DjRuntime.Listener() {
                @Override public void onState(String value) {
                    synchronized (sessionGuard) {
                        if (sessionFence.owns(token)) acceptState(value);
                    }
                }
                @Override public void onBeat(double bpm, float level) {
                    synchronized (sessionGuard) {
                        if (sessionFence.owns(token)) acceptBeat(bpm, level);
                    }
                }
                @Override public void onError(String error) {
                    Log.w("ClassicDJ", "Capture failure; session halted");
                    stopSession(token, "Mikrofonfehler · bitte Lampenstatus prüfen", true);
                }
            };
            Thread thread = new Thread(() -> {
                try {
                    session.start(this, "microphone", ips, callback);
                } catch (Throwable error) {
                    if (sessionFence.owns(token)) {
                        Log.e("ClassicDJ", "Music handshake or microphone start failed", error);
                        stopSession(token, "DJ-Start fehlgeschlagen; Lampenstatus prüfen", true);
                    }
                }
            }, "Classic-DJ-Session-Start");
            thread.setDaemon(true);
            synchronized (sessionGuard) {
                if (sessionFence.owns(token) && !shuttingDown) {
                    startupWorker = thread;
                    thread.start();
                }
            }
        } catch (Exception | LinkageError error) {
            Log.e("ClassicDJ", "Session start failed; stopping safely", error);
            stopSession(token, "DJ-Start fehlgeschlagen; Lampenstatus prüfen", true);
        }
        return START_NOT_STICKY;
    }

    /**
     * Configuration changes only the owned DSP, never a Music socket.
     * On first MIC start this runs BEFORE microphone or any device command.
     */
    private static boolean configureRuntime(DjRuntime target, Intent intent,
                                            double gain, String scene) {
        if (target == null || intent == null || scene == null ||
                !Double.isFinite(gain) || gain < 0.25 || gain > 4.0 ||
                !java.util.Arrays.asList("SPECTRUM", "AMBIENT", "BEAT", "PULSE").contains(scene))
            return false;
        String mode = intent.getStringExtra(EXTRA_PULSE_MODE);
        if (mode == null) mode = "BEAT";
        if (!"BEAT".equals(mode) && !"TIMER".equals(mode)) return false;
        int rgb = pulseInt(intent, EXTRA_PULSE_COLOR, 0x25CBE8);
        int bpm = pulseInt(intent, EXTRA_PULSE_BPM, 120);
        int minimum = pulseInt(intent, EXTRA_PULSE_MIN, 12);
        int maximum = pulseInt(intent, EXTRA_PULSE_MAX, 90);
        int attack = pulseInt(intent, EXTRA_PULSE_ATTACK, 90);
        int decay = pulseInt(intent, EXTRA_PULSE_DECAY, 600);
        double gamma = intent.getDoubleExtra(EXTRA_PULSE_GAMMA, 2.0);
        if (!target.configurePulse(rgb, "BEAT".equals(mode), bpm, minimum,
                                    maximum, attack, decay, gamma)) return false;
        return target.configure(gain, scene);
    }

    private static int pulseInt(Intent intent, String key, int fallback) {
        double value = intent.getDoubleExtra(key, fallback);
        if (!Double.isFinite(value) || value < 0 || value > Integer.MAX_VALUE ||
                value != Math.rint(value)) return -1;
        return (int) value;
    }

    // Caller owns sessionGuard. A token of zero must never be treated as live.
    private long sessionFenceCurrent() {
        return currentToken;
    }

    private Notification notification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent reopen = new Intent(this, ClassicDjActivity.class);
        reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 32, reopen, flags);
        Intent stop = new Intent(this, ClassicDjService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 33, stop, flags);
        Notification.Builder n = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return n.setContentTitle("Loft DJ · Classic")
            .setContentText("DJ läuft · Tippen öffnet Classic")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stopp", stopPi)
            .build();
    }

    /**
     * Fence audio callbacks synchronously, then release owned Music sessions
     * off the UI thread for a user-initiated Stop. A Music STOP ACK and the
     * post-stop confirmation still run per owned lamp: no blind fire-and-forget
     * and no querying/saving the previous color or power state here.
     *
     * Android may destroy this Service independently of a user action. That
     * destruction path retains synchronous cleanup to avoid abandoning an
     * owned reverse socket as the process stops.
     */
    private void stopSession(long expectedToken, String finalState, boolean requestStopSelf) {
        final DjRuntime instance;
        synchronized (sessionGuard) {
            if (shuttingDown || (expectedToken != 0L && !sessionFence.owns(expectedToken))) return;
            shuttingDown = true;
            sessionFence.cancel(); // fence callbacks before touching any session/socket
            currentToken = 0L;
            activeSession = false;
            instance = runtime;
            runtime = null;
            startupWorker = null;
            lastBpm = 0.0;
            lastLevel = 0f;
            currentState = instance == null ? finalState : "DJ wird beendet · Verbindungen schließen";
        }
        if (instance == null) {
            finishSessionStop(finalState, true, requestStopSelf);
            return;
        }
        if (!requestStopSelf) {
            // Android lifecycle destruction: complete the owned cleanup before
            // leaving onDestroy; no background work is assumed to outlive us.
            finishSessionStop(finalState, closeOwnedRuntime(instance), false);
            return;
        }
        Thread cleanup = new Thread(() -> {
            final boolean clean = closeOwnedRuntime(instance);
            cleanupHandler.post(() -> finishSessionStop(finalState, clean, true));
        }, "Classic-DJ-Owned-Music-Cleanup");
        // A foreground service remains alive until finishSessionStop calls
        // stopSelf. The UI remains responsive while per-device ACKs complete.
        cleanup.setDaemon(true);
        try { cleanup.start(); }
        catch (RuntimeException unableToStartThread) {
            // Do not orphan an owned session if a worker cannot be created.
            finishSessionStop(finalState, closeOwnedRuntime(instance), true);
        }
    }

    private boolean closeOwnedRuntime(DjRuntime instance) {
        try { instance.stop(); return true; }
        catch (Exception | LinkageError error) {
            Log.e("ClassicDJ", "Owned Music cleanup unconfirmed; manual check required", error);
            return false;
        }
    }

    private void finishSessionStop(String finalState, boolean clean, boolean requestStopSelf) {
        currentState = clean ? finalState : "Stopp nicht bestätigt · Lampenstatus prüfen";
        stopForeground(true);
        if (requestStopSelf) stopSelf();
    }

    @Override public void onDestroy() {
        stopSession(0L, "Gestoppt", false);
        super.onDestroy();
    }

    // Only called while holding sessionGuard for the confirmed current session.
    private void acceptState(String value) {
        if (value == null) return;
        if ("MIC_ANALYSIS_ONLY_NO_LAMPS".equals(value)) currentState = "Mikrofonanalyse · keine Lampensteuerung";
        else if (value.startsWith("DJ_ACTIVE_")) currentState = "Music-Rückkanal aktiv · " +
            value.substring(10).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        else if ("STOPPED".equals(value)) currentState = "Gestoppt";
        Log.i("ClassicDJ", "Session state updated");
    }

    private void acceptBeat(double bpm, float level) {
        lastBpm = (!Double.isNaN(bpm) && !Double.isInfinite(bpm)) && bpm > 0 ? bpm : 0.0;
        lastLevel = (!Float.isNaN(level) && !Float.isInfinite(level))
            ? Math.max(0f, Math.min(level, 1f)) : 0f;
    }
}
