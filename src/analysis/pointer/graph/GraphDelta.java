package analysis.pointer.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import util.OrderedPair;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Represents a delta (i.e., a change set) for a PointsToGraph. This is used to both represent the changes that an
 * operation made to a PointsToGraph, and also to allow more efficient processing of statements, which can focus just on
 * the changes to the graph since last time they were processed.
 */
public class GraphDelta {
    private final PointsToGraph g;
    private final Map<PointsToGraphNode, Set<InstanceKey>> newBase;
    private final Map<PointsToGraphNode, Set<OrderedPair<PointsToGraphNode, TypeFilter>>> newIsSupersetOfRelations;

    private boolean performedDomainClosure = false;

    public GraphDelta(PointsToGraph g) {
        this.g = g;
        newBase = new LinkedHashMap<>();
        newIsSupersetOfRelations = new LinkedHashMap<>();
    }

    private Set<InstanceKey> getOrCreateBaseSet(PointsToGraphNode src) {
        Set<InstanceKey> s = newBase.get(src);
        if (s == null) {
            s = new LinkedHashSet<>();
            newBase.put(src, s);
        }
        return s;
    }

    private Set<OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSupersetSet(
            PointsToGraphNode src) {
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> s =
                newIsSupersetOfRelations.get(src);
        if (s == null) {
            s = new LinkedHashSet<>();
            newIsSupersetOfRelations.put(src, s);
        }
        return s;
    }

    public void addBase(PointsToGraphNode src, InstanceKey trg) {
        if (performedDomainClosure) {
            throw new IllegalStateException("Already closed domains");
        }
        getOrCreateBaseSet(src).add(trg);
    }

    public void addCopyEdges(PointsToGraphNode source, TypeFilter filter,
            PointsToGraphNode target) {
        if (performedDomainClosure) {
            throw new IllegalStateException("Already closed domains");
        }
        getOrCreateSupersetSet(target).add(new OrderedPair<>(source, filter));
    }

    public Iterator<PointsToGraphNode> domainIterator() {
        if (!performedDomainClosure) {
            performDomainClosure();
        }

        return new PointsToGraph.ComposedIterators<PointsToGraphNode>(newBase.keySet()
                                                                             .iterator(),
                                                                      newIsSupersetOfRelations.keySet()
                                                                                              .iterator());
    }

    private void performDomainClosure() {
        performedDomainClosure = true;

        // handle the base elements
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> visited =
                new HashSet<>();
        Set<PointsToGraphNode> baseCases =
                new LinkedHashSet<>(newBase.keySet());
        for (PointsToGraphNode b : baseCases) {
            visited.add(new OrderedPair<>(b, (TypeFilter) null));
        }
        for (PointsToGraphNode b : baseCases) {
            addBaseForChildrenOf(b, newBase.get(b), visited);
        }

        // handle the copy edges
        visited = new HashSet<>();
        Set<PointsToGraphNode> supersetCases =
                new LinkedHashSet<>(newIsSupersetOfRelations.keySet());
        for (PointsToGraphNode b : supersetCases) {
            visited.add(new OrderedPair<>(b, (TypeFilter) null));
        }
        for (PointsToGraphNode b : supersetCases) {
            addSuperSetForChildrenOf(b,
                                     newIsSupersetOfRelations.get(b),
                                     visited);
        }
    }

    /*
     * Make the immediate supersets of b point to s (filtering appropriately)
     */
    private void addBaseForChildrenOf(PointsToGraphNode b, Set<InstanceKey> s,
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> visited) {
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter =
                g.immediateSuperSetsOf(b);
        while (iter.hasNext()) {
            OrderedPair<PointsToGraphNode, TypeFilter> child = iter.next();
            if (visited.contains(child)
                    || child.snd() != null
                    && visited.contains(new OrderedPair<>(child.fst(),
                                                          (TypeFilter) null))) {
                // already handled child!
                continue;
            }

            visited.add(child);

            PointsToGraphNode childNode = child.fst();
            TypeFilter filter = child.snd();
            // use filter to filter the elements.
            Set<InstanceKey> childS =
                    filter == null ? s : new PointsToGraph.FilteredSet(s,
                                                                       filter);

            Set<InstanceKey> baseForChild = getOrCreateBaseSet(childNode);
            if (baseForChild.addAll(childS)) {
                // we actually added something, so recurse.
                addBaseForChildrenOf(child.fst(), childS, visited);
            }
        }
    }

    /*
     * Add the "children" of b to the supersetof relation, using filter to filter the sets.
     */
    private void addSuperSetForChildrenOf(PointsToGraphNode b,
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> set,
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> visited) {

        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter =
                g.immediateSuperSetsOf(b);
        while (iter.hasNext()) {
            OrderedPair<PointsToGraphNode, TypeFilter> child = iter.next();
            if (visited.contains(child)
                    || child.snd() != null
                    && visited.contains(new OrderedPair<>(child.fst(),
                                                          (TypeFilter) null))) {
                // already handled child!
                continue;
            }

            visited.add(child);
            TypeFilter filter = child.snd();

            Set<OrderedPair<PointsToGraphNode, TypeFilter>> newSet =
                    new LinkedHashSet<>();
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> existingSet =
                    newIsSupersetOfRelations.get(child.fst());
            if (existingSet != null) {
                newSet.addAll(existingSet);
            }

            for (OrderedPair<PointsToGraphNode, TypeFilter> p : set) {
                // we need to add an appropriate version of p to newSet.
                if (filter == null || filter.equals(p.snd())) {
                    newSet.add(p);
                }
                else {
                    newSet.add(new OrderedPair<>(p.fst(),
                                                 TypeFilter.compose(p.snd(),
                                                                    filter)));
                }
            }

            newIsSupersetOfRelations.put(child.fst(), newSet);

            // now handle the recursive case
            // XXX Possible improvements: check that the set is non-empty before recursing.        
            addSuperSetForChildrenOf(child.fst(), newSet, visited);
        }
    }

    /**
     * Combine this GraphDelta with another graph delta. For efficiency, this method may be implemented imperatively.
     * 
     * @param d
     * @return
     */
    public GraphDelta combine(GraphDelta d) {
        if (d != null) {
            for (PointsToGraphNode src : d.newBase.keySet()) {
                getOrCreateBaseSet(src).addAll(d.newBase.get(src));
            }
            for (PointsToGraphNode src : d.newIsSupersetOfRelations.keySet()) {
                getOrCreateSupersetSet(src).addAll(d.newIsSupersetOfRelations.get(src));
            }
        }
        return this;
    }

    public boolean isEmpty() {
        return newBase.isEmpty() && newIsSupersetOfRelations.isEmpty();
    }

    @Override
    public String toString() {
        return "GraphDelta [newBase=" + newBase + ", newIsSupersetOfRelations="
                + newIsSupersetOfRelations + "]";
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        if (!performedDomainClosure) {
            throw new IllegalStateException("Have not yet closed domains");
        }
        return new DeltaPointsToIterator(n);
    }

    public class DeltaPointsToIterator implements Iterator<InstanceKey> {
        private Iterator<InstanceKey> currentIterator;
        private Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> newSubsets;
        private Set<OrderedPair<PointsToGraphNode, TypeFilter>> alreadyVisited =
                new HashSet<>();

        public DeltaPointsToIterator(PointsToGraphNode n) {
            alreadyVisited.add(new OrderedPair<>(n, (TypeFilter) null));
            if (newBase.containsKey(n)) {
                currentIterator = newBase.get(n).iterator();
            }
            else {
                currentIterator = Collections.emptyIterator();
            }
            if (newIsSupersetOfRelations.containsKey(n)) {
                newSubsets = newIsSupersetOfRelations.get(n).iterator();
            }
            else {
                newSubsets = Collections.emptyIterator();
            }
        }

        @Override
        public boolean hasNext() {
            while (true) {
                if (currentIterator.hasNext()) {
                    return true;
                }
                if (!newSubsets.hasNext()) {
                    // no more subsets of n to examine.
                    return false;
                }
                OrderedPair<PointsToGraphNode, TypeFilter> s =
                        newSubsets.next();
                currentIterator =
                        new PointsToGraph.PointsToIterator(g,
                                                           s.fst(),
                                                           s.snd(),
                                                           alreadyVisited);
                alreadyVisited.add(s);
            }
        }

        @Override
        public InstanceKey next() {
            if (hasNext()) {
                return currentIterator.next();
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
