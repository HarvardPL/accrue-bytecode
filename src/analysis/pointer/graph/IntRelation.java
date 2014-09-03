package analysis.pointer.graph;

import util.intmap.ConcurrentIntMap;
import util.intmap.SimpleConcurrentIntMap;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;

import com.ibm.wala.util.intset.EmptyIntSet;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Representation of a relation on ints.
 */
public class IntRelation {
    private final ConcurrentIntMap<MutableIntSet> forwardReln = new SimpleConcurrentIntMap<>();
    private final ConcurrentIntMap<MutableIntSet> backReln = new SimpleConcurrentIntMap<>();

    boolean add(int from, int to) {
        boolean changed = getOrCreateSet(from, true).add(to);
        getOrCreateSet(to, false).add(from);
        return changed;
    }

    private MutableIntSet getOrCreateSet(int n, boolean forward) {
        ConcurrentIntMap<MutableIntSet> m = forward ? this.forwardReln : this.backReln;

        MutableIntSet s = m.get(n);
        if (s == null) {
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
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
     * Replace n with rep. That is, update the relation R to R' so that if (n, a) \in R then (rep, a) \in R' and (a, n)
     * \in R then (a, rep) \in R'.
     * 
     * Note that this operation will not add reflexive edges.
     * 
     * @param n
     * @param rep
     */
    public void replace(int n, int rep) {
        // for every (n, b), replace it with (rep, b)
        MutableIntSet repForward = this.getOrCreateSet(rep, true);
        IntIterator ii = forward(n).intIterator();
        while (ii.hasNext()) {
            int b = ii.next();
            MutableIntSet bBackward = this.getOrCreateSet(b, false);
            if (rep != b) {
                repForward.add(b);
                bBackward.add(rep);
            }
            bBackward.remove(n);
        }
        forwardReln.remove(n);


        // for every (a, n), replace it with (a, rep)
        MutableIntSet repBackward = this.getOrCreateSet(rep, false);
        ii = backward(n).intIterator();
        while (ii.hasNext()) {
            int a = ii.next();
            MutableIntSet aForward = this.getOrCreateSet(a, true);
            if (rep != a) {
                repBackward.add(a);
                aForward.add(rep);
            }
            aForward.remove(n);
        }
        backReln.remove(n);
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
