package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.content.ClipData;
import android.os.Build;
import android.view.DragEvent;
import java.util.List;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.WeakHashMap;

/**
 * One on-page extension to the ORIGINAL SceneFragment, not another Activity
 * or a duplicate scene database. The private, SHA-pinned Classic clone
 * invokes attach/detach at the original Fragment lifecycle; no reflection.
 * A panel is inserted only when the existing layout can safely give up
 * viewport space. Missing OEM layout proof => leave everything untouched.
 */
public final class ClassicSceneGalleryPanel extends LinearLayout {
    private static final String CLONE_PACKAGE = "io.lxsdd.yeelightdj";
    private static final String FRAGMENT =
        "com.yeelight.cherry.ui.fragment.SceneFragment";
    private static final WeakHashMap<Object, Lease> owned = new WeakHashMap<>();
    private final int collapsedPx, detailPx;
    private final Button header;
    private final ScrollView details;
    private final Button[] cards = new Button[ClassicSceneUiState.SCENES.length];
    // Stable IDs belong to seven DJ effects, never the opaque OEM scene list.
    private final ClassicEffectOrder effectOrder;
    private final LinearLayout[] sceneRows = new LinearLayout[4];
    private Button dragSource;
    private final Button moveUp, moveDown, undoOrder;
    private final Button speed, direction, trail, brightness;
    private final LinearLayout spatialEditor, pulseEditor;
    private final Button pulseMode, pulseTempo, pulseShape;
    private final TextView note;
    private static final String[] EXPLANATIONS = {
        "Frequenzbänder aus der Audioanalyse",
        "Sanfter Verlauf passend zum Musikpegel",
        "Helligkeitsimpulse auf erkannte Beats",
        "Puls nach Beat oder frei gewähltem Tempo",
        "Lichtpunkt wandert in geordneter Richtung",
        "Kurzer Lichtschweif mit abklingender Spur",
        "Lichtwelle durch die geordnete Auswahl"
    };
    private Lease lease;

    private static final class Lease {
        final FrameLayout root;
        final View original;
        final ClassicSceneGalleryPanel panel;
        final int originalBottom;
        int reserved;
        Lease(FrameLayout root, View original, ClassicSceneGalleryPanel panel,
              int originalBottom) {
            this.root = root;
            this.original = original;
            this.panel = panel;
            this.originalBottom = originalBottom;
        }
        void resize(int height) {
            if (original.getParent() != root || panel.getParent() != root ||
                !(original.getLayoutParams() instanceof FrameLayout.LayoutParams) ||
                height < 0 || height > root.getResources().getDisplayMetrics().heightPixels * .65f)
                return;
            FrameLayout.LayoutParams p =
                (FrameLayout.LayoutParams) original.getLayoutParams();
            if (p.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                p.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                p.bottomMargin != originalBottom + reserved) return;
            p.bottomMargin = originalBottom + height;
            original.setLayoutParams(p);
            reserved = height;
        }
        void detach() {
            panel.lease = null;
            if (original.getParent() == root &&
                original.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams p =
                    (FrameLayout.LayoutParams) original.getLayoutParams();
                if (p.bottomMargin == originalBottom + reserved) {
                    p.bottomMargin = originalBottom;
                    original.setLayoutParams(p);
                }
            }
            if (panel.getParent() == root) root.removeView(panel);
        }
    }

    public static void attach(Object owner, View sceneRoot) {
        if (owner == null || sceneRoot == null ||
            !FRAGMENT.equals(owner.getClass().getName()) ||
            !CLONE_PACKAGE.equals(sceneRoot.getContext().getPackageName()) ||
            !(sceneRoot instanceof FrameLayout)) return;
        try {
            synchronized (owned) {
                Lease previous = owned.get(owner);
                if (previous != null) {
                    if (previous.panel.getParent() == previous.root &&
                        previous.original.getParent() == previous.root) {
                        // Fragment revisits keep one gallery; reflect edits
                        // made in the original MusicActivity immediately.
                        previous.panel.redraw();
                        return;
                    }
                    previous.detach();
                    owned.remove(owner);
                }
                FrameLayout root = (FrameLayout) sceneRoot;
                if (root.getChildCount() != 1) return;
                View original = root.getChildAt(0);
                if (original == null ||
                    !(original.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
                FrameLayout.LayoutParams old =
                    (FrameLayout.LayoutParams) original.getLayoutParams();
                if (old.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                    old.height != ViewGroup.LayoutParams.MATCH_PARENT ||
                    old.bottomMargin < 0) return;
                ClassicSceneGalleryPanel panel = new ClassicSceneGalleryPanel(root.getContext());
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
                root.addView(panel, params);
                Lease lease = new Lease(root, original, panel, old.bottomMargin);
                panel.lease = lease;
                owned.put(owner, lease);
                lease.resize(panel.collapsedPx);
            }
        } catch (RuntimeException ignored) {
            // Fail closed: optional controls must never break original scenes.
        } catch (LinkageError ignored) {
            // Older Classic layouts must remain usable with no injected panel.
        }
    }

    public static void detach(Object owner) {
        if (owner == null) return;
        synchronized (owned) {
            Lease lease = owned.remove(owner);
            if (lease != null) lease.detach();
        }
    }

    private ClassicSceneGalleryPanel(Context context) {
        super(context);
        effectOrder = new ClassicEffectOrder(context);
        setOrientation(VERTICAL);
        setTag("io.github.lxsdd.yeelightclassicdj.scenes.inline");
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(6f * density);
        setPadding(pad, pad, pad, pad);
        collapsedPx = Math.round(54f * density) + pad * 2;
        detailPx = Math.min(Math.round(305f * density),
            Math.round(getResources().getDisplayMetrics().heightPixels * .46f));
        header = new Button(context);
        header.setAllCaps(false);
        header.setText("Dynamische Lichtszene");
        header.setContentDescription("Dynamische Szenenkarten in der Classic-Szenengalerie öffnen");
        addView(header, new LinearLayout.LayoutParams(-1, Math.round(54f * density), 0f));
        details = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        details.addView(content);
        details.setVisibility(View.GONE);
        addView(details, new LinearLayout.LayoutParams(-1, detailPx, 0f));
        header.setOnClickListener(v -> {
            details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (lease != null) lease.resize(collapsedPx +
                (details.getVisibility() == View.VISIBLE ? detailPx : 0));
        });
        note = new TextView(context);
        note.setText("In Classic ausgewählte Szene · keine zusätzlichen Lampen freigegeben");
        note.setContentDescription("Vorschau ohne Geräte- oder Szenenschreibzugriff");
        content.addView(note);
        for (int rowIndex = 0; rowIndex < 4; rowIndex++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            sceneRows[rowIndex] = row;
            int first = rowIndex * 2;
            for (int i = first; i < Math.min(first + 2, cards.length); i++) {
                final int index = i;
                Button card = new Button(context);
                card.setAllCaps(false);
                card.setOnClickListener(v -> {
                    ClassicSceneUiState.Selection old = ClassicSceneUiState.snapshot();
                    if (ClassicSceneUiState.update(index, old.speedIndex, old.reverse,
                            old.trailIndex, old.rangeIndex)) onSceneEdited();
                });
                card.setOnLongClickListener(v -> startEffectDrag(card));
                card.setOnDragListener((v, event) -> handleEffectDrop(card, event));
                cards[i] = card;
                row.addView(card, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            content.addView(row);
        }
        // True SceneFragment in-place sort; explicit gesture -> existing
        // clone-only stable-ID persistence. Never edits OEM scene records.
        renderEffectCards();
        LinearLayout orderActions = new LinearLayout(context);
        orderActions.setOrientation(HORIZONTAL);
        moveUp = new Button(context);
        moveUp.setAllCaps(false);
        moveUp.setText("↑");
        moveUp.setContentDescription("Gewählten DJ-Lichteffekt nach oben verschieben");
        moveUp.setOnClickListener(v -> moveSelectedEffect(-1));
        orderActions.addView(moveUp, new LinearLayout.LayoutParams(0, -2, 1f));
        moveDown = new Button(context);
        moveDown.setAllCaps(false);
        moveDown.setText("↓");
        moveDown.setContentDescription("Gewählten DJ-Lichteffekt nach unten verschieben");
        moveDown.setOnClickListener(v -> moveSelectedEffect(1));
        orderActions.addView(moveDown, new LinearLayout.LayoutParams(0, -2, 1f));
        undoOrder = new Button(context);
        undoOrder.setAllCaps(false);
        undoOrder.setText("Sortierung zurück");
        undoOrder.setContentDescription("Letzte eigene DJ-Lichteffekt-Sortierung rückgängig");
        undoOrder.setOnClickListener(v -> {
            if (effectOrder.undo()) {
                renderEffectCards();
                redraw();
            }
        });
        orderActions.addView(undoOrder, new LinearLayout.LayoutParams(0, -2, 2f));
        content.addView(orderActions);
        spatialEditor = new LinearLayout(context);
        spatialEditor.setOrientation(VERTICAL);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        speed = new Button(context);
        speed.setAllCaps(false);
        speed.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, (s.speedIndex + 1) %
                ClassicSceneUiState.SPEEDS.length, s.reverse, s.trailIndex, s.rangeIndex);
            onSceneEdited();
        });
        row.addView(speed, new LinearLayout.LayoutParams(0, -2, 1f));
        direction = new Button(context);
        direction.setAllCaps(false);
        direction.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, s.speedIndex, !s.reverse,
                s.trailIndex, s.rangeIndex);
            onSceneEdited();
        });
        row.addView(direction, new LinearLayout.LayoutParams(0, -2, 1f));
        spatialEditor.addView(row);
        LinearLayout second = new LinearLayout(context);
        second.setOrientation(HORIZONTAL);
        trail = new Button(context);
        trail.setAllCaps(false);
        trail.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, s.speedIndex, s.reverse,
                (s.trailIndex + 1) % ClassicSceneUiState.TRAILS.length, s.rangeIndex);
            onSceneEdited();
        });
        second.addView(trail, new LinearLayout.LayoutParams(0, -2, 1f));
        brightness = new Button(context);
        brightness.setAllCaps(false);
        brightness.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, s.speedIndex, s.reverse,
                s.trailIndex, (s.rangeIndex + 1) % ClassicSceneUiState.RANGES.length);
            onSceneEdited();
        });
        second.addView(brightness, new LinearLayout.LayoutParams(0, -2, 1f));
        spatialEditor.addView(second);
        content.addView(spatialEditor);

        pulseEditor = new LinearLayout(context);
        pulseEditor.setOrientation(VERTICAL);
        LinearLayout pulseRow = new LinearLayout(context);
        pulseRow.setOrientation(HORIZONTAL);
        pulseMode = new Button(context);
        pulseMode.setAllCaps(false);
        pulseMode.setContentDescription("Puls zwischen Beat und Timer umschalten");
        pulseMode.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            if (ClassicSceneUiState.updateAudio(s.gainIndex, !s.beatPulse,
                    s.pulseTempoIndex, s.pulseShapeIndex)) onSceneEdited();
        });
        pulseRow.addView(pulseMode, new LinearLayout.LayoutParams(0, -2, 1f));
        pulseTempo = new Button(context);
        pulseTempo.setAllCaps(false);
        pulseTempo.setContentDescription("Zeitgesteuertes Puls-Tempo einstellen");
        pulseTempo.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            if (ClassicSceneUiState.updateAudio(s.gainIndex, s.beatPulse,
                    (s.pulseTempoIndex + 1) % 5, s.pulseShapeIndex)) onSceneEdited();
        });
        pulseRow.addView(pulseTempo, new LinearLayout.LayoutParams(0, -2, 1f));
        pulseEditor.addView(pulseRow);
        pulseShape = new Button(context);
        pulseShape.setAllCaps(false);
        pulseShape.setContentDescription("Pulsform wechseln");
        pulseShape.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            if (ClassicSceneUiState.updateAudio(s.gainIndex, s.beatPulse,
                    s.pulseTempoIndex, (s.pulseShapeIndex + 1) % 3)) onSceneEdited();
        });
        pulseEditor.addView(pulseShape);
        content.addView(pulseEditor);
        TextView info = new TextView(context);
        info.setText("Szenenparameter gelten auch im originalen Musikbereich. "
            + "Lampen starten erst nach einer separat verifizierten Auswahl.");
        content.addView(info);
        redraw();
    }

    private boolean startEffectDrag(Button card) {
        if (card == null || card.getParent() == null) return false;
        dragSource = card;
        // Empty local payload; exact source View identity proves ownership.
        ClipData data = ClipData.newPlainText("loft-dj-local-effect", "");
        boolean started = Build.VERSION.SDK_INT >= 24
            ? card.startDragAndDrop(data, new View.DragShadowBuilder(card), card, 0)
            : card.startDrag(data, new View.DragShadowBuilder(card), card, 0);
        if (!started) dragSource = null;
        return started;
    }

    private boolean handleEffectDrop(Button target, DragEvent event) {
        if (event == null) return false;
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED)
            return dragSource != null && event.getLocalState() == dragSource;
        if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
            dragSource = null;
            return true;
        }
        if (event.getAction() == DragEvent.ACTION_DROP) {
            if (dragSource == null || event.getLocalState() != dragSource)
                return false;
            int sourceIndex = -1, targetIndex = -1;
            for (int i = 0; i < cards.length; i++) {
                if (cards[i] == dragSource) sourceIndex = i;
                if (cards[i] == target) targetIndex = i;
            }
            if (sourceIndex < 0 || targetIndex < 0) return false;
            int destination = effectOrder.order().indexOf(
                ClassicSceneUiState.SCENES[targetIndex]);
            if (!effectOrder.move(ClassicSceneUiState.SCENES[sourceIndex], destination))
                return false;
            renderEffectCards();
            redraw();
            return true;
        }
        return dragSource != null && event.getLocalState() == dragSource;
    }

    private void moveSelectedEffect(int direction) {
        ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
        if (effectOrder.moveRelative(ClassicSceneUiState.SCENES[s.scene], direction)) {
            renderEffectCards();
            redraw();
        }
    }

    private void renderEffectCards() {
        List<String> order = effectOrder.order();
        if (order.size() != cards.length) return;
        for (LinearLayout row : sceneRows) row.removeAllViews();
        int position = 0;
        for (String id : order) {
            int index = ClassicEffectOrder.sceneIndex(id);
            if (index < 0 || cards[index] == null) return;
            sceneRows[position / 2].addView(cards[index],
                new LinearLayout.LayoutParams(0, -2, 1f));
            position++;
        }
    }

    /**
     * An explicit tap may update an already-owned internal-audio session.
     * There is no OEM participant lookup and NO session/transport start.
     * Every Intent goes through the original single owner-fenced service.
     */
    private void onSceneEdited() {
        redraw();
        String owner = ClassicDjService.ownerForInternal();
        if (owner == null || !ClassicDjService.hasActiveSession()) return;
        if (!CLONE_PACKAGE.equals(getContext().getPackageName())) return;
        ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
        Intent config = new Intent(getContext(), ClassicDjService.class);
        config.setAction(ClassicDjService.ACTION_CONFIG);
        config.putExtra(ClassicDjService.EXTRA_UI_OWNER, owner);
        config.putExtra(ClassicDjService.EXTRA_SCENE,
            ClassicSceneUiState.SCENES[s.scene]);
        double[] gains = {0.5, 1.0, 2.0, 4.0};
        int[] tempos = {60, 90, 120, 150, 180};
        double[] gammas = {2.2, 1.2, 2.0};
        double[][] shapes = {{12, 65, 160, 900}, {8, 100, 70, 330},
                             {12, 90, 90, 600}};
        config.putExtra(ClassicDjService.EXTRA_GAIN, gains[s.gainIndex]);
        config.putExtra(ClassicDjService.EXTRA_PULSE_MODE,
            s.beatPulse ? "BEAT" : "TIMER");
        config.putExtra(ClassicDjService.EXTRA_PULSE_BPM, (double) tempos[s.pulseTempoIndex]);
        config.putExtra(ClassicDjService.EXTRA_PULSE_MIN, shapes[s.pulseShapeIndex][0]);
        config.putExtra(ClassicDjService.EXTRA_PULSE_MAX, shapes[s.pulseShapeIndex][1]);
        config.putExtra(ClassicDjService.EXTRA_PULSE_ATTACK, shapes[s.pulseShapeIndex][2]);
        config.putExtra(ClassicDjService.EXTRA_PULSE_DECAY, shapes[s.pulseShapeIndex][3]);
        config.putExtra(ClassicDjService.EXTRA_PULSE_GAMMA, gammas[s.pulseShapeIndex]);
        config.putExtra(ClassicDjService.EXTRA_SPATIAL_SPEED,
            (double) ClassicSceneUiState.SPEEDS[s.speedIndex]);
        config.putExtra(ClassicDjService.EXTRA_SPATIAL_REVERSE, s.reverse);
        config.putExtra(ClassicDjService.EXTRA_SPATIAL_TRAIL,
            (double) ClassicSceneUiState.TRAILS[s.trailIndex]);
        config.putExtra(ClassicDjService.EXTRA_SPATIAL_MIN,
            (double) ClassicSceneUiState.RANGES[s.rangeIndex][0]);
        config.putExtra(ClassicDjService.EXTRA_SPATIAL_MAX,
            (double) ClassicSceneUiState.RANGES[s.rangeIndex][1]);
        try {
            getContext().startService(config);
        } catch (RuntimeException ignored) {
            note.setText("Szenenwahl gespeichert · laufende Audioanalyse nicht aktualisiert");
        }
    }

    private void redraw() {
        if (effectOrder.refresh()) renderEffectCards();
        ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
        note.setText(ClassicSceneUiState.LABELS[s.scene] + " · " +
            EXPLANATIONS[s.scene] + " · ohne verifizierte Lampenauswahl nur Vorschau");
        List<String> orderedEffects = effectOrder.order();
        int selectedPosition = orderedEffects.indexOf(ClassicSceneUiState.SCENES[s.scene]);
        moveUp.setEnabled(selectedPosition > 0);
        moveDown.setEnabled(selectedPosition >= 0 &&
            selectedPosition < orderedEffects.size() - 1);
        for (int i = 0; i < cards.length; i++) {
            cards[i].setSelected(i == s.scene);
            cards[i].setText((i == s.scene ? "✓ " : "") + ClassicSceneUiState.LABELS[i]);
            cards[i].setContentDescription(ClassicSceneUiState.LABELS[i] +
                (i == s.scene ? ", ausgewählt" : ", Lichtszene wählen"));
        }
        spatialEditor.setVisibility(s.scene >= 4 ? View.VISIBLE : View.GONE);
        pulseEditor.setVisibility(s.scene == 3 ? View.VISIBLE : View.GONE);
        pulseMode.setText(s.beatPulse ? "Puls: Beat" : "Puls: Timer");
        int[] pulseTempos = {60, 90, 120, 150, 180};
        String[] pulseShapes = {"Sanft", "Kräftig", "Normal"};
        pulseTempo.setText(pulseTempos[s.pulseTempoIndex] + " BPM");
        pulseTempo.setEnabled(!s.beatPulse);
        pulseShape.setText("Pulsform: " + pulseShapes[s.pulseShapeIndex]);
        speed.setText("Tempo " + ClassicSceneUiState.SPEEDS[s.speedIndex] + "%");
        direction.setText(s.reverse ? "Rückwärts" : "Vorwärts");
        trail.setText("Schweif " + ClassicSceneUiState.TRAILS[s.trailIndex]);
        brightness.setText("Helligkeit " +
            ClassicSceneUiState.RANGES[s.rangeIndex][0] + "–" +
            ClassicSceneUiState.RANGES[s.rangeIndex][1] + "%");
    }
}
