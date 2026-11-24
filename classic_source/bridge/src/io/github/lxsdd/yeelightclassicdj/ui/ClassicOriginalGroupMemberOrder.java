package io.github.lxsdd.yeelightclassicdj.ui;

import android.content.ClipData;
import android.content.Context;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import android.database.DataSetObserver;
import android.os.Build;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.WrapperListAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Original GroupMusicModeManagerActivity ListView uses its original BaseAdapter
 * and item click callbacks. This read-through ListAdapter maps DISPLAY indices
 * to the exact OEM item before invoking getItem/getView/isEnabled/getItemId.
 * Only a separate clone-local preference is changed after an explicit grip
 * gesture; the original J1() group membership, devices and commands never are.
 */
public final class ClassicOriginalGroupMemberOrder {
    private static final String CLONE = "io.lxsdd.yeelightdj";
    private static final String ORIGINAL_ADAPTER =
        "com.yeelight.yeelib.ui.activity.GroupMusicModeManagerActivity$g";
    private ClassicOriginalGroupMemberOrder() {}

    /** Replaces ONLY the pinned original onCreate ListView.setAdapter call.
     * Unknown Activity, owner, adapter ABI or scope preserves OEM behavior.
     */
    public static void installGroupAdapter(ListView list, ListAdapter original) {
        if (list == null) return;
        // A ListView may be rebound while an old row/popup or async LAN scan
        // still holds its previous wrapper. Invalidate it before changing the
        // adapter; never expose the stale wrapper as an authorized DJ owner.
        ListAdapter previous = list.getAdapter();
        if (previous == original) return;
        if (previous instanceof OrderedGroupAdapter) {
            OrderedGroupAdapter old = (OrderedGroupAdapter)previous;
            if (old.originalList == list) {
                old.retire();
                // A new OEM adapter is not proof of identical membership.
                // Explicit DJ intent survives, but its output lease cannot.
                ClassicOriginalMusicSelection.originalGroupMembershipChanged();
            }
        }
        if (original == null || !CLONE.equals(list.getContext().getPackageName()) ||
            !ORIGINAL_ADAPTER.equals(original.getClass().getName())) {
            list.setAdapter(original);
            return;
        }
        String scope = ClassicOriginalMusicSelection.originalGroupDisplayScope();
        if (!ClassicOrderPolicy.validStableId(scope)) {
            list.setAdapter(original);
            return;
        }
        OrderedGroupAdapter replacement = null;
        try {
            replacement = new OrderedGroupAdapter(list, original,
                new ClassicLocalOrderStore(list.getContext()), scope);
            list.setAdapter(replacement);
        } catch (RuntimeException invalid) {
            // Failed setAdapter must not leak a registered OEM observer.
            if (replacement != null) replacement.retire();
            list.setAdapter(original);
        }
    }

    static final class OrderedGroupAdapter extends BaseAdapter implements WrapperListAdapter {
        final ListAdapter original;
        final ListView originalList;
        final ClassicLocalOrderStore store;
        final String scope;
        private List<String> sourceIds = Collections.emptyList();
        private List<String> shownIds = Collections.emptyList();
        private boolean verified;
        private boolean revoked;
        private long revision;
        private boolean observerRegistered;
        /** A superseded adapter cannot authorize a gesture, an async LAN
         * result, or a late OEM dataset notification. A new ListView adapter
         * is a new unverified witness, even for the same apparent room.
         */
        private boolean ownsCurrentAdapter() {
            return !revoked && originalList.getAdapter() == this &&
                scope.equals(ClassicOriginalMusicSelection.originalGroupDisplayScope());
        }
        private boolean acceptsGesture() {
            return ownsCurrentAdapter() && originalList.isAttachedToWindow();
        }
        /** Idempotent: an Android detach and a same-ListView rebind may race.
         * Remove the observer immediately so retired OEM adapters cannot keep
         * the UI wrapper alive indefinitely or revoke a new Music lease.
         */
        private synchronized void retire() {
            if (revoked) return;
            revoked = true; verified = false; revision++;
            if (observerRegistered) {
                observerRegistered = false;
                try { original.unregisterDataSetObserver(observer); }
                catch (RuntimeException alreadyRemoved) {
                    // Revoked first; even an unusual OEM adapter may not
                    // restore the wrapper's callback authority.
                }
            }
        }
        // A positional OEM click must observe source-indexed getItem/getItemId
        // during that one synchronous callback, not the DnD display indices.
        private final ThreadLocal<Boolean> originalClickIndex = new ThreadLocal<>();
        /** Preserve an owned Music lease on status-only refresh, but revoke
         * it immediately when the original adapter's member set changes.
         * The original J1() hook may not run again before the next OEM bind.
         */
        private synchronized void observedOriginalAdapterChange(boolean invalidated) {
            if (!ownsCurrentAdapter()) return;
            boolean wasVerified = verified;
            List<String> previous = new ArrayList<>(sourceIds);
            revision++;
            verified = false;
            boolean sameMembers = !invalidated && wasVerified &&
                refresh() && previous.equals(sourceIds);
            if (!sameMembers) ClassicOriginalMusicSelection.originalGroupMembershipChanged();
        }

        private final DataSetObserver observer = new DataSetObserver() {
            @Override public void onChanged() {
                observedOriginalAdapterChange(false);
                notifyDataSetChanged();
            }
            @Override public void onInvalidated() {
                observedOriginalAdapterChange(true);
                notifyDataSetInvalidated();
            }
        };

        OrderedGroupAdapter(ListView list, ListAdapter original,
                            ClassicLocalOrderStore store, String scope) {
            this.original=original; this.originalList=list; this.store=store; this.scope=scope;
            original.registerDataSetObserver(observer);
            observerRegistered = true;
            list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    retire();
                }
            });
        }

        @Override public ListAdapter getWrappedAdapter() { return original; }
        @Override public int getCount() { return original.getCount(); }
        @Override public boolean isEmpty() { return original.isEmpty(); }
        @Override public boolean areAllItemsEnabled() {
            return original.areAllItemsEnabled();
        }
        @Override public boolean isEnabled(int position) {
            return original.isEnabled(originalIndex(position));
        }
        @Override public Object getItem(int position) {
            return original.getItem(originalIndex(position));
        }
        @Override public long getItemId(int position) {
            return original.getItemId(originalIndex(position));
        }
        @Override public boolean hasStableIds() { return original.hasStableIds(); }
        @Override public int getItemViewType(int position) {
            return original.getItemViewType(originalIndex(position));
        }
        @Override public int getViewTypeCount() { return original.getViewTypeCount(); }

        private synchronized boolean refresh() {
            if (!ownsCurrentAdapter()) {
                verified=false; revision++; return false;
            }
            try {
                int count=original.getCount();
                if (count < 0 || count > ClassicOrderPolicy.MAX_ITEMS) {
                    verified=false; revision++; return false;
                }
                ArrayList<Object> models = new ArrayList<>(count);
                for (int i=0;i<count;i++) models.add(original.getItem(i));
                ClassicOriginalGroupMemberWitness.Snapshot result =
                    ClassicOriginalGroupMemberWitness.inspect(models);
                if (!result.identitiesVerified || result.observedCount!=count) {
                    verified=false; revision++; return false;
                }
                List<String> now=result.keysForDisplay();
                if (!verified || !sourceIds.equals(now)) {
                    sourceIds=new ArrayList<>(now);
                    shownIds=ClassicOrderPolicy.project(sourceIds,
                        store.read(ClassicOrderPolicy.Surface.GROUP_MEMBERS,scope));
                    verified=true;
                    revision++;
                }
                return true;
            } catch (RuntimeException invalid) {
                verified=false; revision++; return false;
            }
        }

        synchronized long revision() { return revision; }
        synchronized boolean displayIdAtIndex(String key,long expected) {
            return refresh() && revision==expected && shownIds.contains(key);
        }
        synchronized String displayIdAt(int position) {
            return refresh() && position >= 0 && position < shownIds.size()
                ? shownIds.get(position) : null;
        }
        private synchronized int originalIndex(int displayed) {
            if (Boolean.TRUE.equals(originalClickIndex.get())) return displayed;
            if (!refresh() || displayed < 0 || displayed >= shownIds.size())
                return displayed;
            int pos=sourceIds.indexOf(shownIds.get(displayed));
            return pos < 0 ? displayed : pos;
        }

        /** Route ONLY the verified, still-attached original GroupMusic row
         * to the OEM listener. No synthetic device action or network request.
         */
        synchronized boolean dispatchOriginalClick(String key,long expected,View originalView) {
            if (key==null || originalView==null || originalClickIndex.get()!=null ||
                !acceptsGesture() || !refresh() || revision!=expected) return false;
            int visible=shownIds.indexOf(key);
            int actual=sourceIds.indexOf(key);
            if (visible<0 || actual<0 || visible>=original.getCount() ||
                !original.isEnabled(actual)) return false;
            AdapterView.OnItemClickListener listener=originalList.getOnItemClickListener();
            if (listener==null) return false;
            originalClickIndex.set(Boolean.TRUE);
            try {
                // A native row action may power/toggle/reassign its OEM device.
                // Never keep a separate DJ Music grant over that action.
                ClassicOriginalMusicSelection.originalGroupRowAction();
                listener.onItemClick(originalList,originalView,actual,original.getItemId(actual));
                return true;
            } finally {
                // A throw, reentrant click or OEM list invalidation must never
                // leave subsequent display positions in the source-index mode.
                originalClickIndex.remove();
            }
        }
        synchronized boolean moveTo(String from, String target, long expected) {
            if (!acceptsGesture() || !refresh() || revision != expected ||
                from.equals(target) ||
                !shownIds.contains(from) || !shownIds.contains(target)) return false;
            return persist(from,shownIds.indexOf(target));
        }
        synchronized boolean moveRelative(String from,int by,long expected) {
            if (!acceptsGesture() || !refresh() || revision != expected ||
                (by!=-1 && by!=1)) return false;
            int pos=shownIds.indexOf(from);
            if (pos < 0 || pos+by < 0 || pos+by>=shownIds.size()) return false;
            return persist(from,pos+by);
        }
        private boolean persist(String from,int index) {
            if (!store.applyUserDropAtIndex(ClassicOrderPolicy.Surface.GROUP_MEMBERS,
                scope,sourceIds,shownIds,from,index)) return false;
            shownIds=ClassicOrderPolicy.project(sourceIds,
                store.read(ClassicOrderPolicy.Surface.GROUP_MEMBERS,scope));
            // A formerly verified LAN endpoint order is no longer the
            // original group's explicitly chosen spatial order.
            ClassicOriginalMusicSelection.originalDisplayOrderChanged();
            revision++;
            notifyDataSetChanged();
            return true;
        }
        /** A stale drag/list revision cannot enroll any OEM member. */
        synchronized boolean markDjMember(String id, boolean include, long expected) {
            return acceptsGesture() && refresh() && revision==expected &&
                shownIds.contains(id) &&
                ClassicOriginalMusicSelection.markDjIntentFromOriginalAdapter(
                    id,include,sourceIds);
        }
        synchronized boolean markDjGroup(boolean include, long expected) {
            return acceptsGesture() && refresh() && revision==expected &&
                ClassicOriginalMusicSelection.markAllDjIntentFromOriginalAdapter(
                    include,sourceIds);
        }
        /** Deliberate, READ-ONLY LAN inquiry from the original GroupMusic
         * menu. Never scans when an Activity opens or during background music.
         * Verified identity and the exact current adapter set are checked both
         * BEFORE sending and AFTER returning from the asynchronous query.
         */
        /**
         * Match both the ORIGINAL adapter's live models and the user's
         * explicit DJ choices. This runs on the UI thread before discovery
         * and again AFTER the async scan, before installing a LAN grant.
         * A reordered, refreshed or detached original view cannot revive
         * old IP endpoints or silently authorize a different cohort.
         */
        synchronized boolean stillSameLanCandidate(long expected,
                List<String> originalIds, List<String> chosen) {
            return acceptsGesture() && refresh() &&
                revision == expected && sourceIds.equals(originalIds) &&
                chosen != null && !chosen.isEmpty() &&
                chosen.equals(ClassicOriginalMusicSelection.selectedKeysForLanCheck(
                    originalIds,shownIds));
        }

        /** One user-approved read-only query; any intervening selection or
         * adapter mutation revokes the candidate before and after network I/O.
         * Only the foreground ORIGINAL GroupMusic host can accept its result.
         */
        synchronized boolean requestLanCheck(long expected) {
            if (!acceptsGesture() || !refresh() || revision != expected) return false;
            final ArrayList<String> originalIds = new ArrayList<>(sourceIds);
            final List<String> chosen =
                ClassicOriginalMusicSelection.selectedKeysForLanCheck(originalIds,
                    new ArrayList<>(shownIds));
            if (chosen.isEmpty()) return false;
            final Context context = originalList.getContext();
            new AlertDialog.Builder(context)
                .setTitle("Ausgewählte DJ-Lampen prüfen")
                .setMessage("Nur die ausdrücklich vorgemerkten Classic-Geräte werden " +
                    "über eine einmalige lokale Yeelight-Suche abgeglichen. " +
                    "Es wird keine Lampe eingeschaltet und keine Szene geändert.")
                .setNegativeButton("Abbrechen",null)
                .setPositiveButton("LAN nur lesen",(dialog,which)->{
                    // The user may have changed a row while the dialog was open.
                    if (!stillSameLanCandidate(expected,originalIds,chosen)) return;
                    new Thread(()->{
                        ClassicOriginalLanDiscovery.VerifiedTargets proof = null;
                        try {
                            String localIp = ClassicOriginalLanDiscovery.wifiPrivateIpv4();
                            proof = ClassicOriginalLanDiscovery.discover(chosen,localIp);
                        } catch (java.io.IOException | RuntimeException denied) {
                            // A failed scan can never install an endpoint grant.
                        }
                        final ClassicOriginalLanDiscovery.VerifiedTargets verified = proof;
                        new Handler(Looper.getMainLooper()).post(()->{
                            // Original adapters are UI-owned; never read their
                            // current models on the discovery thread.
                            final boolean accepted;
                            synchronized (OrderedGroupAdapter.this) {
                                accepted = verified != null &&
                                    stillSameLanCandidate(expected,originalIds,chosen) &&
                                    ClassicOriginalMusicSelection.acceptLanProof(
                                        originalIds,new ArrayList<>(shownIds),verified);
                            }
                            if (originalList.isAttachedToWindow())
                                Toast.makeText(context,accepted ?
                                    "DJ-LAN-Ziele geprüft · zur Music-Ansicht zurückkehren" :
                                    "Auswahl geändert oder LAN-Zuordnung unklar · Lichtausgabe gesperrt",
                                    Toast.LENGTH_LONG).show();
                        });
                    },"Classic-Original-LAN-Readonly").start();
                }).show();
            return true;
        }

        synchronized boolean undo(long expected) {
            if (!acceptsGesture() || !refresh() || revision!=expected ||
                !store.undoLastUserDrop(ClassicOrderPolicy.Surface.GROUP_MEMBERS,
                    scope,sourceIds,shownIds)) return false;
            shownIds=ClassicOrderPolicy.project(sourceIds,
                store.read(ClassicOrderPolicy.Surface.GROUP_MEMBERS,scope));
            ClassicOriginalMusicSelection.originalDisplayOrderChanged();
            revision++;
            notifyDataSetChanged();
            return true;
        }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            Row oldRow = convert instanceof Row ? (Row)convert : null;
            View priorOriginal = oldRow == null ? convert : oldRow.content;
            final int sourcePosition;
            final long expected;
            final String stable;
            synchronized (this) {
                sourcePosition=originalIndex(position);
                expected=revision;
                stable=verified && position>=0 && position<shownIds.size()
                    ? shownIds.get(position) : null;
            }
            View row=original.getView(sourcePosition,priorOriginal,parent);
            // OEM getView may mutate/refresh its source during rendering.
            // Do not bind a stale row to any new click or drag gesture.
            synchronized (this) {
                if (stable == null || !refresh() || revision!=expected ||
                    position<0 || position>=shownIds.size() ||
                    !stable.equals(shownIds.get(position)) ||
                    sourcePosition!=sourceIds.indexOf(stable)) {
                    if (oldRow!=null) oldRow.release();
                    return row!=null && row.getParent()==null
                        ? row : new FrameLayout(parent.getContext());
                }
            }
            // An OEM getView() may recycle the child of our FrameLayout.
            // Never return an attached child to ListView when the original
            // identities become stale, or Android throws a parent conflict.
            // Nor may a foreign parent's view be detached: fail closed with
            // an inert row instead of rebinding a potentially wrong device.
            if (row != null && row.getParent() != null &&
                (oldRow == null || row.getParent() != oldRow ||
                 row != oldRow.content)) {
                if (oldRow != null) oldRow.release();
                return new FrameLayout(row.getContext());
            }
            if (stable==null || row==null) {
                if (oldRow != null) oldRow.release();
                return row;
            }
            try {
                Row host=oldRow == null ? new Row(row.getContext()) : oldRow;
                host.bind(this,row,stable,expected);
                return host;
            } catch (RuntimeException layoutMismatch) {
                if (oldRow != null) oldRow.release();
                return row.getParent() == null ? row : new FrameLayout(row.getContext());
            }
        }
    }

    private static final class Drag {
        final OrderedGroupAdapter adapter;
        final String key;
        final long revision;
        Drag(OrderedGroupAdapter adapter,String key,long revision) {
            this.adapter=adapter; this.key=key; this.revision=revision;
        }
    }
    private static final class Row extends FrameLayout {
        View content;
        final Grip grip;
        Row(Context context) {
            super(context);
            grip=new Grip(context);
            int w=Math.round(42*getResources().getDisplayMetrics().density);
            int h=Math.round(44*getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(
                w,h,Gravity.END|Gravity.CENTER_VERTICAL);
            addView(grip,lp);
        }
        void bind(OrderedGroupAdapter adapter,View actual,String id,long revision) {
            if (actual!=content) {
                if (content!=null) removeView(content);
                if (actual.getParent()!=null) throw new IllegalStateException("OEM row still attached");
                content=actual;
                addView(actual,0,new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            grip.bind(adapter,id,revision);
            // OEM child controls still execute their own source-bound actions.
            // Otherwise intercept the list-level click and forward the correct
            // original index, never the newly displayed DnD position.
            setOnClickListener(v -> adapter.dispatchOriginalClick(id,revision,content));
            setClickable(true);
        }
        /** Release a recycled OEM child before returning it to ListView.
         * Never detach a view that belongs to an unrelated OEM parent.
         * Revocation also disables the stale drag/menu callbacks.
         */
        void release() {
            grip.clear();
            setOnClickListener(null);
            setClickable(false);
            if (content != null && content.getParent() == this)
                removeView(content);
            content = null;
        }
    }
    private static final class Grip extends TextView {
        private OrderedGroupAdapter adapter;
        private String id;
        private long revision;
        Grip(Context context) {
            super(context);
            setText("\u2261");
            setTextSize(22f);
            setGravity(Gravity.CENTER);
            setContentDescription("Gruppenmitglied sortieren");
            setOnLongClickListener(v -> {
                if (Build.VERSION.SDK_INT < 24 || !fresh()) return false;
                return startDragAndDrop(ClipData.newPlainText("",""),
                    new View.DragShadowBuilder(this),new Drag(adapter,id,revision),0);
            });
            setOnDragListener((v,event)->{
                Object state=event.getLocalState();
                if (!(state instanceof Drag) || !fresh()) return false;
                Drag incoming=(Drag)state;
                if (incoming.adapter!=adapter || incoming.revision!=revision) return false;
                if (event.getAction()==DragEvent.ACTION_DRAG_STARTED) return true;
                if (event.getAction()==DragEvent.ACTION_DROP)
                    return adapter.moveTo(incoming.key,id,revision);
                return true;
            });
            setOnClickListener(v->{
                if (!fresh()) return;
                PopupMenu menu=new PopupMenu(getContext(),this);
                menu.getMenu().add("Nach oben").setOnMenuItemClickListener(item ->
                    adapter.moveRelative(id,-1,revision));
                menu.getMenu().add("Nach unten").setOnMenuItemClickListener(item ->
                    adapter.moveRelative(id,1,revision));
                menu.getMenu().add("Rückgängig").setOnMenuItemClickListener(item ->
                    adapter.undo(revision));
                menu.getMenu().add("Für DJ vormerken").setOnMenuItemClickListener(item ->
                    adapter.markDjMember(id,true,revision));
                menu.getMenu().add("Aus DJ-Vormerkung entfernen").setOnMenuItemClickListener(item ->
                    adapter.markDjMember(id,false,revision));
                menu.getMenu().add("Alle Gruppenmitglieder vormerken").setOnMenuItemClickListener(item ->
                    adapter.markDjGroup(true,revision));
                menu.getMenu().add("DJ-Vormerkung leeren").setOnMenuItemClickListener(item ->
                    adapter.markDjGroup(false,revision));
                menu.getMenu().add("Vorgemerkte DJ-Lampen im LAN prüfen").setOnMenuItemClickListener(item ->
                    adapter.requestLanCheck(revision));
                menu.show();
            });
        }
        private boolean fresh() {
            return adapter!=null && id!=null && adapter.displayIdAtIndex(id,revision);
        }
        void bind(OrderedGroupAdapter adapter,String id,long revision) {
            this.adapter=adapter;this.id=id;this.revision=revision;setEnabled(true);
        }
        void clear() {
            adapter=null;id=null;revision=-1;setEnabled(false);
        }
    }
}
