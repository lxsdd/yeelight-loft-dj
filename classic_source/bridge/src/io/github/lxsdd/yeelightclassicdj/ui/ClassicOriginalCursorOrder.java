package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.view.View;
import android.view.DragEvent;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Original Classic device/group cursor display order. The source cursor,
 * OEM click handlers, database, group membership and cloud are untouched.
 * Clone-only: verified _id keys, exact adapter owner and explicit gesture.
 * Unverified row or cursor layouts retain the original OEM presentation.
 */
public final class ClassicOriginalCursorOrder {
    private static final String CLONE = "io.lxsdd.yeelightdj";
    private static final String DEVICE =
        "com.yeelight.cherry.ui.fragment.DeviceRecyclerViewAdapter";
    private static final String GROUP =
        "com.yeelight.cherry.ui.fragment.GroupRecyclerViewAdapter";
    private static final String HANDLE = "loftdj.original.cursor.drag.handle";

    private ClassicOriginalCursorOrder() {}

    public static Cursor wrapDevices(Context context, Cursor cursor) {
        return wrap(context, cursor, ClassicOrderPolicy.Surface.DEVICE_LIST, "classic-devices-global");
    }

    public static Cursor wrapGroups(Context context, Cursor cursor) {
        return wrap(context, cursor, ClassicOrderPolicy.Surface.GROUP_LIST, "classic-groups-global");
    }

    private static Cursor wrap(Context context, Cursor cursor,
                               ClassicOrderPolicy.Surface surface, String scope) {
        if (cursor instanceof OrderedCursor) {
            OrderedCursor previous = (OrderedCursor) cursor;
            if (previous.surface == surface && previous.scope.equals(scope))
                return cursor;
            // A recycled OEM cursor may be presented on another independent
            // list surface. Unwrap first, then validate the new scope.
            cursor = previous.delegate;
        }
        if (context == null || !CLONE.equals(context.getPackageName()) ||
            cursor == null || cursor.isClosed()) return cursor;
        try {
            return new OrderedCursor(cursor, new ClassicLocalOrderStore(context),
                surface, scope);
        } catch (RuntimeException invalid) {
            return cursor;
        }
    }

    /**
     * Invoked after ORIGINAL onBindViewHolder's cursor-bound row rendering.
     * AndroidX ViewHolder.itemView is a public field; no private OEM member
     * or obfuscated method is accessed. No click listener is overwritten.
     */
    public static void bindRow(Object adapter, Object holder, Cursor cursor) {
        if (!(cursor instanceof OrderedCursor) || adapter == null || holder == null)
            return;
        OrderedCursor ordered = (OrderedCursor) cursor;
        String owner = adapter.getClass().getName();
        if (!(DEVICE.equals(owner) && ordered.surface == ClassicOrderPolicy.Surface.DEVICE_LIST) &&
            !(GROUP.equals(owner) && ordered.surface == ClassicOrderPolicy.Surface.GROUP_LIST))
            return;
        try {
            Object item = holder.getClass().getField("itemView").get(holder);
            if (!(item instanceof FrameLayout)) return;
            FrameLayout row = (FrameLayout) item;
            if (!CLONE.equals(row.getContext().getPackageName())) return;
            Object old = row.findViewWithTag(HANDLE);
            String stableId = ordered.validSnapshot()
                ? ordered.idAt(cursor.getPosition()) : null;
            if (stableId == null) {
                // RecyclerView may recycle a former valid row into an
                // unverified/empty entry. Revoke its old gesture immediately.
                if (old instanceof Handle) ((Handle) old).clear();
                return;
            }
            Handle handle;
            if (old instanceof Handle) handle = (Handle) old;
            else if (old == null) {
                handle = new Handle(row.getContext());
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.round(38 * row.getResources().getDisplayMetrics().density),
                    Math.round(44 * row.getResources().getDisplayMetrics().density),
                    Gravity.END | Gravity.CENTER_VERTICAL);
                row.addView(handle, lp);
            } else return;
            handle.bind(ordered, adapter, stableId);
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            // A new OEM layout/row type must not crash the original app.
        }
    }

    private static void redraw(Object adapter) {
        try {
            adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The UI preference is saved; an OEM refresh will rebind it.
        }
    }

    private static final class Drag {
        final OrderedCursor cursor;
        final Object adapter;
        final String id;
        final long revision;
        Drag(OrderedCursor cursor, Object adapter, String id, long revision) {
            this.cursor = cursor; this.adapter = adapter; this.id = id;
            this.revision = revision;
        }
    }

    private static final class Handle extends TextView {
        private OrderedCursor cursor;
        private Object adapter;
        private String id;
        private long revision;

        Handle(Context context) {
            super(context);
            setTag(HANDLE);
            setText("\u2261");
            setTextSize(22f);
            setGravity(Gravity.CENTER);
            setContentDescription("Sortieren: halten und auf einen anderen Griff ziehen");
            setOnLongClickListener(view -> {
                if (android.os.Build.VERSION.SDK_INT < 24 || cursor == null || id == null ||
                    !cursor.validRevision(revision)) return false;
                return startDragAndDrop(
                    android.content.ClipData.newPlainText("", ""),
                    new View.DragShadowBuilder(this),
                    new Drag(cursor, adapter, id, revision), 0);
            });
            setOnDragListener((view, event) -> {
                Object state = event.getLocalState();
                if (!(state instanceof Drag) || cursor == null || id == null)
                    return false;
                Drag moving = (Drag) state;
                if (moving.cursor != cursor || moving.adapter != adapter ||
                    moving.revision != revision || !cursor.validRevision(revision))
                    return false;
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return true;
                if (event.getAction() == DragEvent.ACTION_DROP) {
                    if (!cursor.moveTo(moving.id, id, revision)) return false;
                    redraw(adapter);
                }
                return true;
            });
            setOnClickListener(view -> {
                if (cursor == null || id == null || !cursor.validRevision(revision))
                    return;
                PopupMenu menu = new PopupMenu(getContext(), this);
                menu.getMenu().add("Nach oben").setOnMenuItemClickListener(item -> {
                    if (!cursor.moveRelative(id, -1, revision)) return false;
                    redraw(adapter); return true;
                });
                menu.getMenu().add("Nach unten").setOnMenuItemClickListener(item -> {
                    if (!cursor.moveRelative(id, 1, revision)) return false;
                    redraw(adapter); return true;
                });
                menu.getMenu().add("Rückgängig").setOnMenuItemClickListener(item -> {
                    if (!cursor.undo(revision)) return false;
                    redraw(adapter); return true;
                });
                menu.show();
            });
        }

        void bind(OrderedCursor cursor, Object adapter, String id) {
            this.cursor = cursor; this.adapter = adapter; this.id = id;
            this.revision = cursor.revision();
            setEnabled(true);
        }
        void clear() {
            cursor = null; adapter = null; id = null; revision = -1L;
            setEnabled(false);
        }
    }

    /** CursorWrapper maps visible positions onto real OEM rows, never edits them. */
    public static final class OrderedCursor extends CursorWrapper {
        final Cursor delegate;
        final ClassicLocalOrderStore store;
        final ClassicOrderPolicy.Surface surface;
        final String scope;
        private List<String> original;
        private final Map<String, Integer> sourcePositions = new HashMap<>();
        private List<String> displayed;
        private int position = -1;
        private boolean invalid;
        private long revision;

        OrderedCursor(Cursor cursor, ClassicLocalOrderStore store,
                      ClassicOrderPolicy.Surface surface, String scope) {
            super(cursor);
            delegate = cursor;
            this.store = store;
            this.surface = surface;
            this.scope = scope;
            original = Collections.unmodifiableList(scan(cursor));
            for (int i = 0; i < original.size(); i++)
                sourcePositions.put(original.get(i), i);
            displayed = ClassicOrderPolicy.project(original, store.read(surface, scope));
        }

        private static List<String> scan(Cursor cursor) {
            if (cursor.isClosed() || cursor.getCount() > ClassicOrderPolicy.MAX_ITEMS)
                throw new IllegalArgumentException("Unverified OEM cursor");
            int id = cursor.getColumnIndex("_id");
            if (id < 0) throw new IllegalArgumentException("Missing stable _id");
            int prior = cursor.getPosition();
            try {
                List<String> ids = new ArrayList<>();
                HashSet<String> unique = new HashSet<>();
                for (int i = 0; i < cursor.getCount(); i++) {
                    if (!cursor.moveToPosition(i) || cursor.isNull(id))
                        throw new IllegalArgumentException("Unreadable stable ID");
                    long key = cursor.getLong(id);
                    if (key < 0 || !unique.add(Long.toString(key)))
                        throw new IllegalArgumentException("Invalid/duplicate stable ID");
                    ids.add(Long.toString(key));
                }
                return ids;
            } finally { cursor.moveToPosition(prior); }
        }

        synchronized boolean validSnapshot() {
            if (invalid) return false;
            if (delegate.isClosed()) {
                invalid = true;
                revision++;
                return false;
            }
            try {
                List<String> refreshed = scan(delegate);
                if (!original.equals(refreshed)) {
                    // Same OEM Cursor instance, new set/order of stable _id
                    // rows. Rebuild only the read-through position map. The
                    // original cursor and account/database are never modified.
                    original = Collections.unmodifiableList(refreshed);
                    sourcePositions.clear();
                    for (int i = 0; i < original.size(); i++)
                        sourcePositions.put(original.get(i), i);
                    displayed = ClassicOrderPolicy.project(original, store.read(surface, scope));
                    revision++;
                    // The row currently binding was resolved before this
                    // refresh; prohibit attaching a potentially wrong grip.
                    position = -1;
                    return false;
                }
                return true;
            } catch (RuntimeException mismatch) {
                // Missing/duplicate/non-numeric OEM identities are not a
                // refresh. Deny all future drag writes for this cursor.
                invalid = true;
                revision++;
                return false;
            }
        }

        public synchronized long revision() { return revision; }
        synchronized boolean validRevision(long expected) {
            return validSnapshot() && revision == expected;
        }

        synchronized String idAt(int p) {
            List<String> rows = invalid ? original : displayed;
            return p < 0 || p >= rows.size() ? null : rows.get(p);
        }

        @Override public synchronized int getPosition() {
            return invalid ? delegate.getPosition() : position;
        }
        @Override public synchronized boolean moveToPosition(int requested) {
            List<String> rows = invalid ? original : displayed;
            int source = requested;
            if (!invalid && requested >= 0 && requested < rows.size())
                source = sourcePositions.get(rows.get(requested));
            boolean moved = delegate.moveToPosition(source);
            position = requested;
            return moved;
        }
        @Override public synchronized boolean move(int by) {
            return moveToPosition(getPosition() + by);
        }
        @Override public synchronized boolean moveToFirst() { return moveToPosition(0); }
        @Override public synchronized boolean moveToLast() { return moveToPosition(getCount()-1); }
        @Override public synchronized boolean moveToNext() { return moveToPosition(getPosition()+1); }
        @Override public synchronized boolean moveToPrevious() { return moveToPosition(getPosition()-1); }
        @Override public synchronized boolean isBeforeFirst() { return getPosition() < 0; }
        @Override public synchronized boolean isAfterLast() { return getPosition() >= getCount(); }
        @Override public synchronized boolean isFirst() {
            return getCount() > 0 && getPosition() == 0;
        }
        @Override public synchronized boolean isLast() {
            return getCount() > 0 && getPosition() == getCount()-1;
        }

        public synchronized boolean moveTo(String dragged, String target, long expected) {
            return validRevision(expected) && moveTo(dragged, target);
        }
        public synchronized boolean moveTo(String dragged, String target) {
            if (!validSnapshot() || !displayed.contains(dragged) ||
                !displayed.contains(target) || dragged.equals(target)) return false;
            return persist(dragged, displayed.indexOf(target));
        }
        public synchronized boolean moveRelative(String id, int direction, long expected) {
            return validRevision(expected) && moveRelative(id, direction);
        }
        public synchronized boolean moveRelative(String id, int direction) {
            if (!validSnapshot() || (direction != -1 && direction != 1)) return false;
            int at = displayed.indexOf(id);
            if (at < 0 || at + direction < 0 ||
                at + direction >= displayed.size()) return false;
            return persist(id, at + direction);
        }
        private boolean persist(String id, int newIndex) {
            if (!store.applyUserDropAtIndex(surface, scope, original, displayed, id, newIndex))
                return false;
            displayed = ClassicOrderPolicy.project(original, store.read(surface, scope));
            position = -1;
            revision++;
            return true;
        }
        public synchronized boolean undo(long expected) {
            return validRevision(expected) && undo();
        }
        public synchronized boolean undo() {
            if (!validSnapshot() ||
                !store.undoLastUserDrop(surface, scope, original, displayed)) return false;
            displayed = ClassicOrderPolicy.project(original, store.read(surface, scope));
            position = -1;
            revision++;
            return true;
        }
    }
}
