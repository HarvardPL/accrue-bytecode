package analysis.pointer.graph;

import java.util.NoSuchElementException;
import java.util.Set;

import util.intmap.ConcurrentIntMap;
import util.intmap.IntMap;
import analysis.AnalysisUtil;
import analysis.pointer.graph.RelevantNodesIncremental.RelevantNodesQuery;

import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.MutableIntSet;

/**
 * Relevant node dependencies resulting from traversing the call graph along only callee edges from a root call graph
 * node
 */
public class CalleeDependency {

    /**
     * Cache mapping root call graph nodes to the dependencies for that node
     */
    private static final ConcurrentIntMap<CalleeDependency> cache = AnalysisUtil.createConcurrentIntMap();
    /**
     * Dependencies for all callees of the root
     */
    private final IntMap<CalleeDependency> calleeDependencies = AnalysisUtil.createConcurrentIntMap();
    /**
     * Call graph nodes whose dependencencies contain the dependencies of this root
     */
    private final MutableIntSet parents = AnalysisUtil.createConcurrentIntSet();
    /**
     * Queries that depend on the callees of this root node, if these change then the query need to be recomputed
     */
    private final Set<RelevantNodesQuery> queryDependencies = AnalysisUtil.createConcurrentSet();
    /**
     * Root node this represents the dependencies for
     */
    private final int root;

    /**
     * Create a new dependency map
     *
     * @param rootCGNode call graph node at the root of the callee graph
     */
    private CalleeDependency(int rootCGNode) {
        // Do not call use getOrCreate
        this.root = rootCGNode;
    }

    /**
     * Dependencies for callees starting at the given call graph node
     *
     * @param cgNode call graph node at the root
     * @return dependencies for callees (transitively) from the given call graph node
     */
    static CalleeDependency getOrCreate(int cgNode) {
        CalleeDependency m = cache.get(cgNode);
        if (m == null) {
            m = new CalleeDependency(cgNode);
            CalleeDependency existing = cache.putIfAbsent(cgNode, m);
            if (existing != null) {
                // Someone beat us to it
                return existing;
            }
        }
        return m;
    }

    /**
     * Get the nodes that depend on the given node
     *
     * @param cgNode node to get the dependencies for
     * @return iterator through call graph node dependencies
     */
    IntIterator getDependencies(int cgNode) {
        return this.new DependencyIterator(cgNode);
    }

    /**
     * Add a callee of the root node
     *
     * @param childCallee call graph node for the callee
     * @return true if the callees changed
     */
    boolean addCallee(int childCallee) {
        CalleeDependency childMap = getOrCreate(childCallee);
        childMap.addParent(this.root);
        return calleeDependencies.put(childCallee, getOrCreate(childCallee)) != null;
    }

    /**
     * Add a parent call graph node. This is used to track dependencies
     *
     * @param parentCGNode node that is a caller of the root node
     * @return true if the parent was a new addition
     */
    private boolean addParent(int parentCGNode) {
        return parents.add(parentCGNode);
    }

    /**
     * Add a query that dependends on the dependencies this class represents
     *
     * @param dep query that depends on these dependencies
     * @return true if this is a new dependency
     */
    boolean addQueryDependency(RelevantNodesQuery dep) {
        return queryDependencies.add(dep);
    }

    /**
     * Get all relevant node queries that depend on these callee dependencies
     *
     * @return the set of dependent queries
     */
    Set<RelevantNodesQuery> getQueryDependencies() {
        return queryDependencies;
    }

    /**
     * Iterator through all call graph nodes that depend on the dependee
     */
    private class DependencyIterator implements IntIterator {

        /**
         * Queued up next element
         */
        private int next = -1;
        /**
         * Call graph node to find dependencies for
         */
        private final int dependee;
        /**
         * Did we already check the local dependency map
         */
        private boolean checkedLocally;
        @SuppressWarnings("synthetic-access")
        /**
         * Iterator through the direct callees of the root
         */
        private IntIterator callees = calleeDependencies.keyIterator();
        /**
         * Iterator for the callee that is currently being iterated through
         */
        private DependencyIterator lastCalleeIter = null;


        /**
         * Create a new iterator through all the dependencies of the given call graph node
         *
         * @param dependee the call graph node to get the dependencies for
         */
        public DependencyIterator(int dependee) {
            this.dependee = dependee;
        }

        @SuppressWarnings("synthetic-access")
        @Override
        public boolean hasNext() {
            // XXX what should we do with a cyclic call graph = cyclic dependencies

            if (next >= 0) {
                // Already computed the next element
                return true;
            }

            if (!checkedLocally && calleeDependencies.containsKey(dependee)) {
                // Use if the dependee is a direct callee then the root is a dependency
                next = root;
                checkedLocally = true;
                return true;
            }

            if (lastCalleeIter != null && lastCalleeIter.hasNext()) {
                // If there is an active child iterator and it has an element then use that
                next = lastCalleeIter.next();
                return true;
            }

            if (callees.hasNext()) {
                // Try the next child iterator
                lastCalleeIter = calleeDependencies.get(callees.next()).new DependencyIterator(dependee);
                return hasNext();
            }

            // No callees left
            next = -1;
            return false;
        }

        @Override
        public int next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return next;
        }

    }
}
