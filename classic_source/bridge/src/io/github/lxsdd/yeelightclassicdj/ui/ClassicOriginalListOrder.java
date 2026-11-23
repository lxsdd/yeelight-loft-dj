package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.Context;
import android.content.ClipData;
import android.os.Build;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Original Classic SceneRecyclerViewAdapter and RoomRecyclerViewAdapter.
 * Only the pinned original list-model identity is used: scene model t.l()
 * (manager scene lookup key) and room model y3.a.n() (room lookup key).
 *
 * The list returned to the OEM adapter is a read-through VIEW. No OEM List,
 * scene content, room membership, database, LAN or cloud state is written.
 * Invalid/renamed/ambiguous OEM models retain their unmodified adapter list.
 */
public final class ClassicOriginalListOrder {
    private static final String CLONE = "io.lxsdd.yeelightdj";
    private static final String SCENE_ADAPTER =
        "com.yeelight.cherry.ui.fragment.SceneRecyclerViewAdapter";
    private static final String ROOM_ADAPTER =
        "com.yeelight.cherry.ui.adapter.RoomRecyclerViewAdapter";
    private static final String SCENE_MODEL = "com.yeelight.yeelib.models.t";
    private static final String ROOM_MODEL = "y3.a";
    private static final String HANDLE_TAG = "loftdj.original.model.drag.handle";
    private static final WeakHashMap<Object, OrderedList> ADAPTERS = new WeakHashMap<>();
    private static Context application;

    private ClassicOriginalListOrder() {}

    /** Only the clone's original-provider startup may register app context. */
    public static synchronized void registerCloneContext(Context context) {
        if (context == null || !CLONE.equals(context.getPackageName())) return;
        Context app = context.getApplicationContext();
        if (app != null && CLONE.equals(app.getPackageName())) application = app;
    }

    public static List<?> wrapScene(Object adapter, List<?> rows) {
        return wrap(adapter, rows, SCENE_ADAPTER, SCENE_MODEL, "l",
            ClassicOrderPolicy.Surface.SCENE_LIST, "classic-original-scenes");
    }
    public static List<?> wrapRoom(Object adapter, List<?> rows) {
        return wrap(adapter, rows, ROOM_ADAPTER, ROOM_MODEL, "n",
            ClassicOrderPolicy.Surface.ROOM_LIST, "classic-original-rooms");
    }

    private static synchronized List<?> wrap(Object adapter, List<?> rows,
                                              String adapterName, String modelName,
                                              String getter, ClassicOrderPolicy.Surface surface,
                                              String scope) {
        if (adapter == null || rows == null || application == null ||
            !adapterName.equals(adapter.getClass().getName())) return rows;
        OrderedList previous = ADAPTERS.get(adapter);
        // The OEM may reuse its adapter with an entirely new model list, or
        // pass an already wrapped list back through the constructor bridge.
        // Never leave two simultaneously writable gesture generations.
        if (rows instanceof OrderedList) {
            OrderedList supplied = (OrderedList) rows;
            if (previous == supplied && supplied.matches(modelName, getter, surface, scope)
                && supplied.valid()) return rows;
            rows = supplied.source;
        }
        if (previous != null) {
            previous.revoke();
            ADAPTERS.remove(adapter);
        }
        try {
            OrderedList wrapper = new OrderedList(rows,
                new ClassicLocalOrderStore(application), modelName, getter, surface, scope);
            ADAPTERS.put(adapter, wrapper);
            return wrapper;
        } catch (RuntimeException invalid) {
            // Unknown OEM ABI or malformed refreshed identities: retain the
            // authentic original list, never a stale foreign display view.
            return rows;
        }
    }

    public static void bindSceneRow(Object adapter, Object holder, int index) {
        bind(adapter, holder, index, SCENE_ADAPTER);
    }
    public static void bindRoomRow(Object adapter, Object holder, int index) {
        bind(adapter, holder, index, ROOM_ADAPTER);
    }

    private static void bind(Object adapter, Object holder, int index, String adapterName) {
        if (adapter == null || holder == null ||
            !adapterName.equals(adapter.getClass().getName())) return;
        OrderedList list;
        synchronized (ClassicOriginalListOrder.class) { list = ADAPTERS.get(adapter); }
        try {
            Object view = holder.getClass().getField("itemView").get(holder);
            if (!(view instanceof FrameLayout)) return;
            FrameLayout row = (FrameLayout) view;
            if (!CLONE.equals(row.getContext().getPackageName())) return;
            View old = row.findViewWithTag(HANDLE_TAG);
            String id = list != null && list.valid() ? list.idAt(index) : null;
            if (id == null) {
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
            handle.bind(adapter, list, id, list.revision());
        } catch (ReflectiveOperationException | RuntimeException denied) {
            // Original OEM adapter/UI must continue untouched on ABI drift.
        }
    }

    private static void redraw(Object adapter) {
        try { adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter); }
        catch (ReflectiveOperationException | RuntimeException denied) {
            // A saved order never authorizes arbitrary original UI callbacks.
        }
    }

    private static final class Drag {
        final Object adapter; final OrderedList list; final String id; final long revision;
        Drag(Object adapter, OrderedList list, String id, long revision) {
            this.adapter=adapter; this.list=list; this.id=id; this.revision=revision;
        }
    }

    private static final class Handle extends TextView {
        private Object adapter;
        private OrderedList list;
        private String id;
        private long revision;
        Handle(Context context) {
            super(context);
            setTag(HANDLE_TAG);
            setText("\u2261");
            setTextSize(22f);
            setGravity(Gravity.CENTER);
            setContentDescription("Originale Liste sortieren: halten, ziehen oder tippen");
            setOnLongClickListener(view -> {
                if (Build.VERSION.SDK_INT < 24 || list == null || id == null ||
                    !list.validRevision(revision)) return false;
                return startDragAndDrop(ClipData.newPlainText("", ""),
                    new View.DragShadowBuilder(this),
                    new Drag(adapter, list, id, revision), 0);
            });
            setOnDragListener((view,event) -> {
                if (!(event.getLocalState() instanceof Drag) ||
                    list == null || id == null) return false;
                Drag drag=(Drag)event.getLocalState();
                if (drag.adapter != adapter || drag.list != list ||
                    drag.revision != revision || !list.validRevision(revision)) return false;
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return true;
                if (event.getAction() == DragEvent.ACTION_DROP) {
                    if (!list.moveTo(drag.id, id, revision)) return false;
                    redraw(adapter);
                }
                return true;
            });
            setOnClickListener(view -> {
                if (list == null || id == null || !list.validRevision(revision)) return;
                PopupMenu menu = new PopupMenu(getContext(), this);
                menu.getMenu().add("Nach oben").setOnMenuItemClickListener(item -> {
                    if (!list.moveRelative(id,-1,revision)) return false;
                    redraw(adapter); return true;
                });
                menu.getMenu().add("Nach unten").setOnMenuItemClickListener(item -> {
                    if (!list.moveRelative(id,1,revision)) return false;
                    redraw(adapter); return true;
                });
                menu.getMenu().add("Rückgängig").setOnMenuItemClickListener(item -> {
                    if (!list.undo(revision)) return false;
                    redraw(adapter); return true;
                });
                menu.show();
            });
        }
        void bind(Object adapter, OrderedList list, String id, long revision) {
            this.adapter=adapter; this.list=list; this.id=id; this.revision=revision;
            setEnabled(true);
        }
        void clear() {
            adapter=null; list=null; id=null; revision=-1L;
            setEnabled(false);
        }
    }

    /**
     * The adapter receives a list view, never a mutated OEM model list.
     * On OEM refresh, the original sequence is rescanned. Active gesture
     * generations become stale, while new rows appear in OEM order.
     */
    public static final class OrderedList extends AbstractList<Object> {
        final List<?> source;
        final ClassicLocalOrderStore store;
        final String modelName, getter, scope;
        final ClassicOrderPolicy.Surface surface;
        private List<String> original, displayed;
        private long revision;
        private boolean invalid;

        synchronized boolean matches(String model, String key,
                                      ClassicOrderPolicy.Surface candidate,
                                      String stableScope) {
            return modelName.equals(model) && getter.equals(key) &&
                surface == candidate && scope.equals(stableScope);
        }

        synchronized void revoke() {
            invalid = true;
            revision++;
        }

        OrderedList(List<?> source, ClassicLocalOrderStore store, String modelName,
                    String getter, ClassicOrderPolicy.Surface surface, String scope) {
            this.source=source; this.store=store; this.modelName=modelName;
            this.getter=getter; this.surface=surface; this.scope=scope;
            original=scan(source);
            displayed=ClassicOrderPolicy.project(original, store.read(surface,scope));
        }

        private List<String> scan(List<?> items) {
            if (items.size() > ClassicOrderPolicy.MAX_ITEMS)
                throw new IllegalArgumentException("OEM list size unverified");
            ArrayList<String> ids = new ArrayList<>();
            HashSet<String> unique = new HashSet<>();
            for (Object item : items) {
                if (item == null || !modelName.equals(item.getClass().getName()))
                    throw new IllegalArgumentException("OEM model type unverified");
                try {
                    Method getterMethod = item.getClass().getMethod(getter);
                    Object raw=getterMethod.invoke(item);
                    if (!(raw instanceof String) ||
                        !ClassicOrderPolicy.validStableId((String)raw) ||
                        !unique.add((String)raw))
                        throw new IllegalArgumentException("OEM stable model ID unverified");
                    ids.add((String)raw);
                } catch (ReflectiveOperationException denied) {
                    throw new IllegalArgumentException("OEM stable model ABI drift",denied);
                }
            }
            return ids;
        }

        private synchronized boolean refresh() {
            if (invalid) return false;
            try {
                List<String> newest=scan(source);
                if (!newest.equals(original)) {
                    original=newest;
                    displayed=ClassicOrderPolicy.project(original, store.read(surface,scope));
                    revision++;
                }
                return true;
            } catch (RuntimeException denied) {
                invalid=true; revision++; return false;
            }
        }

        synchronized boolean valid() { return refresh(); }
        synchronized long revision() { return revision; }
        synchronized boolean validRevision(long candidate) {
            return refresh() && candidate == revision;
        }
        synchronized String idAt(int index) {
            if (!refresh() || index < 0 || index >= displayed.size()) return null;
            return displayed.get(index);
        }

        @Override public synchronized int size() { return source.size(); }

        @Override public synchronized Object get(int index) {
            if (!refresh()) return source.get(index);
            if (index < 0 || index >= displayed.size())
                throw new IndexOutOfBoundsException();
            int actual=original.indexOf(displayed.get(index));
            if (actual < 0) throw new IllegalStateException("Original model moved");
            return source.get(actual);
        }

        synchronized boolean moveTo(String from, String before, long expected) {
            if (!validRevision(expected) || !displayed.contains(from) ||
                !displayed.contains(before) || from.equals(before)) return false;
            return persist(from,displayed.indexOf(before));
        }
        synchronized boolean moveRelative(String from,int direction,long expected) {
            if (!validRevision(expected) || (direction != -1 && direction != 1))
                return false;
            int at=displayed.indexOf(from);
            if (at < 0 || at+direction < 0 || at+direction >= displayed.size())
                return false;
            return persist(from,at+direction);
        }
        private boolean persist(String from,int index) {
            if (!store.applyUserDropAtIndex(surface,scope,original,displayed,from,index))
                return false;
            displayed=ClassicOrderPolicy.project(original,store.read(surface,scope));
            revision++;
            return true;
        }
        synchronized boolean undo(long expected) {
            if (!validRevision(expected) ||
                !store.undoLastUserDrop(surface,scope,original,displayed))
                return false;
            displayed=ClassicOrderPolicy.project(original,store.read(surface,scope));
            revision++;
            return true;
        }
    }
}
