package analysis.pointer.analyses;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.CallSiteLabel;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * A HeapAbstractionFactory is responsible for providing an appropriate abstraction of the heap for pointer analysis. It
 * is based on the factoring of pointer analysis in the paper "Pick Your Contexts Well: Understanding
 * Object-Sensitivity" by Smaragdakis, Bravenboer, and Lhotak, POPL 2011.
 * <p>
 * Context is the calling context methods are analyzed in
 * <p>
 * InstanceKey the heap context for allocated objects
 */
public abstract class HeapAbstractionFactory {

    /**
     * Create a new abstract object created at a particular allocation site in a particular code context. The results of
     * this call must be memoized and two equivalent InstanceKeys must be pointer-equivalent
     * 
     * @param allocationSite
     *            Representation of the program counter for the allocation site
     * @param context
     *            Code context at the allocation site
     * 
     * @return Abstract heap object (heap context)
     */
    public abstract InstanceKey record(AllocSiteNode allocationSite, Context context);

    /**
     * Create a new code context for a new callee. The results of this call must be memoized and two equivalent contexts
     * must be pointer-equivalent.
     * 
     * @param callSite
     *            call site we are creating a node for
     * @param receiver
     *            Abstract object (heap context) representing the receiver
     * @param callerContext
     *            Code context in the method caller
     * @return code context for the callee
     */
    public abstract Context merge(CallSiteLabel callSite, InstanceKey receiver, Context callerContext);

    /**
     * Return the initial Context, i.e., to analyze the root method.
     */
    public abstract Context initialContext();

    @Override
    public abstract String toString();

    /**********
     * Memoization
     **********/
    private static final ConcurrentHashMap<Wrapper<InstanceKey>, InstanceKey> instanceKeymemo = createConcurrentHashMap();
    private static final ConcurrentHashMap<Wrapper<Context>, Context> contextMemo = createConcurrentHashMap();

    /**
     * Memoize the given context. If all the <code>memoKeys</code> are .equals to those passed in for an existing
     * Context and the contexts are of the same type then the previous context will be returned, otherwise the context
     * that was passed in will be recorded and returned.
     * 
     * @param c
     *            context to memoize
     * @param memoKeys
     *            anything on which determines the semantic meaning of the context
     * @return The existing context for the given <code>memoKeys</code> if any, otherwise, <code>c</code>
     */
    @SuppressWarnings("unchecked")
    protected static <CC extends Context> CC memoize(CC c, Object... memoKeys) {
        Wrapper<Context> w = new Wrapper<Context>(c, memoKeys);
        CC memoized = (CC) contextMemo.get(w);
        if (memoized == null) {
            memoized = (CC) contextMemo.putIfAbsent(w, c);
            if (memoized == null) {
                // the key wasn't in the map and c is now in the map
                memoized = c;
            }
        }

        return memoized;
    }

    /**
     * Memoize the given heap context (instance key). If all the <code>memoKeys</code> are .equals to those passed in
     * for an existing InstanceKey and the InstanceKeys are of the same type then the previous instance key will be
     * returned, otherwise the instance key that was passed in will be recorded and returned.
     * 
     * @param ik
     *            instance key (heap context) to memoize
     * @param memoKeys
     *            anything on which determines the semantic meaning of the instance key
     * @return The existing instance key for the given <code>memoKeys</code> if any, otherwise, <code>ik</code>
     */
    @SuppressWarnings("unchecked")
    protected static <HC extends InstanceKey> HC memoize(HC ik, Object... memoKeys) {
        Wrapper<InstanceKey> w = new Wrapper<InstanceKey>(ik, memoKeys);
        HC memoized = (HC) instanceKeymemo.get(w);
        if (memoized == null) {
            memoized = (HC) instanceKeymemo.putIfAbsent(w, ik);
            if (memoized == null) {
                // the key wasn't in the map and ik is now in the map
                memoized = ik;
            }
        }
        return memoized;
    }

    private static <W, T> ConcurrentHashMap<W, T> createConcurrentHashMap() {
        return new ConcurrentHashMap<>(16, 0.75f, Runtime.getRuntime().availableProcessors());
    }



    /**
     * A wrapper for contexts/instanceKeys and memoization keys to be used as the key in a memoization map for contexts.
     * Note that although the Wrapper contains a T c, that is not directly used in the equality comparison, except (1)
     * to check the runtime class of the T, and (2) to speed up the checking of equality, in the case that the two Ts
     * happen to be pointer-equal.
     */
    private static class Wrapper<T> {

        private final T c;
        private final Object[] memoKeys;

        /**
         * Wrapper around the context and any keys determining semantic equality
         * 
         * @param c
         *            context or InstanceKey (must be non-null)
         * @param memoKeys
         *            keys determining semantic equality (must be non-null and non-empty)
         */
        public Wrapper(T c, Object[] memoKeys) {
            assert c != null;
            assert memoKeys != null && memoKeys.length > 0;
            this.c = c;
            this.memoKeys = memoKeys;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(memoKeys) ^ c.getClass().hashCode();
        }

        @Override
        public boolean equals(Object obj) {

            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            @SuppressWarnings("unchecked")
            Wrapper<T> other = (Wrapper<T>) obj;
            if (c == other.c) {
                assert Arrays.equals(memoKeys, other.memoKeys);
                // If the instance keys are the same object then we do not need to check the keys, they must be the same
                // (unless there was a bug)
                return true;
            }

            if (c.getClass() != other.c.getClass()) {
                // Comparing two different types of context
                return false;
            }

            return Arrays.equals(memoKeys, other.memoKeys);
        }

    }

}
