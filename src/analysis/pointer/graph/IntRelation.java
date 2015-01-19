package analysis.pointer.graph;

import util.intmap.ConcurrentIntMap;
import analysis.AnalysisUtil;

import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Representation of a relation on ints.
 */
public final class IntRelation {
    private final ConcurrentIntMap<MutableIntSet> forwardReln = AnalysisUtil.makeConcurrentIntMap();
    private final ConcurrentIntMap<MutableIntSet> backReln = AnalysisUtil.makeConcurrentIntMap();

    boolean add(int from, int to) {
        boolean changed = getOrCreateSet(from, true).add(to);
        getOrCreateSet(to, false).add(from);
        return changed;
    }

    private MutableIntSet getOrCreateSet(int n, boolean forward) {
        ConcurrentIntMap<MutableIntSet> m = forward ? this.forwardReln : this.backReln;

        MutableIntSet s = m.get(n);
        if (s == null) {
            s = AnalysisUtil.makeConcurrentIntSet();
            MutableIntSet existing = m.putIfAbsent(n, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s;
    }

    /**
     * Given n, return the set { b | (n,b) \in R } where R is this relation
     *
     * @return
     */
    public IntSet forward(int n) {
        IntSet s = forwardReln.get(n);
        if (s == null) {
            return EmptyIntSet.instance;
        }
        return s;
    }

    /**
     * Given n, return the set { a | (a,n) \in R } where R is this relation
     *
     * @return
     */
    public IntSet backward(int n) {
        IntSet s = backReln.get(n);
        if (s == null) {
            return EmptyIntSet.instance;
        }
        return s;
    }

    /**
     * Return the domain, i.e., the set {a | (a,b) \in R }
     */
    public IntIterator domain() {
        return this.forwardReln.keyIterator();
    }

    /**
     * Return the codomain, i.e., the set {b | (a,b) \in R }
     */
    public IntIterator codomain() {
        return this.backReln.keyIterator();
    }

}
