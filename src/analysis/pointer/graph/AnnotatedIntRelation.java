package analysis.pointer.graph;

import java.util.Collections;
import java.util.Set;

import util.intmap.ConcurrentIntMap;
import analysis.AnalysisUtil;

import com.ibm.wala.util.intset.IntIterator;

/**
 * Representation of a relation on ints.
 */
public abstract class AnnotatedIntRelation<T> {
    private final ConcurrentIntMap<ConcurrentIntMap<T>> forwardReln = AnalysisUtil.makeConcurrentIntMap();
    private final ConcurrentIntMap<ConcurrentIntMap<T>> backReln = AnalysisUtil.makeConcurrentIntMap();

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
            s = AnalysisUtil.makeConcurrentIntMap();
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

    public static final class SetAnnotatedIntRelation<T> extends AnnotatedIntRelation<Set<T>> {

        @Override
        protected Set<T> createInitialAnnotation() {
            return AnalysisUtil.createConcurrentSet();
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
