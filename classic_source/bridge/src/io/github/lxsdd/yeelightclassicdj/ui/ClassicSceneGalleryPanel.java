package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
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
    private final Button speed, direction, trail, brightness;
    private final TextView note;
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
                if (owned.containsKey(owner)) return;
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
            int first = rowIndex * 2;
            for (int i = first; i < Math.min(first + 2, cards.length); i++) {
                final int index = i;
                Button card = new Button(context);
                card.setAllCaps(false);
                card.setOnClickListener(v -> {
                    ClassicSceneUiState.Selection old = ClassicSceneUiState.snapshot();
                    if (ClassicSceneUiState.update(index, old.speedIndex, old.reverse,
                            old.trailIndex, old.rangeIndex)) redraw();
                });
                cards[i] = card;
                row.addView(card, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            content.addView(row);
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        speed = new Button(context);
        speed.setAllCaps(false);
        speed.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, (s.speedIndex + 1) %
                ClassicSceneUiState.SPEEDS.length, s.reverse, s.trailIndex, s.rangeIndex);
            redraw();
        });
        row.addView(speed, new LinearLayout.LayoutParams(0, -2, 1f));
        direction = new Button(context);
        direction.setAllCaps(false);
        direction.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, s.speedIndex, !s.reverse,
                s.trailIndex, s.rangeIndex);
            redraw();
        });
        row.addView(direction, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(row);
        LinearLayout second = new LinearLayout(context);
        second.setOrientation(HORIZONTAL);
        trail = new Button(context);
        trail.setAllCaps(false);
        trail.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, s.speedIndex, s.reverse,
                (s.trailIndex + 1) % ClassicSceneUiState.TRAILS.length, s.rangeIndex);
            redraw();
        });
        second.addView(trail, new LinearLayout.LayoutParams(0, -2, 1f));
        brightness = new Button(context);
        brightness.setAllCaps(false);
        brightness.setOnClickListener(v -> {
            ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
            ClassicSceneUiState.update(s.scene, s.speedIndex, s.reverse,
                s.trailIndex, (s.rangeIndex + 1) % ClassicSceneUiState.RANGES.length);
            redraw();
        });
        second.addView(brightness, new LinearLayout.LayoutParams(0, -2, 1f));
        content.addView(second);
        TextView info = new TextView(context);
        info.setText("Szenenparameter gelten auch im originalen Musikbereich. "
            + "Lampen starten erst nach einer separat verifizierten Auswahl.");
        content.addView(info);
        redraw();
    }

    private void redraw() {
        ClassicSceneUiState.Selection s = ClassicSceneUiState.snapshot();
        for (int i = 0; i < cards.length; i++) {
            cards[i].setText((i == s.scene ? "✓ " : "") + ClassicSceneUiState.LABELS[i]);
            cards[i].setContentDescription(ClassicSceneUiState.LABELS[i] +
                (i == s.scene ? ", ausgewählt" : ", Lichtszene wählen"));
        }
        speed.setText("Tempo " + ClassicSceneUiState.SPEEDS[s.speedIndex] + "%");
        direction.setText(s.reverse ? "Rückwärts" : "Vorwärts");
        trail.setText("Schweif " + ClassicSceneUiState.TRAILS[s.trailIndex]);
        brightness.setText("Helligkeit " +
            ClassicSceneUiState.RANGES[s.rangeIndex][0] + "–" +
            ClassicSceneUiState.RANGES[s.rangeIndex][1] + "%");
    }
}
