package io.github.lxsdd.yeelightclassicdj.ui;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import io.github.lxsdd.yeelightclassicdj.runtime.DjRuntime;

/** One foreground owner for music. Never falls back to regular TCP-RGB on failure. */
public final class ClassicDjService extends Service {
    public static final String ACTION_MIC = "io.github.lxsdd.yeelightclassicdj.MIC";
    public static final String ACTION_INTERNAL = "io.github.lxsdd.yeelightclassicdj.INTERNAL";
    public static final String EXTRA_CONSENT_CODE = "io.github.lxsdd.yeelightclassicdj.CONSENT_CODE";
    public static final String EXTRA_CONSENT_DATA = "io.github.lxsdd.yeelightclassicdj.CONSENT_DATA";
    public static final String ACTION_STOP = "io.github.lxsdd.yeelightclassicdj.STOP";
    public static final String ACTION_CONFIG = "io.github.lxsdd.yeelightclassicdj.CONFIG";
    public static final String EXTRA_UI_OWNER = "io.github.lxsdd.yeelightclassicdj.UI_SESSION_OWNER";
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
    private final ClassicUiSessionOwner uiOwner = new ClassicUiSessionOwner();
    private static volatile ClassicDjService liveService;
    private final android.os.Handler cleanupHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean shuttingDown;
    private long currentToken;
    private Thread startupWorker;
    private static volatile String currentState = "Bereit · Mikrofonanalyse ohne Lampenziele";
    private static volatile double lastBpm = 0.0;
    private static volatile float lastLevel = 0f;
    private static volatile boolean activeSession = false;
    private boolean currentInternal;
    private MediaProjection activeProjection;
    private MediaProjection.Callback activeProjectionCallback;
    private final float[] analyzedBands = new float[4];
    private float latestConfidence;
    private long analyzedFrames;
    private long lastFrameElapsedMs = -1L;
    private long lastBeatElapsedMs = -1L;

    /** Exact process-local owner of a real internal capture, never an OEM lamp. */
    static String ownerForInternal() {
        ClassicDjService service = liveService;
        if (service == null) return null;
        synchronized (service.sessionGuard) {
            return activeSession && service.currentInternal &&
                   service.sessionFence.owns(service.currentToken) ?
                service.uiOwner.current() : null;
        }
    }
    private static final ClassicAnalysisStartResult startResults =
        new ClassicAnalysisStartResult();

    /** Only the same owner may observe its failed start, never a global stale error. */
    static String startFailureForOwner(String owner) {
        return startResults.failureFor(owner);
    }

    static boolean isStopping() {
        ClassicDjService current = liveService;
        if (current == null) return false;
        synchronized (current.sessionGuard) { return current.shuttingDown; }
    }

    private static void rejectStart(String owner, String reason) {
        currentState = reason;
        startResults.fail(owner, reason);
    }

    /** Same-process observation only; not a device power-state assertion. */
    public static boolean hasActiveSession() { return activeSession; }

    /** Source-owned data only; stale, paused and foreign sessions see no PCM. */
    static ClassicDjLiveFrame frameForOwner(String owner) {
        ClassicDjService current = liveService;
        if (current == null) return ClassicDjLiveFrame.none();
        synchronized (current.sessionGuard) {
            if (current.shuttingDown || !current.sessionFence.owns(current.currentToken) ||
                    !current.uiOwner.owns(owner))
                return ClassicDjLiveFrame.none();
            long now = SystemClock.elapsedRealtime();
            long frameAge = current.lastFrameElapsedMs < 0 ? Long.MAX_VALUE :
                Math.max(0, now - current.lastFrameElapsedMs);
            long beatAge = current.lastBeatElapsedMs < 0 ? Long.MAX_VALUE :
                Math.max(0, now - current.lastBeatElapsedMs);
            return new ClassicDjLiveFrame(true, current.currentInternal,
                current.analyzedFrames, frameAge, beatAge, lastBpm, lastLevel,
                current.analyzedBands[0], current.analyzedBands[1],
                current.analyzedBands[2], current.analyzedBands[3],
                current.latestConfidence);
        }
    }

    /**
     * The OEM MusicActivity can pause after Android disallows startService.
     * Immediately fence ONLY its own already-running session, then clean up
     * asynchronously. Never use a blind stopService() on an unknown owner.
     */
    static boolean stopOwnedFromUi(String owner) {
        ClassicDjService service = liveService;
        if (service == null) return false;
        synchronized (service.sessionGuard) {
            if (service.shuttingDown || !service.uiOwner.owns(owner) ||
                    !service.sessionFence.owns(service.currentToken)) return false;
            service.stopSession(service.currentToken, "Gestoppt", true);
            return true;
        }
    }

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
            final long stopToken;
            synchronized (sessionGuard) {
                stopToken = !shuttingDown &&
                    uiOwner.owns(intent.getStringExtra(EXTRA_UI_OWNER)) &&
                    sessionFence.owns(currentToken) ? currentToken : 0L;
                if (stopToken == 0L && !shuttingDown &&
                        !sessionFence.owns(currentToken)) stopSelf();
            }
            if (stopToken != 0L) stopSession(stopToken, "Gestoppt", true);
            return START_NOT_STICKY;
        }
        if (ACTION_CONFIG.equals(action)) {
            double gain = intent.getDoubleExtra(EXTRA_GAIN, Double.NaN);
            String scene = intent.getStringExtra(EXTRA_SCENE);
            synchronized (sessionGuard) {
                if (sessionFence.owns(currentToken) && !shuttingDown &&
                    uiOwner.owns(intent.getStringExtra(EXTRA_UI_OWNER)) &&
                    runtime != null && configureRuntime(runtime, intent, gain, scene)) {
                    currentState = "DJ-Einstellung live aktualisiert";
                }
            }
            return START_NOT_STICKY;
        }
        final boolean internal = ACTION_INTERNAL.equals(action);
        if (!ACTION_MIC.equals(action) && !internal) return START_NOT_STICKY;
        // Reject duplicate or stale UI starts BEFORE their invalid settings
        // can call stopSelf() on an already-owned running DJ session.
        synchronized (sessionGuard) {
            if (shuttingDown || sessionFence.owns(currentToken))
                return START_NOT_STICKY;
        }
        final String requestedOwner = intent.getStringExtra(EXTRA_UI_OWNER);
        if (!ClassicUiSessionOwner.valid(requestedOwner)) {
            currentState = "Ungültige DJ-Sitzungskennung · kein Start";
            stopSelf();
            return START_NOT_STICKY;
        }
        startResults.begin(requestedOwner);
        if (internal && Build.VERSION.SDK_INT < 29) {
            rejectStart(requestedOwner, "Internes Audio benötigt Android 10 oder neuer");
            stopSelf();
            return START_NOT_STICKY;
        }
        final double requestedGain = intent.getDoubleExtra(EXTRA_GAIN, 1.0);
        final String requestedScene = intent.getStringExtra(EXTRA_SCENE) == null ?
            "SPECTRUM" : intent.getStringExtra(EXTRA_SCENE);
        if (!Double.isFinite(requestedGain) || requestedGain < 0.25 ||
            requestedGain > 4.0 ||
            !java.util.Arrays.asList("SPECTRUM", "AMBIENT", "BEAT", "PULSE").contains(requestedScene)) {
            rejectStart(requestedOwner, "Ungültige DJ-Live-Einstellung");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            rejectStart(requestedOwner, "Mikrofonberechtigung fehlt");
            stopSelf();
            return START_NOT_STICKY;
        }
        final String[] selectedIps = intent.getStringArrayExtra(ClassicDjActivity.EXTRA_SELECTED_IPS);
        final java.util.List<String> ips = selectedIps == null ? java.util.Collections.emptyList()
            : java.util.Arrays.asList(selectedIps);
        // No OEM device/room identity or user selection is verified by this
        // bridge. Raw IP extras, including those from legacy screens, must
        // never authorize a reverse Music session or a lamp power command.
        // This remains zero-target until a separately qualified OEM adapter
        // provides a generation-fenced participant authorization.
        if (!ips.isEmpty()) {
            rejectStart(requestedOwner, "OEM-Lampenauswahl nicht verifiziert · keine Lampenbefehle");
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
                rejectStart(requestedOwner, "Runtime fehlt · keine Lampensteuerung");
                stopSelf();
                return START_NOT_STICKY;
            }
            // Stage the user's settings BEFORE any microphone or lamp socket.
            boolean configured;
            try {
                configured = configureRuntime(session, intent, requestedGain, requestedScene);
            } catch (RuntimeException | LinkageError failure) {
                // A binary-incompatible host library must not crash the OEM Music UI.
                // Fail closed and report only a source-owned status, not OEM internals.
                Log.w("ClassicDJ", "Checkpoint configuration failed; no lamp commands");
                configured = false;
            }
            if (!configured) {
                rejectStart(requestedOwner, "DJ-Einstellungen nicht unterstützt");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!uiOwner.begin(requestedOwner)) return START_NOT_STICKY;
            token = sessionFence.begin();
            if (token == 0L) {
                uiOwner.clear();
                return START_NOT_STICKY;
            }
            currentToken = token;
            currentInternal = internal;
            runtime = session;
            activeSession = true;
            analyzedFrames = 0L;
            lastFrameElapsedMs = -1L;
            lastBeatElapsedMs = -1L;
            java.util.Arrays.fill(analyzedBands, 0f);
            latestConfidence = 0f;
            lastBpm = 0.0;
            lastLevel = 0f;
            liveService = this;
            currentState = internal ? "Internes Android-Audio wird vorbereitet" :
                "Mikrofon-Analyse wird vorbereitet";
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Loft DJ", NotificationManager.IMPORTANCE_LOW));
            }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(),
                    internal ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION :
                               ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
            final MediaProjection mediaProjection;
            if (internal) {
                @SuppressWarnings("deprecation")
                Intent data = Build.VERSION.SDK_INT >= 33 ?
                    intent.getParcelableExtra(EXTRA_CONSENT_DATA, Intent.class) :
                    (Intent) intent.getParcelableExtra(EXTRA_CONSENT_DATA);
                if (data == null ||
                    intent.getIntExtra(EXTRA_CONSENT_CODE, Integer.MIN_VALUE)
                        != android.app.Activity.RESULT_OK) {
                    throw new SecurityException("Android playback capture consent missing");
                }
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                if (manager == null) throw new SecurityException("MediaProjection unavailable");
                mediaProjection = manager.getMediaProjection(
                    android.app.Activity.RESULT_OK, data);
                if (mediaProjection == null)
                    throw new SecurityException("Android rejected media projection");
                activeProjection = mediaProjection;
                final long ownedToken = token;
                MediaProjection.Callback revoked = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        stopSession(ownedToken, "Android-Audiofreigabe beendet", true);
                    }
                };
                activeProjectionCallback = revoked;
                mediaProjection.registerCallback(revoked,
                    new android.os.Handler(android.os.Looper.getMainLooper()));
            } else {
                mediaProjection = null;
            }
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
                @Override public void onSpectrum(float bass, float mid,
                        float presence, float treble, boolean beat, float confidence) {
                    synchronized (sessionGuard) {
                        if (sessionFence.owns(token)) {
                            acceptSpectrum(bass, mid, presence, treble, beat, confidence);
                        }
                    }
                }
                @Override public void onError(String error) {
                    Log.w("ClassicDJ", "Capture failure; session halted");
                    stopSession(token, internal ?
                        "DJ-Start fehlgeschlagen · internes Audio [AUDIO_READ]" :
                        "Mikrofonfehler · keine Lampen betroffen", true);
                }
            };
            Thread thread = new Thread(() -> {
                try {
                    if (internal) session.startInternal(this, mediaProjection, ips, callback);
                    else session.start(this, "microphone", ips, callback);
                } catch (Throwable error) {
                    if (sessionFence.owns(token)) {
                        Log.w("ClassicDJ", "Music capture start rejected; no device command");
                        stopSession(token, classifyStartError(internal, error), true);
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
            Log.w("ClassicDJ", "Session start rejected; stopping safely");
            stopSession(token, classifyStartError(internal, error), true);
        }
        return START_NOT_STICKY;
    }

    /** Report a stable, non-sensitive reason instead of raw OEM/Android logs. */
    private static String classifyStartError(boolean internal, Throwable failure) {
        if (!internal) return "DJ-Start fehlgeschlagen · Mikrofon [MIC_START]";
        if (failure instanceof SecurityException)
            return "DJ-Start fehlgeschlagen · Audioberechtigung [AUDIO_PERMISSION]";
        if (failure instanceof LinkageError)
            return "DJ-Start fehlgeschlagen · Audio-Laufzeit [AUDIO_ABI]";
        if (failure instanceof IllegalStateException)
            return "DJ-Start fehlgeschlagen · Audioeingang nicht bereit [AUDIO_STATE]";
        if (failure instanceof IllegalArgumentException)
            return "DJ-Start fehlgeschlagen · Audioformat [AUDIO_FORMAT]";
        return "DJ-Start fehlgeschlagen · internes Audio [AUDIO_START]";
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
        // Only a previously resumed OEM MusicActivity may receive the tap.
        // Missing first-run/login host: no tap destination, never a sidecar.
        Intent reopen = ClassicDjEntryProvider.verifiedMusicReturnIntent(this);
        PendingIntent open = null;
        if (reopen != null) {
            reopen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open = PendingIntent.getActivity(this, 32, reopen, flags);
        }
        Intent stop = new Intent(this, ClassicDjService.class);
        stop.setAction(ACTION_STOP);
        // The notification STOP belongs only to this exact service generation.
        stop.putExtra(EXTRA_UI_OWNER, uiOwner.current());
        PendingIntent stopPi = PendingIntent.getService(this, 33, stop, flags);
        Notification.Builder n = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        n.setContentTitle("Loft DJ · Classic")
            .setContentText(open == null
                ? "DJ läuft · Classic-Musik derzeit nicht verfügbar"
                : "DJ läuft · Zurück zu Classic-Musik")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stopp", stopPi);
        if (open != null) n.setContentIntent(open);
        return n.build();
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
            String completedOwner = uiOwner.current();
            if (finalState.startsWith("DJ-Start fehlgeschlagen") ||
                    finalState.startsWith("Mikrofonfehler") ||
                    finalState.startsWith("Android-Audiofreigabe")) {
                startResults.fail(completedOwner, finalState);
            } else {
                startResults.clear(completedOwner);
            }
            uiOwner.clear(); // revoke all stale panel/notification actions
            currentToken = 0L;
            activeSession = false;
            currentInternal = false;
            instance = runtime;
            runtime = null;
            startupWorker = null;
            lastBpm = 0.0;
            lastLevel = 0f;
            analyzedFrames = 0L;
            lastFrameElapsedMs = -1L;
            lastBeatElapsedMs = -1L;
            latestConfidence = 0f;
            java.util.Arrays.fill(analyzedBands, 0f);
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
        boolean clean = true;
        try { instance.stop(); }
        catch (Exception | LinkageError error) {
            Log.e("ClassicDJ", "Owned Music cleanup unconfirmed; manual check required", error);
            clean = false;
        } finally {
            // A projection obtained after foreground start is single-use.
            // Release even if this session failed before a PCM input was made.
            MediaProjection projection = activeProjection;
            MediaProjection.Callback callback = activeProjectionCallback;
            activeProjection = null;
            activeProjectionCallback = null;
            if (projection != null) {
                if (callback != null) {
                    try { projection.unregisterCallback(callback); }
                    catch (RuntimeException ignored) { clean = false; }
                }
                try { projection.stop(); }
                catch (RuntimeException ignored) { clean = false; }
            }
        }
        return clean;
    }

    private void finishSessionStop(String finalState, boolean clean, boolean requestStopSelf) {
        currentState = clean ? finalState : "Stopp nicht bestätigt · Mikrofonstatus prüfen";
        stopForeground(true);
        if (requestStopSelf) stopSelf();
    }

    @Override public void onDestroy() {
        stopSession(0L, "Gestoppt", false);
        if (liveService == this) liveService = null;
        super.onDestroy();
    }

    // Only called while holding sessionGuard for the confirmed current session.
    private void acceptState(String value) {
        if (value == null) return;
        if ("MIC_ANALYSIS_ONLY_NO_LAMPS".equals(value)) currentState =
            currentInternal ? "Internes Android-Audio · keine Lampensteuerung" :
                "Mikrofonanalyse · keine Lampensteuerung";
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

    private void acceptSpectrum(float bass, float mid, float presence,
                                float treble, boolean beat, float confidence) {
        if (!Float.isFinite(bass) || !Float.isFinite(mid) ||
                !Float.isFinite(presence) || !Float.isFinite(treble) ||
                !Float.isFinite(confidence)) return;
        analyzedBands[0] = Math.max(0f, Math.min(bass, 1f));
        analyzedBands[1] = Math.max(0f, Math.min(mid, 1f));
        analyzedBands[2] = Math.max(0f, Math.min(presence, 1f));
        analyzedBands[3] = Math.max(0f, Math.min(treble, 1f));
        latestConfidence = Math.max(0f, Math.min(confidence, 1f));
        lastFrameElapsedMs = SystemClock.elapsedRealtime();
        if (analyzedFrames < Long.MAX_VALUE) analyzedFrames++;
        if (beat) lastBeatElapsedMs = lastFrameElapsedMs;
    }
}
