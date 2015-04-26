package analysis.pointer.graph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import main.AccrueAnalysisMain;
import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import util.intmap.DenseIntMap;
import util.intmap.IntMap;
import util.intmap.ReadOnlyConcurrentIntMap;
import util.intmap.SparseIntMap;
import util.intset.EmptyIntSet;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.DependencyRecorder;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.collections.IntStack;
import com.ibm.wala.util.intset.IntIterator;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSparseIntSet;
import com.ibm.wala.util.intset.SparseIntSet;

/**
 * Graph mapping local variables (in a particular context) and fields to
 * abstract heap locations (representing zero or more actual heap locations)
 */
public final class PointsToGraph {

    public static final String ARRAY_CONTENTS = "[contents]";

    /* ***************************************************************************
    *
    * Fields for managing integer dictionaries.
    * That is, for efficiency we map InstanceKeys and PointsToGraphNode to ints, and
    * these fields help us manage those mappings.
    */
    /**
     * InstanceKey counter, for unique integers for InstanceKeys
     */
    private final AtomicInteger instanceKeyCounter = new AtomicInteger(0);
    /**
     * Dictionary for mapping ints to InstanceKeys.
     */
    private ConcurrentIntMap<InstanceKey> instanceKeyDictionary = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
    /**
     * Dictionary for mapping InstanceKeys to ints
     */
    private ConcurrentMap<InstanceKey, Integer> reverseInstanceKeyDictionary = new ConcurrentHashMap<>();

    /**
     * Dictionary to record the concrete type of instance keys.
     */
    private ConcurrentIntMap<IClass> concreteTypeDictionary = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * GraphNode counter, for unique integers for GraphNodes
     */
    private final AtomicInteger graphNodeCounter = new AtomicInteger(0);

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private ConcurrentMap<PointsToGraphNode, Integer> reverseGraphNodeDictionary = new ConcurrentHashMap<>();



    /* ***************************************************************************
    *
    * The Points To graph itself. The pointsTo map records the actual points to sets,
    * and the isUnfilteredSubsetOf and isFilteredSubsetOf relations record subset
    * relations between PointsToGraphNodes, in order to let us more efficiently
    * propagate changes to the graph.
    *
    * We use the map representative to record the "representative" node for nodes
    * that have been collapsed. Once node a has been collapsed to another node
    * (i.e., a \in domain(representative)), then any processing of statements
    * should not add to the points to set of a, but should instead add to the
    * points to set of the representative of a. To ensure that concurrency
    * is handled correctly with respect to cycle collapsing, all of the
    * subset relations for a are left intact (i.e., isUnfilteredSubsetOf, etc).
    *
    * Note also that it is possible that (a,b) \in representative and b \in domain(representative),
    * i.e., the representative of a collapsed node may itself get collapsed.
    */

    /**
     * The PointsTo sets.
     */
    private ConcurrentIntMap<MutableIntSet> pointsTo = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * if "a isUnfilteredSubsetOf b" then the points to set of a is always a subset of the points to set of b.
     */
    private IntRelation isUnfilteredSubsetOf = new IntRelation();

    /**
     * if "a isFilteredSubsetOf b with filter" then the filter(pointsTo(a)) is a subset of pointsTo(b).
     */
    private AnnotatedIntRelation<TypeFilter> isFilteredSubsetOf = new AnnotatedIntRelation<>();

    /**
     * Map from PointsToGraphNodes to PointsToGraphNodes, indicating which nodes have been collapsed (due to being in
     * cycles) and which node now represents them.
     */
    private final ConcurrentIntMap<Integer> immediateRepresentative = USE_CYCLE_COLLAPSING
            ? PointsToAnalysisMultiThreaded.<Integer> makeConcurrentIntMap() : null;

    /**
     * Map from PointsToGraphNodes to PointsToGraphNodes, indicating which nodes have been collapsed (due to being in
     * cycles) and which node now represents them.
     */
    private final ConcurrentIntMap<Integer> representative = USE_CYCLE_COLLAPSING
            ? PointsToAnalysisMultiThreaded.<Integer> makeConcurrentIntMap() : null;

    /**
     * This flag controls whether we try to use cycle collapsing. NOTE: Cycle collapsing is an experimental feature that
     * is not yet correct. There are a number of concurrency issues. It will work in a single threaded setting, but not
     * concurrently.
     */
    static final boolean USE_CYCLE_COLLAPSING = true;

    /* ***************************************************************************
    *
    * Reachable contexts and entry points, and call graph representations.
    *
    */

    /**
     * The contexts that a method may appear in.
     */
    private ConcurrentMap<IMethod, Set<Context>> reachableContexts = new ConcurrentHashMap<>();

    /**
     * The classes that will be loaded (i.e., we need to analyze their static
     * initializers).
     */
    private Set<IMethod> classInitializers = AnalysisUtil.createConcurrentSet();

    /**
     * Entry points added during the pointer analysis
     */
    private Set<IMethod> entryPoints = AnalysisUtil.createConcurrentSet();

    /**
     * A thread-safe representation of the call graph that we populate during the analysis, and then convert it to a
     * HafCallGraph later.
     */
    /* ConcurrentMap<OrderedPair<IMethod, Context>, ConcurrentMap<CallSiteReference, Set<OrderedPair<IMethod, Context>>>> */
    private ConcurrentIntMap<ConcurrentMap<CallSiteReference, MutableIntSet>> callGraphMap = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * GraphNode counter, for unique integers for call graph nodes
     */
    private final AtomicInteger callGraphNodeCounter = new AtomicInteger(0);

    /**
     * Dictionary for mapping ints to CG nodes.
     */
    private ConcurrentIntMap<OrderedPair<IMethod, Context>> callGraphNodeDictionary = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * Dictionary for mapping CG Nodes to ints
     */
    private ConcurrentMap<OrderedPair<IMethod, Context>, Integer> reverseCallGraphNodeDictionary = new ConcurrentHashMap<>();

    private HafCallGraph callGraph = null;

    /**
     * Heap abstraction factory.
     */
    private final HeapAbstractionFactory haf;


    private final DependencyRecorder depRecorder;

    private final StatementRegistrar registrar;


    /**
     * Is the graph still being constructed, or is it finished? Certain operations should be called only once the graph
     * has finished being constructed.
     */
    private boolean graphFinished = false;

    public PointsToGraph(StatementRegistrar registrar, HeapAbstractionFactory haf, DependencyRecorder depRecorder) {
        this.depRecorder = depRecorder;

        this.haf = haf;
        this.registrar = registrar;

        this.populateInitialContexts(registrar.getInitialContextMethods());
    }

    /**
     * Populate the contexts map by adding the initial context for all the given
     * methods
     *
     * @param haf abstraction factory defining the initial context
     * @param initialMethods methods to be paired with the initial context
     * @return mapping from each method in the given set to the singleton set
     *         containing the initial context
     */
    private void populateInitialContexts(Set<IMethod> initialMethods) {
        for (IMethod m : initialMethods) {
            this.getOrCreateContextSet(m).add(this.haf.initialContext());
        }
    }


    /**
     * What is the immediate representative of n? If n is its own representative (i.e., either n is not in a cycle, or n
     * has not been collapsed to another node in a cycle), then this method returns null.
     *
     * @param n
     * @return
     */
    /*PointsToGraphNode*/Integer getImmediateRepresentative(/*PointsToGraphNode*/int n) {
        if (!USE_CYCLE_COLLAPSING) {
            return null;
        }
        return this.immediateRepresentative.get(n);
    }

    /**
     * What is the representative of n? If n is its own representative (i.e., either n is not in a cycle, or n has not
     * been collapsed to another node in a cycle), then this method returns n.
     *
     * @param n
     * @return
     */
    private/*PointsToGraphNode*/int getRepresentative(/*PointsToGraphNode*/int n) {
        if (!USE_CYCLE_COLLAPSING) {
            return n;
        }

        Integer rep = this.representative.get(n);
        if (rep == null) {
            return n;
        }

        int x = this.getRepresentative(rep);
        while (x != rep) {
            if (this.representative.replace(n, rep, x)) {
                // successfully updated to the new rep, a la union-find
                return x;
            }
            // the rep changed out from under us, try it again.
            rep = this.representative.get(n);
            x = this.getRepresentative(rep);
        }
        assert !isCollapsedNode(rep);
        return rep;
    }

    /**
     * Has n been collapsed? i.e., it is part of a cycle, and the representative of the cycle is a node other than n.
     *
     * @param n
     * @return
     */
    public boolean isCollapsedNode(/*PointsToGraphNode*/int n) {
        if (!USE_CYCLE_COLLAPSING) {
            return false;
        }
        return this.immediateRepresentative.containsKey(n);
    }

    /**
     * Add an edge from node to heapContext in the graph.
     *
     * @param node
     * @param heapContext
     * @return
     */
    public GraphDelta addEdge(PointsToGraphNode node, InstanceKey heapContext) {
        assert node != null && heapContext != null;
        assert !this.graphFinished;
        Integer h = this.reverseInstanceKeyDictionary.get(heapContext);
        if (h == null) {
            // not in the dictionary yet
            h = this.instanceKeyCounter.getAndIncrement();

            // Put the mapping into instanceKeyDictionary and concreteTypeDictionary
            // Note that it is important to do this before putting it into reverseInstanceKeyDictionary
            // to avoid a race (i.e., someone looking up heapContext in reverseInstanceKeyDictionary, getting
            // int h, yet getting null when trying instanceKeyDictionary.get(h).)
            // try a put if absent
            // Note that we can do a put instead of a putIfAbsent, since h is guaranteed unique.
            this.instanceKeyDictionary.put(h, heapContext);
            if (concreteTypeDictionary != null) {
                this.concreteTypeDictionary.put(h, heapContext.getConcreteType());
            }
            Integer existing = this.reverseInstanceKeyDictionary.putIfAbsent(heapContext, h);
            if (existing != null) {
                // someone beat us. h will never be used.
                this.instanceKeyDictionary.remove(h);
                if (concreteTypeDictionary != null) {
                    this.concreteTypeDictionary.remove(h);
                }
                h = existing;
            }
        }

        GraphDelta delta = new GraphDelta(this);
        int n = lookupDictionary(node);

        // Get the pointsTo set for (the representative of) n.
        // Requires a loop due to concurrent threads changing the representative of n.
        MutableIntSet ptset;
        do {
            n = this.getRepresentative(n);
            ptset = this.pointsToSet(n);
        } while (ptset == null);

        if (!ptset.contains(h)) {
            IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
            addToSetAndSupersets(delta,
                                 n,
                                 SparseIntSet.singleton(h).intIterator(),
                                 1,
                                 true,
                                 MutableSparseIntSet.makeEmpty(),
                                 new IntStack(),
                                 new Stack<Set<TypeFilter>>(),
                                 toCollapse);
            collapseCycles(toCollapse, delta);
        }
        return delta;
    }

    /**
     * Collapse a cycle, i.e., choose a representative, and collapse all the nodes to point to that representative.
     *
     * @param toCollapse
     * @param delta
     */
    private void collapseCycles(IntMap<MutableIntSet> toCollapse, GraphDelta delta) {
        if (!USE_CYCLE_COLLAPSING) {
            return;
        }
        IntIterator iter = toCollapse.keyIterator();
        while (iter.hasNext()) {
            int rep = iter.next();
            IntIterator collapseIter = toCollapse.get(rep).intIterator();
            rep = this.getRepresentative(rep); // it is possible that rep was already collapsed to something else. So we get the representative of it to shortcut things.
            while (collapseIter.hasNext()) {
                int n = collapseIter.next();
                if (n == rep) {
                    // no need to collapse this.
                    continue;
                }
                this.collapseNodes(n, rep, delta);
                delta.collapseNodes(n, rep);
            }
        }
    }

    /**
     * Used for printing. Only create if in test mode or if printing the points-to graph
     */
    public IntMap<PointsToGraphNode> graphNodeDictionary = (!AccrueAnalysisMain.testMode)
            ? PointsToAnalysisMultiThreaded.<PointsToGraphNode> makeConcurrentIntMap() : null;

    protected int lookupDictionary(PointsToGraphNode node) {
        Integer n = this.reverseGraphNodeDictionary.get(node);
        if (n == null) {
            // not in the dictionary yet
            if (this.graphFinished) {
                return -1;
            }
            n = graphNodeCounter.getAndIncrement();
            Integer existing = this.reverseGraphNodeDictionary.putIfAbsent(node, n);
            if (existing != null) {
                return existing;
            }
            if (!AccrueAnalysisMain.testMode) {
                graphNodeDictionary.put(n, node);
            }
        }
        return n;
    }

    private int lookupCallGraphDictionary(OrderedPair<IMethod, Context> node) {
        Integer n = this.reverseCallGraphNodeDictionary.get(node);
        if (n == null) {
            // not in the dictionary yet
            if (this.graphFinished) {
                return -1;
            }
            n = callGraphNodeCounter.getAndIncrement();

            callGraphNodeDictionary.put(n, node);
            Integer existing = this.reverseCallGraphNodeDictionary.putIfAbsent(node, n);
            if (existing != null) {
                return existing;
            }
        }
        return n;
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target.
     * This should be used when the pointsto set of the target is a supserset of
     * the pointsto set of the source.
     *
     * @param source
     * @param target
     * @return
     */
    public GraphDelta copyEdges(PointsToGraphNode source, PointsToGraphNode target) {
        GraphDelta changed = new GraphDelta(this);
        copyEdges(this.getRepresentative(lookupDictionary(source)),
                  this.getRepresentative(lookupDictionary(target)),
                  changed);
        return changed;
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target. This should be used when the pointsto set
     * of the target is a supserset of the pointsto set of the source.
     *
     * @param source
     * @param target
     * @return
     */
    private void copyEdges(int/*PointsToGraphNode*/source, int/*PointsToGraphNode*/target, GraphDelta changed) {
        assert !this.graphFinished;

        if (source == target) {
            // don't bother adding
            return;
        }

        // source is a subset of target, target is a superset of source.
        if (isUnfilteredSubsetOf.add(source, target)) {
            computeDeltaForAddedSubsetRelation(changed, source, null, target);

            if (USE_CYCLE_COLLAPSING) {
                int newS = this.getRepresentative(source);
                int newT = this.getRepresentative(target);
                if (newS != source || newT != target) {
                    // the representative of either s or t changed.
                    // We need to redo the copyEdges with the newRepresentatives
                    copyEdges(newS, newT, changed);
                }
            }
        }
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target.
     * This should be used when the pointsto set of the target is a supserset of
     * the pointsto set of the source.
     *
     * @param source
     * @param target
     * @return
     */
    public GraphDelta copyFilteredEdges(PointsToGraphNode source,
                                        TypeFilter filter,
                                        PointsToGraphNode target) {
        GraphDelta changed = new GraphDelta(this);
        copyFilteredEdges(this.getRepresentative(lookupDictionary(source)),
                          filter,
                          this.getRepresentative(lookupDictionary(target)),
                          changed);
        return changed;
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target. This should be used when the pointsto set
     * of the target is a supserset of the pointsto set of the source.
     *
     * @param source
     * @param target
     * @return
     */
    private void copyFilteredEdges(int/*PointsToGraphNode*/source, TypeFilter filter, int/*PointsToGraphNode*/target,
                                   GraphDelta changed) {

        assert !this.graphFinished;
        // source is a subset of target, target is a subset of source.
        if (TypeFilter.IMPOSSIBLE.equals(filter)) {
            // impossible filter! Don't bother adding the relationship.
            return;
        }

        if (source == target) {
            // don't bother adding
            return;
        }

        if (isFilteredSubsetOf.add(source, target, filter)) {
            computeDeltaForAddedSubsetRelation(changed, source, filter, target);
            if (USE_CYCLE_COLLAPSING) {
                int newS = this.getRepresentative(source);
                int newT = this.getRepresentative(target);
                if (newS != source || newT != target) {
                    // the representative of either s or t changed.
                    // We need to redo the copyEdges with the newRepresentatives
                    copyFilteredEdges(newS, filter, newT, changed);
                }
            }
        }
    }

    /*
     * This method adds everything in s that satisfies filter to t, in both the cache and the GraphDelta,
     * and the recurses
     */
    private void computeDeltaForAddedSubsetRelation(GraphDelta changed, /*PointsToGraphNode*/int source, TypeFilter filter, /*PointsToGraphNode*/
                                          int target) {

        IntSet s = this.pointsToSet(source);
        while (s == null) {
            source = this.getRepresentative(source);
            s = this.pointsToSet(source);
        }

        // Now take care of all the supersets of target...
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
        addToSetAndSupersets(changed,
                             target,
                             filter == null ? s.intIterator() : new FilteredIterator(s.intIterator(), filter),
                             s.size(),
                             true,
                             MutableSparseIntSet.makeEmpty(),
                             new IntStack(),
                             new Stack<Set<TypeFilter>>(),
                             toCollapse);
        collapseCycles(toCollapse, changed);

    }

    /**
     * Add the elements in toAdd to the target. If addToSuperSets is true, then propagate any changes to the supersets
     * of target. Arguments currentlyAdding, currentlyAddingStack, and filterStack are used in the detectino of cycles,
     * and cycles that are found are recorded in toCollapse (if it is non-null). Any changes made to the points to graph
     * are recorded in the GraphDelta changed.
     *
     * @param changed
     * @param target
     * @param toAdd
     * @param toAddSizeGuess
     * @param addToSuperSets
     * @param currentlyAdding
     * @param currentlyAddingStack
     * @param filterStack
     * @param toCollapse
     */
    private void addToSetAndSupersets(GraphDelta changed, /*PointsToGraphNode*/int target, /*Iterator<InstanceKey>*/
                                      IntIterator toAdd,
                                      int toAddSizeGuess, boolean addToSuperSets,
                                  MutableIntSet currentlyAdding, IntStack currentlyAddingStack,
                                  Stack<Set<TypeFilter>> filterStack, IntMap<MutableIntSet> toCollapse) {
        // Handle detection of cycles.
        if (USE_CYCLE_COLLAPSING) {
            if (toCollapse != null) {
                if (currentlyAdding.contains(target)) {
                    // we detected a cycle!
                    int foundAt = -1;
                    boolean hasMeaningfulFilter = false;
                    for (int i = 0; !hasMeaningfulFilter && i < currentlyAdding.size(); i++) {
                        if (foundAt < 0 && currentlyAddingStack.get(i) == target) {
                            foundAt = i;
                        }
                        hasMeaningfulFilter |= filterStack.get(i) != null;
                    }
                    if (!hasMeaningfulFilter) {
                        // we can collapse some nodes together!
                        // Choose the lowest numbered element of the cycle as the representative,
                        // so that if another thread is also trying to collapse this cycle,
                        // they will agree on the representative.
                        int representative = target;
                        MutableIntSet toCollapseSet = MutableSparseIntSet.makeEmpty();
                        for (int i = foundAt + 1; i < filterStack.size(); i++) {
                            int n = currentlyAddingStack.get(i);
                            toCollapseSet.add(n);
                            if (n < representative) {
                                representative = n;
                            }
                        }

                        // Put the toCollapseSet in the toCollapse map, for the representative.
                        MutableIntSet existingCollapseSet = toCollapse.get(representative);
                        if (existingCollapseSet == null) {
                            toCollapse.put(representative, toCollapseSet);
                        }
                        else {
                            existingCollapseSet.addAll(toCollapseSet);
                        }
                    }
                }
            }
        }

        // Now we actually add the set to the target, both in the cache, and in the GraphDelta
        MutableIntSet deltaSet = null;
        MutableIntSet graphSet = this.pointsToSet(target);

        if (USE_CYCLE_COLLAPSING) {
            if (graphSet == null) {
                // whoops! target has been collapsed.
                // Don't bother adding to target and supersets, just go straight for
                // the representative of target.
                addToSetAndSupersets(changed,
                                     this.getRepresentative(target),
                                     toAdd,
                                     toAddSizeGuess,
                                     addToSuperSets,
                                     currentlyAdding,
                                     currentlyAddingStack,
                                     filterStack,
                                     toCollapse);
                return;
            }
        }

        MutableIntSet added = MutableSparseIntSet.makeEmpty();

        while (toAdd.hasNext()) {
            int next = toAdd.next();
            if (graphSet.add(next)) {
                if (deltaSet == null) {
                    deltaSet = changed.getOrCreateSet(target, toAddSizeGuess);
                }
                deltaSet.add(next);
                added.add(next);
            }
        }
        if (added.isEmpty()) {
            // we didn't add anything.
            return;
        }

        int newRep;
        if (USE_CYCLE_COLLAPSING) {
            if ((newRep = this.getRepresentative(target)) != target) {

                // whoops! The target got merged with something else while we were in the middle
                // of adding to it.
                // to make sure we don't lose any instance keys, we will add the elements again on newRep...
                // We don't bother adding directly to the supersets of target, since all the supersets of
                // target will be supersets of newRep...
                addToSetAndSupersets(changed,
                                     newRep,
                                     added.intIterator(),
                                     added.size(),
                                     addToSuperSets,
                                     currentlyAdding,
                                     currentlyAddingStack,
                                     filterStack,
                                     toCollapse);
                return;
            }
        }

        // We added at least one element to target
        // So let's recurse on the immediate supersets of target, if we need to
        if (!addToSuperSets) {
            return;
        }

        currentlyAdding.add(target);
        currentlyAddingStack.push(target);
        IntSet unfilteredSupersets = this.isUnfilteredSubsetOf.forward(target);
        IntIterator iter = unfilteredSupersets == null ? EmptyIntIterator.instance()
                : unfilteredSupersets.intIterator();
        filterStack.push(null);
        while (iter.hasNext()) {
            int m = this.getRepresentative(iter.next());

            if (m == target) {
                continue;
            }
            addToSetAndSupersets(changed,
                                 m,
                                 added.intIterator(),
                                 added.size(),
                                 addToSuperSets,
                                 currentlyAdding,
                                 currentlyAddingStack,
                                 filterStack,
                                 toCollapse);

        }
        filterStack.pop();
        IntMap<Set<TypeFilter>> filteredSupersets = this.isFilteredSubsetOf.forward(target);
        iter = filteredSupersets == null ? EmptyIntIterator.instance() : filteredSupersets.keyIterator();
        while (iter.hasNext()) {
            int m = this.getRepresentative(iter.next());
            if (m == target) {
                continue;
            }

            @SuppressWarnings("null")
            Set<TypeFilter> filterSet = filteredSupersets.get(m);
            // it is possible that the filter set is empty, due to race conditions.
            // No trouble, we will just ignore it, and pretend we got in there before
            // the relation between target and m was created.
            if (filterSet != null && !filterSet.isEmpty()) {
                filterStack.push(filterSet);
                addToSetAndSupersets(changed,
                                     m,
                                     new FilteredIterator(added.intIterator(), filterSet),
                                     added.size(),
                                     addToSuperSets,
                                     currentlyAdding,
                                     currentlyAddingStack,
                                     filterStack,
                                     toCollapse);
                filterStack.pop();
            }
        }
        currentlyAdding.remove(target);
        currentlyAddingStack.pop();

    }

    /**
     * Provide an iterator for the things that n points to. Note that we may not return a set, i.e., some InstanceKeys
     * may be returned multiple times.
     *
     * @param n
     * @return
     */
    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode n) {
        assert this.graphFinished : "Can only get a points to set without an originator if the graph is finished";
        return pointsToIterator(n, null);
    }

    public Iterator<InstanceKey> pointsToIterator(PointsToGraphNode node, StmtAndContext originator) {
        assert this.graphFinished || originator != null;
        int n = lookupDictionary(node);
        if (this.graphFinished && n < 0) {
            return Collections.emptyIterator();
        }
        return new IntToInstanceKeyIterator(this.pointsToIntIterator(n, originator));
    }

    public IntIterator pointsToIntIterator(PointsToGraphNode n, StmtAndContext origninator) {
        assert !this.graphFinished;
        return pointsToIntIterator(lookupDictionary(n), origninator);
    }
    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n, StmtAndContext originator) {
        MutableIntSet s;
        do {
            n = this.getRepresentative(n);
            s = this.pointsToSet(n);
        } while (s == null);

        if (originator != null) {
            // If the originating statement is null then the graph is finished and there is no need to record this read
            this.recordRead(n, originator);
        }

        return s.intIterator();
    }


    private static boolean satisfiesAny(Set<TypeFilter> filters, IClass type) {
        for (TypeFilter f : filters) {
            if (f.satisfies(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add a call at a particular call site from a caller (in a context) to a callee (in a context)
     *
     * @param callSite call site the new call is being added for
     * @param caller method invocation occurs in
     * @param callerContext analysis context for the caller
     * @param callee method being called
     * @param calleeContext analyis context for the callee
     */
    public boolean addCall(CallSiteReference callSite, IMethod caller,
                           Context callerContext, IMethod callee,
                           Context calleeContext) {
        int callerPair = lookupCallGraphDictionary(new OrderedPair<>(caller, callerContext));
        int calleePair = lookupCallGraphDictionary(new OrderedPair<>(callee, calleeContext));

        ConcurrentMap<CallSiteReference, MutableIntSet> m = this.callGraphMap.get(callerPair);
        if (m == null) {
            m = AnalysisUtil.createConcurrentHashMap();
            ConcurrentMap<CallSiteReference, MutableIntSet> existing = this.callGraphMap.putIfAbsent(callerPair,
                                                                                                                          m);
            if (existing != null) {
                m = existing;
            }
        }

        MutableIntSet s = m.get(callSite);
        if (s == null) {
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet existing = m.putIfAbsent(callSite, s);

            if (existing != null) {
                s = existing;
            }
        }
        s.add(calleePair);

        this.recordReachableContext(callee, calleeContext);
        return true;
    }

    /**
     * Record a callee context for the given method
     *
     * @param callee method
     * @param calleeContext context
     */
    private void recordReachableContext(IMethod callee, Context calleeContext) {
        Set<Context> s = this.reachableContexts.get(callee);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<Context> existing = this.reachableContexts.putIfAbsent(callee, s);
            if (existing != null) {
                s = existing;
            }
        }

        if (s.add(calleeContext)) {
            // The context is new
            depRecorder.recordNewContext(callee, calleeContext);
        }
    }

    private Set<Context> getOrCreateContextSet(IMethod callee) {
        return PointsToGraph.<IMethod, Context> getOrCreateSet(callee, this.reachableContexts);
    }

    static MutableIntSet getOrCreateIntSet(int key, ConcurrentIntMap<MutableIntSet> map) {
        MutableIntSet set = map.get(key);
        if (set == null) {
            set = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet ex = map.putIfAbsent(key, set);
            if (ex != null) {
                set = ex;
            }
        }
        return set;
    }

    @SuppressWarnings("unused")
    private static <T> ConcurrentIntMap<T> getOrCreateIntMap(int key, ConcurrentIntMap<ConcurrentIntMap<T>> map) {
        ConcurrentIntMap<T> set = map.get(key);
        if (set == null) {
            set = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();
            ConcurrentIntMap<T> existing = map.putIfAbsent(key, set);
            if (existing != null) {
                set = existing;
            }
        }
        return set;
    }

    static <K, T> Set<T> getOrCreateSet(K key, ConcurrentMap<K, Set<T>> map) {
        Set<T> set = map.get(key);
        if (set == null) {
            // make these concurrent to avoid ConcurrentModificationExceptions
            // set = new HashSet<>();
            set = AnalysisUtil.createConcurrentSet();
            Set<T> ex = map.putIfAbsent(key, set);
            if (ex != null) {
                set = ex;
            }
        }
        return set;
    }

    /**
     * Set of contexts for the given method
     *
     * @param m method reference to get contexts for
     * @return set of contexts for the given method
     */
    public Set<Context> getContexts(IMethod m) {
        Set<Context> s = this.reachableContexts.get(m);
        if (s == null) {
            return Collections.<Context> emptySet();
        }
        return Collections.unmodifiableSet(this.reachableContexts.get(m));
    }

    /**
     * Get the heap abstraction factory used to compute contexts for this points to graph
     *
     * @return heap abstraction factory for this pointer analysis
     */
    public HeapAbstractionFactory getHaf() {
        return haf;
    }

    private int numCGNodes = -1;

    /**
     * Get the number of call graph nodes. Only call after construction.
     */
    public int getNumberOfCallGraphNodes() {
        if (this.numCGNodes < 0) {
            int num = 0;

            MutableIntSet nodes = MutableSparseIntSet.makeEmpty();
            IntIterator callerIter = this.callGraphMap.keyIterator();
            while (callerIter.hasNext()) {
                int callerPair = callerIter.next();
                ConcurrentMap<CallSiteReference, MutableIntSet> m = this.callGraphMap.get(callerPair);
                for (CallSiteReference callSite : m.keySet()) {
                    nodes.addAll(m.get(callSite));
                }
            }

            num += nodes.size();
            num += this.entryPoints.size();
            num += this.classInitializers.size();
            // Root method itself
            num++;
            this.numCGNodes = num;
        }

        return this.numCGNodes;
    }


        /**
     * Get the procedure call graph
     *
     * @return call graph
     */
    @SuppressWarnings("deprecation")
    public HafCallGraph getCallGraph() {
        assert graphFinished;
        if (this.callGraph != null) {
            return this.callGraph;
        }

        // Construct the call graph.
        HafCallGraph callGraph = new HafCallGraph(this.haf);
        this.callGraph = callGraph;
        try {
            IntIterator callerIter = this.callGraphMap.keyIterator();
            while (callerIter.hasNext()) {
                int callerInt = callerIter.next();
                OrderedPair<IMethod, Context> callerPair = callGraphNodeDictionary.get(callerInt);
                IMethod caller = callerPair.fst();
                Context callerContext = callerPair.snd();
                CGNode src = callGraph.findOrCreateNode(caller, callerContext);

                ConcurrentMap<CallSiteReference, MutableIntSet> m = this.callGraphMap.get(callerInt);
                for (CallSiteReference callSite : m.keySet()) {
                    MutableIntSet calleePairs = m.get(callSite);
                    IntIterator calleeIter = calleePairs.intIterator();
                    while (calleeIter.hasNext()) {
                        OrderedPair<IMethod, Context> calleePair = callGraphNodeDictionary.get(calleeIter.next());
                        IMethod callee = calleePair.fst();
                        Context calleeContext = calleePair.snd();

                        CGNode dst = callGraph.findOrCreateNode(callee, calleeContext);

                        // We are building a call graph so it is safe to call this "deprecated" method
                        src.addTarget(callSite, dst);
                    }
                }
            }

            Context initialContext = this.haf.initialContext();

            for (IMethod entryPoint : this.entryPoints) {
                callGraph.registerEntrypoint(callGraph.findOrCreateNode(entryPoint, initialContext));
            }

            for (IMethod classInit : this.classInitializers) {
                // new initializer
                CGNode initNode = callGraph.findOrCreateNode(classInit, initialContext);
                callGraph.registerEntrypoint(initNode);
            }
        }
        catch (CancelException e) {
            throw new RuntimeException(e);
        }
        return callGraph;

    }

    private void recordRead(/*PointsToGraphNode*/int node, StmtAndContext sac) {
        this.depRecorder.recordRead(node, sac);
    }

    /**
     * When we have detected that the points to sets of two nodes are identical,
     * we can collapse them.
     *
     * @param n
     * @param rep
     */
    void collapseNodes(/*PointsToGraphNode*/int n, /*PointsToGraphNode*/int rep, GraphDelta delta) {
        if (!USE_CYCLE_COLLAPSING) {
            throw new UnsupportedOperationException("We do not currently support cycle collapsing");
        }
        assert n != rep : "Can't collapse a node with itself";

        while (true) {
            // it is possible that since n and rep were registered, one or both of them were already merged.
            n = this.getRepresentative(n);
            rep = this.getRepresentative(rep);

            if (n == rep) {
                // they have already been merged.
                return;
            }

            if (n < rep) {
                // swap the representative and n, since we always collapse to the lower number.
                int temp = rep;
                rep = n;
                n = temp;
            }

            assert rep < n : "Should always collapse to the lowest number node";

            // Notify the dependency recorder
            depRecorder.startCollapseNode(n, rep);

            Integer existingRep = this.immediateRepresentative.putIfAbsent(n, rep);
            if (existingRep == null) {
                // we were the first to put it in, so update the representative map
                Integer old = this.representative.putIfAbsent(n, rep);
                assert old == null;
                break;
            }
            if (existingRep.intValue() == rep) {
                // someone else update the rep, but to the same representative..
                break;
            }
            // while we were duplicating the subset relations, someone else got in
            // and collapsed n to some other representative!
            // Try again...
        }

        // First, get the representative's points to set.
        IntSet repSet = this.pointsToSet(rep);
        while (repSet == null) {
            rep = this.getRepresentative(rep);
            repSet = this.pointsToSet(rep);
        }

        // Update the subset relations, i.e., if ss is a superset of n,
        // make ss a superset of rep.
        // (This is needed for correctness.)
        IntSet unfilteredSupersets = this.isUnfilteredSubsetOf.forward(n);
        if (unfilteredSupersets != null) {
            IntIterator iter = unfilteredSupersets.intIterator();

            while (iter.hasNext()) {
                int ss = this.getRepresentative(iter.next());
                if (rep != ss && this.isUnfilteredSubsetOf.add(rep, ss)) {
                    // we added a new relation from rep to ss, because there was a relation from
                    // n to ss. So we are responsible for propagating
                    // all any new additions to the pointsTo sets.
                    addToSetAndSupersets(delta,
                                         ss,
                                         repSet.intIterator(),
                                         repSet.size(),
                                         true,
                                         MutableSparseIntSet.makeEmpty(),
                                         new IntStack(),
                                         new Stack<Set<TypeFilter>>(),
                                         null);
                }
            }
        }

        IntMap<Set<TypeFilter>> filteredSupersets = this.isFilteredSubsetOf.forward(n);
        if (filteredSupersets != null) {
            IntIterator iter = filteredSupersets.keyIterator();
            while (iter.hasNext()) {
                int ss = this.getRepresentative(iter.next());
                if (rep == ss) {
                    continue;
                }
                for (TypeFilter filter : filteredSupersets.get(ss)) {
                    if (this.isFilteredSubsetOf.add(rep, ss, filter)) {
                        addToSetAndSupersets(delta,
                                             ss,
                                             new FilteredIterator(repSet.intIterator(), filter),
                                             repSet.size(),
                                             true,
                                             MutableSparseIntSet.makeEmpty(),
                                             new IntStack(),
                                             new Stack<Set<TypeFilter>>(),
                                             null);
                    }
                }
            }
        }

        // Add all of the pointsto set of rep to
        // n, to make sure that we trigger all appropriate
        // statements that depended on n.
        // (This is needed for correctness.)
        addToSetAndSupersets(delta, n, repSet.intIterator(), repSet.size(), false, // no need to add them to the supersets of n...
                             MutableSparseIntSet.makeEmpty(),
                             new IntStack(),
                             new Stack<Set<TypeFilter>>(),
                             null);

        // now that we have taken care of setting the representative for n,
        // adding the new subset relations, etc., we can look at removing
        // stuff that will update the points to set of n.

        // First, tell the dependency recorder we have finished collapsing,
        // so it can remove any dependencies on pointsTo(n).
        // (This is an optimization, and not needed for correctness.)
        depRecorder.finishCollapseNode(n);

        // Second, we can actually remove pointsTo(n). (If anyone has a reference
        // to it at the moment, that will still work, but in the future anyone
        // calling this.pointsToSet(n) will get the points to set for the
        // representative of n.)
        // (This is an optimization, and not needed for correctness.)
        this.pointsTo.remove(n);
    }

    public AtomicInteger clinitCount = new AtomicInteger(0);

    /**
     * Add class initialization methods
     *
     * @param classInits list of class initializer is initialization order (i.e.
     *            element j is a super class of element j+1)
     * @return true if the call graph changed as a result of this call, false
     *         otherwise
     */
    public boolean addClassInitializers(List<IMethod> classInits) {
        Context initialContext = this.haf.initialContext();

        boolean cgChanged = false;
        for (int j = classInits.size() - 1; j >= 0; j--) {
            IMethod clinit = classInits.get(j);
            if (this.classInitializers.add(clinit)) {
                // new initializer
                cgChanged = true;
                this.recordReachableContext(clinit, initialContext);
                this.clinitCount.incrementAndGet();
            }
            else {
                // Already added an initializer and thus must have added initializers for super classes. These are all
                // that are left to process since we are adding from sub class to super class order

                // If any were added then j would have been decremented
                return cgChanged;
            }
        }
        // Should always be true
        assert cgChanged : "Reached the end of the loop without adding any clinits " + classInits;
        return cgChanged;
    }

    /**
     * Add new entry point methods (i.e. methods called in the empty context)
     *
     * @param newEntryPoint list of methods to add
     * @return true if the call graph changed as a result of this call, false
     *         otherwise
     */
    public boolean addEntryPoint(IMethod newEntryPoint) {
        boolean changed = this.entryPoints.add(newEntryPoint);
        if (changed) {
            this.recordReachableContext(newEntryPoint, this.haf.initialContext());
            this.clinitCount.incrementAndGet();
        }
        return changed;
    }

    class FilteredIntSet extends AbstractIntSet {
        final IntSet s;
        final Set<TypeFilter> filters;

        FilteredIntSet(IntSet s, Set<TypeFilter> filters) {
            this.filters = filters;
            assert filters != null && !filters.isEmpty();
            boolean allImpossible = true;
            for (TypeFilter filter : filters) {
                if (TypeFilter.IMPOSSIBLE.equals(filter)) {
                    // nothing will satisfy this filter.
                }
                else {
                    allImpossible = false;
                    break;
                }
            }
            this.s = allImpossible ? EmptyIntSet.INSTANCE : s;
        }

        @Override
        public IntIterator intIterator() {
            return new FilteredIterator(this.s.intIterator(), this.filters);
        }

        @SuppressWarnings("synthetic-access")
        @Override
        public boolean contains(int o) {
            return this.s.contains(o) && satisfiesAny(filters, PointsToGraph.this.concreteType(o));
        }

        @Override
        public boolean containsAny(IntSet set) {
            IntIterator iter = set.intIterator();
            while (iter.hasNext()) {
                if (this.contains(iter.next())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public IntSet intersection(IntSet that) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IntSet union(IntSet that) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void foreach(IntSetAction action) {
            IntIterator iter = this.intIterator();
            while (iter.hasNext()) {
                action.act(iter.next());
            }
        }

        @Override
        public void foreachExcluding(IntSet X, IntSetAction action) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int max() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean sameValue(IntSet that) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSubset(IntSet that) {
            throw new UnsupportedOperationException();
        }

        public int underlyingSetSize() {
            if (this.s instanceof FilteredIntSet) {
                return ((FilteredIntSet) this.s).underlyingSetSize();
            }
            return this.s.size();
        }

    }

    class FilteredIterator implements IntIterator {
        private final IntIterator iter;
        private final TypeFilter filter;
        private final Set<TypeFilter> filters;
        private int next = -1;

        FilteredIterator(IntIterator iter, TypeFilter filter) {
            this.filter = filter;
            this.filters = null;
            if (TypeFilter.IMPOSSIBLE.equals(filter)) {
                // nothing will satisfy this filter.
                this.iter = EmptyIntIterator.instance();
            }
            else {
                this.iter = iter;
            }

        }

        FilteredIterator(IntIterator iter, Set<TypeFilter> filters) {
            if (filters.size() == 1) {
                this.filter = filters.iterator().next();
                this.filters = null;
            }
            else {
                this.filter = null;
                this.filters = filters;
            }
            if (filter != null && TypeFilter.IMPOSSIBLE.equals(filter)) {
                // nothing will satisfy this filter.
                this.iter = EmptyIntIterator.instance();
            }
            else {
                this.iter = iter;
            }

        }

        @SuppressWarnings("synthetic-access")
        @Override
        public boolean hasNext() {
            while (this.next < 0 && this.iter.hasNext()) {
                int i = this.iter.next();
                IClass type = PointsToGraph.this.concreteType(i);
                if (this.filter != null && this.filter.satisfies(type) || this.filters != null
                        && satisfiesAny(filters, type)) {
                    this.next = i;
                }
            }

            return this.next >= 0;
        }

        @Override
        public int next() {
            if (this.hasNext()) {
                int x = this.next;
                this.next = -1;
                return x;
            }
            throw new NoSuchElementException();
        }
    }

    private IClass concreteType(/*InstanceKey*/int i) {
        if (this.concreteTypeDictionary != null) {
            return this.concreteTypeDictionary.get(i);
        }
        return this.instanceKeyDictionary.get(i).getConcreteType();
    }

    public class IntToInstanceKeyIterator implements Iterator<InstanceKey> {
        private final IntIterator iter;

        public IntToInstanceKeyIterator(IntIterator iter) {
            this.iter = iter;
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public InstanceKey next() {
            @SuppressWarnings("synthetic-access")
            InstanceKey ik = PointsToGraph.this.instanceKeyDictionary.get(this.iter.next());
            assert ik != null;
            return ik;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    public int numPointsToGraphNodes() {
        return this.pointsTo.size();
    }

    public IntMap<MutableIntSet> getPointsToGraph() {
        return this.pointsTo;
    }

    private MutableIntSet pointsToSet(/*PointsToGraphNode*/int n) {
        MutableIntSet s = this.pointsTo.get(n);
        if (s == null && !graphFinished) {
            if (USE_CYCLE_COLLAPSING) {
                if (this.isCollapsedNode(n)) {
                    // we've collapsed this node. Don't recreate a points to set for it.
                    // Clients just have to deal with this maybe returning null.
                    return null;
                }
            }
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet ex = this.pointsTo.putIfAbsent(n, s);
            if (ex != null) {
                // someone beat us to it!
                s = ex;
            }
        }
        else if (s == null && graphFinished) {
            return EmptyIntSet.INSTANCE;
        }
        return s;
    }

    public int cycleRemovalCount() {
        if (!USE_CYCLE_COLLAPSING) {
            return 0;
        }
        return this.immediateRepresentative.size();
    }

    /**
     * Find cycles in the superset relation of the pointstograph nodes, and collapse them. Note that this method is not
     * thread safe.
     */
    public GraphDelta findCycles() {
        if (!USE_CYCLE_COLLAPSING) {
            return null;
        }
        GraphDelta delta = new GraphDelta(this);
        boolean someCollapsed;
        do {
            someCollapsed = false;
            IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
            MutableIntSet visited = MutableSparseIntSet.makeEmpty();
            IntIterator iter = this.isUnfilteredSubsetOf.domain();
            while (iter.hasNext()) {
                int n = iter.next();
                this.findCycles(n, visited, MutableSparseIntSet.makeEmpty(), new IntStack(), toCollapse);
            }

            IntIterator repIter = toCollapse.keyIterator();
            while (repIter.hasNext()) {
                int rep = repIter.next();
                IntIterator nIter = toCollapse.get(rep).intIterator();
                rep = this.getRepresentative(rep); // it is possible that rep was already collapsed to something else. So we get the representative of it to shortcut things.
                while (nIter.hasNext()) {
                    int n = this.getRepresentative(nIter.next());
                    if (n == rep) {
                        continue;
                    }
                    someCollapsed = true;
                    this.collapseNodes(n, rep, delta);
                }
            }
        } while (someCollapsed);
        return delta;
    }

    private void findCycles(/*PointsToGraphNode*/int n, MutableIntSet visited, MutableIntSet currentlyVisiting,
                            IntStack currentlyVisitingStack, IntMap<MutableIntSet> toCollapse) {
        if (!USE_CYCLE_COLLAPSING) {
            throw new UnsupportedOperationException("We do not currently support collapsing cycles.");
        }
        if (isCollapsedNode(n)) {
            // ignore collapsed nodes
            return;
        }
        if (currentlyVisiting.contains(n)) {
            // we detected a cycle!
            int foundAt = -1;
            for (int i = 0; i < currentlyVisiting.size(); i++) {
                if (foundAt < 0 && currentlyVisitingStack.get(i) == n) {
                    foundAt = i;
                    break;
                }
            }
            // we can collapse some nodes together!
            MutableIntSet toCollapseSet = MutableSparseIntSet.makeEmpty();
            int rep = n;
            for (int i = foundAt + 1; i < currentlyVisitingStack.size(); i++) {
                toCollapseSet.add(currentlyVisitingStack.get(i));
                if (n < rep) {
                    rep = n;
                }
            }

            MutableIntSet existingCollapseSet = toCollapse.get(rep);
            if (existingCollapseSet == null) {
                toCollapse.put(rep, toCollapseSet);
            }
            else {
                existingCollapseSet.addAll(toCollapseSet);
            }
            return;
        }

        if (visited.contains(n)) {
            // already recursed or recursing on the children of n
            return;
        }
        visited.add(n);

        // now recurse.
        currentlyVisiting.add(n);
        currentlyVisitingStack.push(n);
        IntSet children = this.isUnfilteredSubsetOf.forward(n);
        if (children == null) {
            children = EmptyIntSet.INSTANCE;
        }
        IntIterator childIterator = children.intIterator();
        while (childIterator.hasNext()) {
            int child = childIterator.next();
            this.findCycles(child,
                            visited,
                            currentlyVisiting,
                            currentlyVisitingStack,
                            toCollapse);
        }

        currentlyVisiting.remove(n);
        currentlyVisitingStack.pop();

    }

    public void constructionFinished() {
        this.graphFinished = true;

        // set various fields to null to allow them to be garbage collected.
        this.reverseInstanceKeyDictionary = null;
        this.concreteTypeDictionary = null;
        this.isUnfilteredSubsetOf = null;
        this.isFilteredSubsetOf = null;

        // construct the call graph before we clear out a lot of stuff.
        this.getCallGraph();
        this.classInitializers = null;
        this.entryPoints = null;
        this.callGraphMap = null;

        // make more compact, read-only versions of the sets.
        IntIterator keyIterator = pointsTo.keyIterator();
        while (keyIterator.hasNext()) {
            int key = keyIterator.next();
            MutableIntSet ms = pointsTo.get(key);
            MutableIntSet newMS = MutableSparseIntSet.make(ms);
            pointsTo.put(key, newMS);
        }

        // this.reachableContexts = compact(this.reachableContexts);
        this.pointsTo = compact(this.pointsTo, "pointsTo");

        this.instanceKeyDictionary = compact(this.instanceKeyDictionary, "instanceKeyDictionary");

        this.reverseGraphNodeDictionary = compact(this.reverseGraphNodeDictionary);
    }

    /**
     * Produce a more compact map. This reduces memory usage, but gives back a read-only map.
     */
    private static <V> ConcurrentIntMap<V> compact(ConcurrentIntMap<V> m, String debugName) {
        DenseIntMap<V> newMap = new DenseIntMap<>(Math.max(m.max() + 1, 0));
        IntIterator keyIterator = m.keyIterator();
        while (keyIterator.hasNext()) {
            int key = keyIterator.next();
            newMap.put(key, m.get(key));
        }
        float util = newMap.utilization();
        int length = Math.round(newMap.size() / util);
        System.err.println("   Utilization of DenseIntMap for " + debugName + ": " + String.format("%.3f", util)
                + " (approx "
                + (length - newMap.size()) + " empty slots out of " + length + ")");
        return new ReadOnlyConcurrentIntMap<>(newMap);
    }

    /**
     * Produce a more compact map. This reduces memory usage, but gives back a read-only map.
     */
    private static <K, V> ConcurrentMap<K, V> compact(ConcurrentMap<K, V> m) {
        if (m.isEmpty()) {
            return new ReadOnlyConcurrentMap<>(Collections.<K, V> emptyMap());
        }
        Map<K, V> newMap = new HashMap<>(m.size());
        for (K key : m.keySet()) {
            newMap.put(key, m.get(key));
        }
        return new ReadOnlyConcurrentMap<>(newMap);
    }

    private static class ReadOnlyConcurrentMap<K, V> implements ConcurrentMap<K, V> {
        final Map<K, V> m;

        ReadOnlyConcurrentMap(Map<K, V> m) {
            this.m = m;
        }

        @Override
        public V putIfAbsent(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object key, Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean replace(K key, V oldValue, V newValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V replace(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<java.util.Map.Entry<K, V>> entrySet() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            return m.size();
        }

        @Override
        public boolean isEmpty() {
            return m.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return m.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return m.containsValue(value);
        }

        @Override
        public V get(Object key) {
            return m.get(key);
        }

        @Override
        public V put(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V remove(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<K> keySet() {
            return m.keySet();
        }

        @Override
        public Collection<V> values() {
            return m.values();
        }

    }

    public StatementRegistrar getRegistrar() {
        return registrar;
    }

    /**
     * Print the points-to graph in graphviz dot format to a file
     *
     * @param filename name of the file, the file is put in tests/filename.dot
     * @param addDate if true then the date will be added to the filename
     */
    public void dumpPointsToGraphToFile(String filename, boolean addDate) {
        String file = filename;
        if (addDate) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("-yyyy-MM-dd-HH_mm_ss");
            Date dateNow = new Date();
            String now = dateFormat.format(dateNow);
            file += now;
        }
        String fullFilename = file + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpPointsToGraph(out);
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    private Writer dumpPointsToGraph(Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]" + ";\n");

        Map<String, Integer> dotToCount = new HashMap<>();
        Map<PointsToGraphNode, String> n2s = new HashMap<>();
        Map<InstanceKey, String> ik2s = new HashMap<>();

        PointsToGraphNode test = null;

        writer.write("/******************** NODES ********************/\n");
        // Need to differentiate between different nodes with the same string
        IntIterator nodeIter = pointsTo.keyIterator();
        while(nodeIter.hasNext()) {
            PointsToGraphNode n = graphNodeDictionary.get(nodeIter.next());
            String nStr = escape(n.toString());
            Integer count = dotToCount.get(nStr);
            if (count == null) {
                dotToCount.put(nStr, 1);
            }
            else {
                dotToCount.put(nStr, count + 1);
                nStr += " (" + count + ")";
            }
            n2s.put(n, nStr);

            Iterator<InstanceKey> ikIter = pointsToIterator(n);
            while (ikIter.hasNext()) {
                InstanceKey ik = ikIter.next();
                if (ik2s.containsKey(ik)) {
                    // Already have a string for this instance key
                    continue;
                }
                String ikStr = escape(ik.toString());
                Integer ikCount = dotToCount.get(ikStr);
                if (ikCount == null) {
                    dotToCount.put(ikStr, 1);
                }
                else {
                    dotToCount.put(ikStr, ikCount + 1);
                    ikStr += " (" + ikCount + ")";
                }
                ik2s.put(ik, ikStr);
            }
        }

        writer.write("/******************** EDGES ********************/\n");
        IntIterator sourceIter = pointsTo.keyIterator();
        while (sourceIter.hasNext()) {
            PointsToGraphNode n = graphNodeDictionary.get(sourceIter.next());
            Iterator<InstanceKey> ikIter = pointsToIterator(n);
            while (ikIter.hasNext()) {
                InstanceKey target = ikIter.next();
                String iks = ik2s.get(target);
                if (iks.contains("Exception") || iks.contains("CaseInsensitiveComparator")
                        || iks.contains("allocated at void com.ibm.wala.FakeRootClass.fakeRootMethod")
                        || iks.contains("ObjectStreamField")) {
                    continue;
                }
                writer.write("\t\"" + n2s.get(n) + "\" -> \"" + ik2s.get(target) + "\";\n");
            }
        }

        writer.write("\n}\n");
        return writer;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
