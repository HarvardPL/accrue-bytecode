package analysis.pointer.graph;

import java.util.Collections;
import java.util.Set;

import util.intmap.ConcurrentIntMap;
import util.intmap.SimpleConcurrentIntMap;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;

import com.ibm.wala.util.intset.IntIterator;

/**
 * Representation of a relation on ints.
 */
public abstract class AnnotatedIntRelation<T> {
    private final ConcurrentIntMap<ConcurrentIntMap<T>> forwardReln = new SimpleConcurrentIntMap<>();
    private final ConcurrentIntMap<ConcurrentIntMap<T>> backReln = new SimpleConcurrentIntMap<>();

    public boolean addAnnotation(int from, int to, T annotation) {
        boolean changed = createOrMergeAnnotation(from, true, to, annotation);
        createOrMergeAnnotation(to, false, from, annotation);
        return changed;
    }

    private boolean createOrMergeAnnotation(int a, boolean forward, int b, T annotation) {
        ConcurrentIntMap<T> m = getOrCreateMap(a, forward);
        boolean changed = false;
        T s = m.get(b);
        if (s == null) {
            changed = true;
            s = createInitialAnnotation();
            if (s == null) {
                s = annotation;
            }
            T existing = m.putIfAbsent(b, s);
            if (existing != null) {
                s = existing;
            }
        }
        if (s != annotation) {
            return merge(s, annotation);
        }
        return changed;
    }

    /**
     * Create an initial T
     * @return
     */
    protected abstract T createInitialAnnotation();

    /**
     * Imperatively update existing to incorporate annotation. Return true if a change was made.
     * @param existing
     * @param annotation
     * @return
     */
    protected abstract boolean merge(T existing, T annotation);


    private ConcurrentIntMap<T> getOrCreateMap(int n, boolean forward) {
        ConcurrentIntMap<ConcurrentIntMap<T>> m = forward ? this.forwardReln : this.backReln;

        ConcurrentIntMap<T> s = m.get(n);
        if (s == null) {
            s = new SimpleConcurrentIntMap<>();
            ConcurrentIntMap<T> existing = m.putIfAbsent(n, s);
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
    public ConcurrentIntMap<T> forward(int n) {
        return getOrCreateMap(n, true);
    }

    /**
     * Given n, return the set { a | (a,n) \in R } where R is this relation
     *
     * @return
     */
    public ConcurrentIntMap<T> backward(int n) {
        return getOrCreateMap(n, false);
    }

    //    /**
    //     * Replace n with rep. That is, update the relation R to R' so that if (n, a, t) \in R then (rep, a, t') \in R'
    //     * where t \subseteq t' and if (a,n,t)\in R then (a, rep,t') \in R' where t \subseteq t'.
    //     *
    //     * Note that this operation will not add reflexive edges.
    //     *
    //     * @param n
    //     * @param rep
    //     */
    //    public void replace(int n, int rep) {
    //        // for every (n, b, T), add T to (rep, b)
    //        // and remove (n,b) and (b.n)
    //        ConcurrentIntMap<Set<T>> nForward = forward(n);
    //        IntIterator ii = nForward.keyIterator();
    //        while (ii.hasNext()) {
    //            int b = ii.next();
    //            Set<T> annotations = nForward.get(b);
    //
    //            if (rep != b) {
    //                this.addAll(rep, b, annotations);
    //            }
    //            Set<T> bBackward = this.getOrCreateSet(b, false, rep);
    //            bBackward.remove(n);
    //        }
    //        forwardReln.remove(n);
    //
    //
    //        // for every (a, n, T), att T to (a, rep)
    //        ConcurrentIntMap<Set<T>> nBackward = backward(n);
    //        ii = nBackward.keyIterator();
    //        while (ii.hasNext()) {
    //            int a = ii.next();
    //            Set<T> annotations = nBackward.get(a);
    //            if (rep != a) {
    //                this.addAll(rep, a, annotations);
    //            }
    //            Set<T> aForward = this.getOrCreateSet(a, true, rep);
    //            aForward.remove(n);
    //        }
    //        backReln.remove(n);
    //    }

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

    public static class SetAnnotatedIntRelation<T> extends AnnotatedIntRelation<Set<T>> {

        @Override
        protected Set<T> createInitialAnnotation() {
            return PointsToAnalysisMultiThreaded.makeConcurrentSet();
        }

        @Override
        protected boolean merge(Set<T> existing, Set<T> annotation) {
            return existing.addAll(annotation);
        }

        public boolean add(int from, int to, T annotation) {
            return this.addAnnotation(from, to, Collections.singleton(annotation));
        }
    }
}
