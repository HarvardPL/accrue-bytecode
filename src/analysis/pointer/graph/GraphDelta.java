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
        this.newBase = new LinkedHashMap<>();
        this.newIsSupersetOfRelations = new LinkedHashMap<>();
    }

    private Set<InstanceKey> getOrCreateBaseSet(PointsToGraphNode src) {
        Set<InstanceKey> s = newBase.get(src);
        if (s == null) {
            s = new LinkedHashSet<>();
            newBase.put(src, s);
        }
        return s;
    }

    private Set<OrderedPair<PointsToGraphNode, TypeFilter>> getOrCreateSupersetSet(PointsToGraphNode src) {
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> s = newIsSupersetOfRelations.get(src);
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
        this.getOrCreateBaseSet(src).add(trg);
    }

    public void addCopyEdges(PointsToGraphNode source, TypeFilter filter, PointsToGraphNode target) {
        if (performedDomainClosure) {
            throw new IllegalStateException("Already closed domains");
        }
        this.getOrCreateSupersetSet(target).add(new OrderedPair<>(source, filter));
    }

    public Set<PointsToGraphNode> domain() {
        if (!performedDomainClosure) {
            performDomainClosure();
        }

        Set<PointsToGraphNode> domain = new LinkedHashSet<>();

        domain.addAll(newBase.keySet());
        domain.addAll(newIsSupersetOfRelations.keySet());
        // // this is a tricky set to compute. It includes the domain of
        // // newBase and newSupersetRelations, and also anything that is
        // // a superset of those. (And we could be smarter, but lets try this).
        // Set<PointsToGraphNode> reachable = new LinkedHashSet<>();
        // Set<PointsToGraphNode> toVisitChildrenOf = new LinkedHashSet<>();
        //
        // reachable.addAll(newSupersetRelations.keySet());
        // toVisitChildrenOf.addAll(newSupersetRelations.keySet());
        // reachable.addAll(newBase.keySet());
        // toVisitChildrenOf.addAll(newBase.keySet());
        // while (!toVisitChildrenOf.isEmpty()) {
        // Iterator<PointsToGraphNode> iter = toVisitChildrenOf.iterator();
        // PointsToGraphNode n = iter.next();
        // iter.remove();
        // for (OrderedPair<PointsToGraphNode, TypeFilter> m : this.g.superSetsOf(n)) {
        // if (reachable.add(m.fst())) {
        // // we hadn't seen m before.
        // toVisitChildrenOf.add(m.fst());
        // }
        // }
        // }

        return domain;
    }

    private void performDomainClosure() {
        performedDomainClosure = true;

        int beforeBaseSize = this.newBase.keySet().size();
        int beforeSupSize = this.newIsSupersetOfRelations.keySet().size();
        System.err.println("\n\nBefore closure: " + this);
        // handle the base elements
        Set<OrderedPair<PointsToGraphNode, TypeFilter>> visited = new HashSet<>();
        Set<PointsToGraphNode> baseCases = new LinkedHashSet<>(this.newBase.keySet());
        for (PointsToGraphNode b : baseCases) {
            visited.add(new OrderedPair<>(b, (TypeFilter) null));
        }
        for (PointsToGraphNode b : baseCases) {
            addBaseForChildrenOf(b, visited);
        }

        // handle the copy edges
        visited = new HashSet<>();
        Set<PointsToGraphNode> supersetCases = new LinkedHashSet<>(this.newIsSupersetOfRelations.keySet());
        for (PointsToGraphNode b : supersetCases) {
            visited.add(new OrderedPair<>(b, (TypeFilter) null));
        }
        for (PointsToGraphNode b : supersetCases) {
            addSuperSetForChildrenOf(b, null, visited);
        }

        System.err.println("After closure: " + this);
        
        if (beforeBaseSize != this.newBase.keySet().size() ||  beforeSupSize != this.newIsSupersetOfRelations.keySet().size()) {
            System.err.println((beforeBaseSize != this.newBase.keySet().size()) ? "CASE A" : "CASE B");
            System.err.println(" ****** " + beforeBaseSize + " =?= " + this.newBase.keySet().size() + " and "
                                            + beforeSupSize + " =?= " + this.newIsSupersetOfRelations.keySet().size());
        }


    }

    /*
     * Add the "children" of b to the base set.
     */
    private void addBaseForChildrenOf(PointsToGraphNode b, 
                                    Set<OrderedPair<PointsToGraphNode, TypeFilter>> visited) {
        for (OrderedPair<PointsToGraphNode, TypeFilter> child : this.g.superSetsOf(b)) {
            if (visited.contains(child)
                                            || (child.snd() != null && visited.contains(new OrderedPair<>(child.fst(),
                                                                            (TypeFilter) null)))) {
                // already handled child!
                continue;
            }

            visited.add(child);

            TypeFilter filter = child.snd();
            // use filter to filter the elements.
            Set<InstanceKey> newSet = new LinkedHashSet<>();
            Set<InstanceKey> existingSet = this.newBase.get(child.fst());
            if (existingSet != null) {
                newSet.addAll(existingSet);
            }
            if (filter == null) {
                newSet.addAll(this.newBase.get(b));                
            }
            else {
                newSet.addAll(new PointsToGraph.FilteredSet(this.newBase.get(b), filter));                                
            }
            this.newBase.put(child.fst(), newSet);

            // now handle the recursive case
            addBaseForChildrenOf(child.fst(), visited);
        }
    }

    /*
     * Add the "children" of b to the supersetof relation, using filter to filter the sets.
     */
    private void addSuperSetForChildrenOf(PointsToGraphNode b, TypeFilter filter,
                                    Set<OrderedPair<PointsToGraphNode, TypeFilter>> visited) {
        for (OrderedPair<PointsToGraphNode, TypeFilter> child : this.g.superSetsOf(b)) {

            // this.newIsSupersetOfRelations
            TypeFilter newFilter = TypeFilter.compose(filter, child.snd());

            OrderedPair<PointsToGraphNode, TypeFilter> newPair = new OrderedPair<>(child.fst(), newFilter);
            if (visited.contains(newPair)
                                            || (newPair.snd() != null && visited.contains(new OrderedPair<>(
                                                                            child.fst(),
                                                                            (TypeFilter) null)))) {
                // already handled child!
                continue;
            }

            visited.add(newPair);

            Set<OrderedPair<PointsToGraphNode, TypeFilter>> newSet = new LinkedHashSet<>();
            Set<OrderedPair<PointsToGraphNode, TypeFilter>> existingSet = this.newIsSupersetOfRelations
                                            .get(child.fst());
            if (existingSet != null) {
                newSet.addAll(existingSet);
            }

            for (OrderedPair<PointsToGraphNode, TypeFilter> p : this.newIsSupersetOfRelations.get(b)) {
                // we need to add an appropriate version of p to newSet.
                if (newFilter == null || newFilter.equals(p.snd())) {
                    newSet.add(p);
                }
                else {
                    newSet.add(new OrderedPair<>(p.fst(), TypeFilter.compose(p.snd(), newFilter)));
                }
            }

            this.newIsSupersetOfRelations.put(child.fst(), newSet);

            // now handle the recursive case
            addSuperSetForChildrenOf(child.fst(), newFilter, visited);
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
                this.getOrCreateBaseSet(src).addAll(d.newBase.get(src));
            }
            for (PointsToGraphNode src : d.newIsSupersetOfRelations.keySet()) {
                this.getOrCreateSupersetSet(src).addAll(d.newIsSupersetOfRelations.get(src));
            }
        }
        return this;
    }

    public boolean isEmpty() {
        return newBase.isEmpty() && newIsSupersetOfRelations.isEmpty();
    }

    @Override
    public String toString() {
        return "GraphDelta [newBase=" + newBase + ", newIsSupersetOfRelations=" + newIsSupersetOfRelations + "]";
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        if (!performedDomainClosure) {
            throw new IllegalStateException("Have not yet closed domains");
        }
        return new DeltaPointsToIterator(n);
    }

    public class DeltaPointsToIterator implements Iterator<InstanceKey> {
        private Iterator<InstanceKey> currentIterator;
        private Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> newSupersets;
        private Set<OrderedPair<PointsToGraphNode, TypeFilter>> alreadyVisited = new HashSet<>();

        public DeltaPointsToIterator(PointsToGraphNode n) {
            this.alreadyVisited.add(new OrderedPair<>(n, (TypeFilter) null));
            if (GraphDelta.this.newBase.containsKey(n)) {
                this.currentIterator = GraphDelta.this.newBase.get(n).iterator();
            }
            else {
                this.currentIterator = Collections.emptyIterator();
            }
            if (GraphDelta.this.newIsSupersetOfRelations.containsKey(n)) {
                this.newSupersets = GraphDelta.this.newIsSupersetOfRelations.get(n).iterator();
            }
            else {
                this.newSupersets = Collections.emptyIterator();
            }
        }

        @Override
        public boolean hasNext() {
            while (true) {
                if (currentIterator.hasNext()) {
                    return true;
                }
                if (!newSupersets.hasNext()) {
                    // no more subsets of n to examine.
                    return false;
                }
                OrderedPair<PointsToGraphNode, TypeFilter> s = newSupersets.next();
                currentIterator = new PointsToGraph.PointsToIterator(g, s.fst(), s.snd(), alreadyVisited);
                alreadyVisited.add(s);
            }
        }

        @Override
        public InstanceKey next() {
            if (this.hasNext()) {
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
