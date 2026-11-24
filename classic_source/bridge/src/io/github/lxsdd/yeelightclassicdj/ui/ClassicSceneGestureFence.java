package io.github.lxsdd.yeelightclassicdj.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** One original SceneFragment action, one host lease and one DJ state/order.
 * A delayed UI callback may not write after another host changed the scene,
 * a preference was reordered, or the original fragment was replaced.
 */
final class ClassicSceneGestureFence {
    static final class Receipt {
        private final Object host;
        private final long revision;
        private final List<String> order;
        private final ClassicSceneUiState.Selection favorite;
        private Receipt(Object host,long revision,List<String> order,
                        ClassicSceneUiState.Selection favorite) {
            this.host=host;
            this.revision=revision;
            this.order=new ArrayList<>(order);
            this.favorite=favorite; // immutable Selection
        }
        boolean current(Object host,long revision,List<String> order) {
            return host!=null && this.host==host && this.revision==revision &&
                order!=null && this.order.equals(order);
        }
        boolean sameFavorite(ClassicSceneUiState.Selection next) {
            return same(this.favorite,next);
        }
    }
    static Receipt capture(Object host,long revision,List<String> order,
                           ClassicSceneUiState.Selection favorite) {
        if(host==null || revision<0 || order==null ||
           order.size()!=ClassicSceneUiState.SCENES.length ||
           !new HashSet<>(order).equals(new HashSet<>(
               Arrays.asList(ClassicSceneUiState.SCENES)))) return null;
        return new Receipt(host,revision,order,favorite);
    }
    private static boolean same(ClassicSceneUiState.Selection a,
                                ClassicSceneUiState.Selection b) {
        if(a==b)return true;
        if(a==null || b==null)return false;
        return a.scene==b.scene && a.speedIndex==b.speedIndex &&
            a.reverse==b.reverse && a.trailIndex==b.trailIndex &&
            a.rangeIndex==b.rangeIndex && a.gainIndex==b.gainIndex &&
            a.beatPulse==b.beatPulse && a.pulseTempoIndex==b.pulseTempoIndex &&
            a.pulseShapeIndex==b.pulseShapeIndex && a.colorIndex==b.colorIndex &&
            a.paletteIndex==b.paletteIndex;
    }
    private ClassicSceneGestureFence(){}
}
