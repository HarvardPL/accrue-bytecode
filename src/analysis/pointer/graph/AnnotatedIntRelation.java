package analysis.pointer.graph;

import java.util.Set;

import util.intmap.ConcurrentIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;

import com.ibm.wala.util.intset.IntIterator;

/**
 * Representation of a relation on ints.
 */
public class AnnotatedIntRelation<T> {
    private final ConcurrentIntMap<ConcurrentIntMap<Set<T>>> forwardReln = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
    private final ConcurrentIntMap<ConcurrentIntMap<Set<T>>> backReln = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    boolean add(int from, int to, T annotation) {
        boolean changed = getOrCreateSet(from, true, to).add(annotation);
        getOrCreateSet(to, false, from).add(annotation);
        return changed;
    }

    boolean addAll(int from, int to, Set<T> annotations) {
        boolean changed = getOrCreateSet(from, true, to).addAll(annotations);
        getOrCreateSet(to, false, from).addAll(annotations);
        return changed;
    }

    private Set<T> getOrCreateSet(int a, boolean forward, int b) {
        ConcurrentIntMap<Set<T>> m = getOrCreateMap(a, forward);

        Set<T> s = m.get(b);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<T> existing = m.putIfAbsent(b, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s;
    }

    private ConcurrentIntMap<Set<T>> getOrCreateMap(int n, boolean forward) {
        ConcurrentIntMap<ConcurrentIntMap<Set<T>>> m = forward ? this.forwardReln : this.backReln;

        ConcurrentIntMap<Set<T>> s = m.get(n);
        if (s == null) {
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
            ConcurrentIntMap<Set<T>> existing = m.putIfAbsent(n, s);
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
    public ConcurrentIntMap<Set<T>> forward(int n) {
        return getOrCreateMap(n, true);
    }

    /**
     * Given n, return the set { a | (a,n) \in R } where R is this relation
     *
     * @return
     */
    public ConcurrentIntMap<Set<T>> backward(int n) {
        return getOrCreateMap(n, false);
    }

    /**
     * Duplicate n's relationships for rep, since n is being collapsed in a cycle, with the new representative being
     * rep. That is, update the relation R to R' so that if (n, a, t) \in R then (rep, a, t') \in R' where t \subseteq
     * t' and if (a,n,t)\in R then (a, rep,t') \in R' where t \subseteq t'.
     *
     * Note that this operation will not add reflexive edges.
     *
     * @param n
     * @param rep
     */
    public void duplicate(int n, int rep) {
        // for every (n, b, T), add T to (rep, b)
        ConcurrentIntMap<Set<T>> nForward = forward(n);
        IntIterator ii = nForward.keyIterator();
        while (ii.hasNext()) {
            int b = ii.next();
            if (rep != b) {
                Set<T> annotations = nForward.get(b);
                this.addAll(rep, b, annotations);
            }
        }

        // for every (a, n, T), att T to (a, rep)
        ConcurrentIntMap<Set<T>> nBackward = backward(n);
        ii = nBackward.keyIterator();
        while (ii.hasNext()) {
            int a = ii.next();
            if (rep != a) {
                Set<T> annotations = nBackward.get(a);
                this.addAll(rep, a, annotations);
            }
        }
    }

    /**
     * Remove edges to n, since n is being collapsed in a cycle, with the new representative being rep. That is, update
     * the relation R to R' so that R' does not have any relations of the form (a,n,t).
     * 
     * Note that we leave edges from n, to ensure that multi-threading works correctly.
     * 
     * @param n
     * @param rep
     */
    public void removeEdgesTo(int n) {
        // for every (a, n, T), remove (a,n)
        ConcurrentIntMap<Set<T>> nBackward = backward(n);
        IntIterator ii = nBackward.keyIterator();
        while (ii.hasNext()) {
            int a = ii.next();
            ConcurrentIntMap<Set<T>> aForward = this.getOrCreateMap(a, true);
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
