package analysis.pointer.graph;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import util.intmap.IntMap;
import util.intmap.SimpleConcurrentIntMap;
import util.intmap.SparseIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.DependencyRecorder;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.graph.AnnotatedIntRelation.SetAnnotatedIntRelation;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.EmptyIntIterator;
import com.ibm.wala.util.collections.IntStack;
import com.ibm.wala.util.intset.EmptyIntSet;
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
public class PointsToGraph {

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
    private final ConcurrentIntMap<InstanceKeyRecency> instanceKeyDictionary = new SimpleConcurrentIntMap();
    /**
     * Dictionary for mapping InstanceKeys to ints
     */
    private final ConcurrentMap<InstanceKeyRecency, Integer> reverseInstanceKeyDictionary = new ConcurrentHashMap<>();

    /**
     * Dictionary to record the concrete type of instance keys.
     */
    final ConcurrentIntMap<IClass> concreteTypeDictionary = new SimpleConcurrentIntMap();

    /**
     * GraphNode counter, for unique integers for GraphNodes
     */
    private final AtomicInteger graphNodeCounter = new AtomicInteger(0);

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private final ConcurrentMap<PointsToGraphNode, Integer> reverseGraphNodeDictionary = new ConcurrentHashMap<>();

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private final ConcurrentIntMap<PointsToGraphNode> graphNodeDictionary = new SimpleConcurrentIntMap();



    /* ***************************************************************************
     *
     * The Points To graph itself.
     *
     * XXX TODO UPDATE THIS DOCUMENTATION
     * The pointsTo map records the actual points to sets,
     * and the isUnfilteredSubsetOf, isFilteredSubsetOf relations record subset
     * relations between PointsToGraphNodes, in order to let us more efficiently
     * propagate changes to the graph.
     *
     * Moreover, since cycles may be collapsed, we use the map representative to
     * record the "representative" node for nodes that have been collapsed. Note
     * that we maintain the invariants:
     *   - if a \in domain(representative) then a \not\in domain(pointsTo)
     *   - if a \in domain(representative) then a \not\in domain(isUnfilteredSubsetOf)
     *   - if a \in domain(representative) then a \not\in domain(isFilteredSubsetOf)
     *
     * Note also that it is possible that (a,b) \in representative and b \in domain(representative),
     * i.e., the representative of a collapsed node may itself get collapsed.
     */

    /**
     * Map from PointsToGraphNode to sets of InstanceKeys (where PointsToGraphNodes and InstanceKeys are represented by
     * ints). These are the flow-insensitive facts, i.e., they hold true at all program points.
     */
    private final ConcurrentIntMap<MutableIntSet> pointsToFI = new SimpleConcurrentIntMap<>();

    /**
     * Map from PointsToGraphNode to InstanceKeys, including the program points (actually, the interprogrampoint
     * replicas) at which they are valid. These are the flow sensitive points to information. if (s,t,ps) \in deltaFS,
     * and p \in ps, then s points to t at program point p.
     */
    private final ConcurrentIntMap<ConcurrentIntMap<ProgramPointSetClosure>> pointsToFS = new SimpleConcurrentIntMap<>();

    /**
     * if "a isUnfilteredSubsetOf b" then the points to set of a is always a subset of the points to set of b.
     */
    private final IntRelation isUnfilteredSubsetOf = new IntRelation();

    /**
     * if "a isFilteredSubsetOf b with filter" then the filter(pointsTo(a)) is a subset of pointsTo(b).
     */
    private final SetAnnotatedIntRelation<TypeFilter> isFilteredSubsetOf = new SetAnnotatedIntRelation<>();

    /**
     * if "a isFlowSensSubsetOf b with pps" then for all ippr \in pps, we have pointsTo(a, ippr) is a subset of
     * pointsTo(b, ippr). At least one of a and b is a flow-sensitive PointsToGraphNode. That is, if ippr \in pps, then
     * if a is flow sensitive, we have: pointsToFS(a, ippr) \subseteq pointsToFI(b) and if b is flow sensitive we have
     * pointsToFI(a) \subseteq pointsToFS(b, ippr)
     */
    private final AnnotatedIntRelation<ExplicitProgramPointSet> isFlowSensSubsetOf = new AnnotatedIntRelation<ExplicitProgramPointSet>() {
        @Override
        protected ExplicitProgramPointSet createInitialAnnotation() {
            return new ExplicitProgramPointSet();
        }

        @Override
        protected boolean merge(ExplicitProgramPointSet existing, ExplicitProgramPointSet annotation) {
            return existing.addAll(annotation);
        }

    };

    /**
     * Map from PointsToGraphNodes to PointsToGraphNodes, indicating which nodes have been collapsed (due to being in
     * cycles) and which node now represents them.
     */
    private final ConcurrentIntMap<Integer> representative = new SimpleConcurrentIntMap<>();


    /* ***************************************************************************
     *
     * Reachable contexts and entry points, and call graph representations.
     *
     */

    /**
     * The contexts that a method may appear in.
     */
    private final ConcurrentMap<IMethod, Set<Context>> reachableContexts = new ConcurrentHashMap<>();

    /**
     * The classes that will be loaded (i.e., we need to analyze their static
     * initializers).
     */
    private final Set<IMethod> classInitializers = AnalysisUtil.createConcurrentSet();

    /**
     * Entry points added during the pointer analysis
     */
    private final Set<IMethod> entryPoints = AnalysisUtil.createConcurrentSet();

    /**
     * A thread-safe representation of the call graph that we populate during the analysis, and then convert it to a
     * HafCallGraph later.
     */
    private final ConcurrentMap<OrderedPair<CallSiteProgramPoint, Context>, Set<OrderedPair<IMethod, Context>>> callGraphMap = new ConcurrentHashMap<>();

    /**
     * A thread-safe representation of the call graph that we populate during the analysis, and then convert it to a
     * HafCallGraph later.
     */
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<CallSiteProgramPoint, Context>>> callGraphReverseMap = new ConcurrentHashMap<>();

    private HafCallGraph callGraph = null;

    /**
     * Heap abstraction factory.
     */
    private final RecencyHeapAbstractionFactory haf;


    private final DependencyRecorder depRecorder;


    /**
     * Is the graph still being constructed, or is it finished? Certain operations should be called only once the graph
     * has finished being constructed.
     */
    private boolean graphFinished = false;

    private int outputLevel = 0;

    public PointsToGraph(StatementRegistrar registrar, RecencyHeapAbstractionFactory haf, DependencyRecorder depRecorder) {
        this.depRecorder = depRecorder;

        this.haf = haf;

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

    //    // Return the immediate supersets of PointsToGraphNode n. That is, any node m such that n is an immediate subset of m
    //    public OrderedPair<IntSet, IntMap<Set<TypeFilter>>> immediateSuperSetsOf(int n) {
    //        n = this.getRepresentative(n);
    //
    //        IntSet unfilteredsupersets = this.isUnfilteredSubsetOf.forward(n);
    //        IntMap<Set<TypeFilter>> supersets = this.isFilteredSubsetOf.forward(n);
    //        this.
    //        return new OrderedPair<>(unfilteredsupersets, supersets);
    //    }
    //


    /*PointsToGraphNode*/Integer getImmediateRepresentative(/*PointsToGraphNode*/int n) {
        return this.representative.get(n);
    }

    public/*PointsToGraphNode*/int getRepresentative(/*PointsToGraphNode*/int n) {
        int orig = n;
        int rep;
        Integer x = n;
        do {
            rep = x;
            x = this.representative.get(x);
        } while (x != null);
        return rep;
    }

    /**
     * Add an edge from node to heapContext in the graph.
     *
     * @param node
     * @param heapContext
     * @return
     */
    public GraphDelta addEdge(PointsToGraphNode node, InstanceKeyRecency heapContext, InterProgramPointReplica ippr) {
        assert node != null && heapContext != null;

        assert !node.isFlowSensitive() : "Base nodes (i.e., nodes that point directly to heap contexts) should be flow-insensitive";

        int n = lookupDictionary(node);

        n = this.getRepresentative(n);

        Integer h = this.reverseInstanceKeyDictionary.get(heapContext);
        if (h == null) {
            // not in the dictionary yet
            h = this.instanceKeyCounter.getAndIncrement();
            // try a put if absent
            Integer existing = this.reverseInstanceKeyDictionary.putIfAbsent(heapContext, h);
            if (existing == null) {
                // we succeeded, and h is now the number for heapcontext.
                this.instanceKeyDictionary.put(h, heapContext);
                this.concreteTypeDictionary.put(h, heapContext.getConcreteType());
            }
            else {
                h = existing;
            }
        }

        GraphDelta delta = new GraphDelta(this);
        if (!this.pointsToSetFI(n).contains(h)) {
            IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
            addToSetAndSupersets(delta,
                                 n,
                                 node.isFlowSensitive(),
                                 null,
                                 SparseIntSet.singleton(h),
                                 MutableSparseIntSet.makeEmpty(),
                                 new IntStack(),
                                 new Stack<Set<TypeFilter>>(),
                                 new Stack<ExplicitProgramPointSet>(),
                                 toCollapse);
            // XXX maybe enable later.
            //collapseCycles(toCollapse, delta);
        }
        return delta;
    }

    public boolean isFlowSensitivePointsToGraphNode(/*PointsToGraphNode*/int n) {
        return this.graphNodeDictionary.get(n).isFlowSensitive();
    }

    private void collapseCycles(IntMap<MutableIntSet> toCollapse, GraphDelta delta) {
        MutableIntSet collapsed = MutableSparseIntSet.makeEmpty();
        IntIterator iter = toCollapse.keyIterator();
        while (iter.hasNext()) {
            int rep = iter.next();
            rep = this.getRepresentative(rep); // it is possible that rep was already collapsed to something else. So we get the representative of it to shortcut things.
            IntIterator collapseIter = toCollapse.get(rep).intIterator();
            while (collapseIter.hasNext()) {
                int n = collapseIter.next();
                if (collapsed.contains(n)) {
                    // we have already collapsed n with something. let's skip it.
                    continue;
                }
                collapsed.add(n);
                //XXXthis.collapseNodes(n, rep);
                delta.collapseNodes(n, rep);
            }
        }
    }

    protected int lookupDictionary(PointsToGraphNode node) {
        Integer n = this.reverseGraphNodeDictionary.get(node);
        if (n == null) {
            // not in the dictionary yet
            n = graphNodeCounter.getAndIncrement();
            Integer existing = this.reverseGraphNodeDictionary.putIfAbsent(node, n);
            if (existing != null) {
                return existing;
            }
            else {
                // we were the first to put it in.
                this.graphNodeDictionary.put(n, node);
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
    public GraphDelta copyEdges(PointsToGraphNode source, InterProgramPointReplica sourceIppr,
                                PointsToGraphNode target, InterProgramPointReplica targetIppr) {
        assert !(source.isFlowSensitive() && source.isFlowSensitive()) : "At most one of the source and target should be flow sensitive";

        int s = this.getRepresentative(lookupDictionary(source));
        int t = this.getRepresentative(lookupDictionary(target));

        GraphDelta changed = new GraphDelta(this);
        if (s == t) {
            // don't bother adding
            return changed;
        }

        // source is a subset of target, target is a superset of source.
        if (!source.isFlowSensitive() && !target.isFlowSensitive()) {
            // neither source nor target is flow sensitive, so let's ignore ippr
            if (isUnfilteredSubsetOf.add(s, t)) {
                computeDeltaForAddedSubsetRelation(changed, s, false, null, t, false, null);
            }
        }
        else {
            // exactly one of source and target is flow sensitive.
            assert !(source.isFlowSensitive() && target.isFlowSensitive());

            // Choose the ippr to be the source or the target ippr, based on which is flow sensitive.
            InterProgramPointReplica ippr = source.isFlowSensitive() ? sourceIppr : targetIppr;

            ExplicitProgramPointSet pps = isFlowSensSubsetOf.forward(s).get(t);
            if (pps == null || !pps.contains(ippr)) {
                // this is a new subset relation!
                computeDeltaForAddedSubsetRelation(changed,
                                                   s,
                                                   source.isFlowSensitive(),
                                                   null,
                                                   t,
                                                   target.isFlowSensitive(),
                                                   ippr);
            }
        }
        return changed;
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
        assert !source.isFlowSensitive() && !target.isFlowSensitive() : "Filtered subset relations can only be on flow-insensitive nodes";
        // source is a subset of target, target is a subset of source.
        if (TypeFilter.IMPOSSIBLE.equals(filter)) {
            // impossible filter! Don't bother adding the relationship.
            return new GraphDelta(this);
        }

        int s = this.getRepresentative(lookupDictionary(source));
        int t = this.getRepresentative(lookupDictionary(target));

        if (s == t) {
            // don't bother adding
            return new GraphDelta(this);
        }

        GraphDelta changed = new GraphDelta(this);
        if (isFilteredSubsetOf.add(s, t, filter)) {
            computeDeltaForAddedSubsetRelation(changed, s, false, filter, t, false, null);
        }
        return changed;
    }

    /*
     * This method adds everything in s that satisfies filter to t, in both the cache and the GraphDelta,
     * and recurses.
     *
     * If ippr is not null it means either:
     *    pointsToFS(source, ippr) \subseteq pointsToFI(target)   (if source is flow sensitive)
     * or
     *    pointsToFI(source) \subseteq pointsToFS(target, ippr)   (if target is flow sensitive)
     */
    private void computeDeltaForAddedSubsetRelation(GraphDelta changed, /*PointsToGraphNode*/int source,
                                                    boolean sourceIsFlowSensitive, TypeFilter filter, /*PointsToGraphNode*/
                                                    int target, boolean targetIsFlowSensitive,
                                                    InterProgramPointReplica ippr) {
        assert !(sourceIsFlowSensitive && targetIsFlowSensitive) : "At most one can be flow sensitive";
        assert !(sourceIsFlowSensitive || targetIsFlowSensitive) || filter == null : "If either is flow sensitive then filter must be null";
        assert !(sourceIsFlowSensitive || targetIsFlowSensitive) || ippr != null : "If either is flow sensitive then ippr must be non null";

        // go through the points to set of source, and add anything that target doesn't already point to.
        IntSet diff = this.getDifference(source, sourceIsFlowSensitive, filter, target, targetIsFlowSensitive, ippr);

        // Now take care of all the supersets of target...
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
        addToSetAndSupersets(changed,
                             target,
                             targetIsFlowSensitive,
                             targetIsFlowSensitive ? ExplicitProgramPointSet.singleton(ippr) : null,
                             diff,
                             MutableSparseIntSet.makeEmpty(),
                             new IntStack(),
                             new Stack<Set<TypeFilter>>(),
                             new Stack<ExplicitProgramPointSet>(),
                             toCollapse);
        //XXX maybe enable later.
        //collapseCycles(toCollapse, changed);

    }

    /**
     * Add set setToAdd to target. If target is flow sensitive, then the setToAdd will be added at all program points
     * ippr \in targetPoints.
     *
     * @param changed the GraphDelta we will use to record changes we make to the pointsto graph.
     * @param target
     * @param targetIsFlowSensitive
     * @param targetPoints
     * @param setToAdd
     * @param currentlyAdding
     * @param currentlyAddingStack
     * @param filterStack
     * @param programPointStack
     * @param toCollapse
     */
    private void addToSetAndSupersets(GraphDelta changed, /*PointsToGraphNode*/int target,
                                      boolean targetIsFlowSensitive, ExplicitProgramPointSet targetPoints,
                                      IntSet setToAdd,
                                      MutableIntSet currentlyAdding, IntStack currentlyAddingStack,
                                      Stack<Set<TypeFilter>> filterStack,
                                      Stack<ExplicitProgramPointSet> programPointStack,
                                      IntMap<MutableIntSet> toCollapse) {

        assert targetIsFlowSensitive || targetPoints == null : "If target is not flow sensitive then targetPoints must be null";

        // Handle detection of cycles.
        if (currentlyAdding.contains(target)) {
            // we detected a cycle!
            int foundAt = -1;
            boolean hasMeaningfulFilter = false;
            boolean isFlowSensitive = false;
            for (int i = 0; !hasMeaningfulFilter && i < currentlyAdding.size(); i++) {
                if (foundAt < 0 && currentlyAddingStack.get(i) == target) {
                    foundAt = i;
                }
                hasMeaningfulFilter |= filterStack.get(i) != null;
                isFlowSensitive |= programPointStack.get(i) != null; // for the moment, we won't try to do anything smart with flow-sensitive cycles. Could do a lot better...
            }
            if (!hasMeaningfulFilter && !isFlowSensitive) {
                // we can collapse some nodes together!
                MutableIntSet toCollapseSet = toCollapse.get(target);
                if (toCollapseSet == null) {
                    toCollapseSet = MutableSparseIntSet.makeEmpty();
                    toCollapse.put(target, toCollapseSet);
                }
                for (int i = foundAt + 1; i < filterStack.size(); i++) {
                    toCollapseSet.add(currentlyAddingStack.get(i));
                }
            }
            assert !changed.addAllToSet(target, targetIsFlowSensitive, targetPoints, setToAdd) : "Shouldn't be anything left to add by this point";
        }

        // Now we actually add the set to the target, both in the cache, and in the GraphDelta
        if (!changed.addAllToSet(target, targetIsFlowSensitive, targetPoints, setToAdd)) {
            return;
        }
        this.addAllToSet(target, targetIsFlowSensitive, targetPoints, setToAdd);


        // We added at least one element to target, so let's recurse on the immediate supersets of target.
        currentlyAdding.add(target);
        currentlyAddingStack.push(target);
        programPointStack.push(null);

        // propagate to the immediate supersets

        // First, do the unfiltered flow-insensitive subset relations
        IntSet unfilteredSupersets = this.isUnfilteredSubsetOf.forward(target);
        IntIterator iter = unfilteredSupersets == null ? EmptyIntIterator.instance()
                : unfilteredSupersets.intIterator();
        while (iter.hasNext()) {
            int m = iter.next();
            assert !isFlowSensitivePointsToGraphNode(m);

            propagateDifference(changed,
                                m,
                                false, // the target m isn't flow sensitive
                                null,
                                null,
                                setToAdd,
                                currentlyAdding,
                                currentlyAddingStack,
                                filterStack,
                                programPointStack,
                                toCollapse);
        }

        // Second, do the filtered flow-insensitive subset relations
        IntMap<Set<TypeFilter>> filteredSupersets = this.isFilteredSubsetOf.forward(target);
        iter = filteredSupersets == null ? EmptyIntIterator.instance() : filteredSupersets.keyIterator();
        while (iter.hasNext()) {
            int m = iter.next();
            assert !isFlowSensitivePointsToGraphNode(m);

            Set<TypeFilter> filterSet = filteredSupersets.get(m);
            // it is possible that the filter set is empty, due to race conditions.
            // No trouble, we will just ignore it, and pretend we got in there before
            // the relation between target and m was created.
            if (!filterSet.isEmpty()) {
                propagateDifference(changed,
                                    m,
                                    false, // the target isn't flow sensitive
                                    filterSet,
                                    null, // no targetprogrampionts
                                    setToAdd,
                                    currentlyAdding,
                                    currentlyAddingStack,
                                    filterStack,
                                    programPointStack,
                                    toCollapse);
            }
        }

        // Third, do the unfiltered flow-sensitive subset relations
        IntMap<ExplicitProgramPointSet> flowSensSupersets = this.isFlowSensSubsetOf.forward(target);
        iter = flowSensSupersets == null ? EmptyIntIterator.instance() : flowSensSupersets.keyIterator();

        while (iter.hasNext()) {
            int m = iter.next();
            boolean mIsFlowSensitive = isFlowSensitivePointsToGraphNode(m);
            assert !(targetIsFlowSensitive && mIsFlowSensitive);

            ExplicitProgramPointSet ppSet = flowSensSupersets.get(m);
            // "target isFlowSensSubsetOf m with ppSet"
            if (targetIsFlowSensitive) {
                // For all p \in ppSet, we want pointsToFS(target, p) \subset pointsToFI(m).
                // We only have something to do if targetPoints intersects with ppSet.
                if (!ppSet.containsAny(targetPoints)) {
                    continue;
                }
            }

            ExplicitProgramPointSet ppSetToAdd = targetIsFlowSensitive ? null : ppSet;
            programPointStack.pop();
            programPointStack.push(ppSetToAdd);

            propagateDifference(changed,
                                m,
                                mIsFlowSensitive,
                                null,
                                ppSetToAdd,
                                setToAdd,
                                currentlyAdding,
                                currentlyAddingStack,
                                filterStack,
                                programPointStack,
                                toCollapse);

        }
        currentlyAdding.remove(target);
        currentlyAddingStack.pop();
        programPointStack.pop();

    }

    /**
     * propagateDifference takes the set setToAdd and adds it to the target PointsToGraphNode. If there are filters then
     * target should be flow insensitive, and setToAdd will be filtered according to the filters. If target is flow
     * sensitive, then setToAdd will be added to all pointsTo sets pointsToFS(target, ippr) for ippr \in targetPoints.
     *
     * @param changed the GraphDelta we will use to record changes we make to the pointsto graph.
     * @param target
     * @param targetIsFlowSensitive
     * @param filters
     * @param targetPoints
     * @param setToAdd
     * @param currentlyAdding
     * @param currentlyAddingStack
     * @param filterStack
     * @param programPointStack
     * @param toCollapse
     */
    private void propagateDifference(GraphDelta changed, /*PointsToGraphNode*/int target, boolean targetIsFlowSensitive, Set<TypeFilter> filters,
                                     ExplicitProgramPointSet targetPoints,
                                      IntSet setToAdd, MutableIntSet currentlyAdding,
                                      IntStack currentlyAddingStack,
                                     Stack<Set<TypeFilter>> filterStack,
                                     Stack<ExplicitProgramPointSet> programPointStack, IntMap<MutableIntSet> toCollapse) {

        assert !(targetIsFlowSensitive) || filters == null : "If target is flow sensitive then filter must be null";
        assert !(targetIsFlowSensitive) || targetPoints != null && !targetPoints.isEmpty() : "If target is flow sensitive then we must have target program points";

        IntIterator iter = filters == null ? setToAdd.intIterator() : new FilteredIterator(setToAdd.intIterator(), filters);

        // The set of elements that will be added to the superset.
        IntSet diff = this.getDifference(iter, target, targetIsFlowSensitive, targetPoints);

        filterStack.push(filters);
        addToSetAndSupersets(changed,
                             target,
                             targetIsFlowSensitive,
                             targetPoints,
                             diff,
                             currentlyAdding,
                             currentlyAddingStack,
                             filterStack,
                             programPointStack,
                             toCollapse);
        filterStack.pop();
    }

    /**
     * Provide an iterator for the things that n points to. Note that we may not return a set, i.e., some InstanceKeys
     * may be returned multiple times. XXX we may change this in the future...
     *
     * @param n
     * @return
     */
    //XXX Can uncomment this after development
    //    public Iterator<? extends InstanceKey> pointsToIterator(PointsToGraphNode n) {
    //        assert this.graphFinished : "Can only get a points to set without an originator if the graph is finished";
    //        return pointsToIterator(n, null, null);
    //    }
    //
    //    public Iterator<? extends InstanceKey> pointsToIterator(PointsToGraphNode n, InterProgramPointReplica ippr) {
    //        assert this.graphFinished : "Can only get a points to set without an originator if the graph is finished";
    //        return pointsToIterator(n, ippr, null);
    //    }
    //
    public Iterator<InstanceKeyRecency> pointsToIterator(PointsToGraphNode n, InterProgramPointReplica ippr,
                                                            StmtAndContext originator) {
        assert this.graphFinished || originator != null;
        return new IntToInstanceKeyIterator(this.pointsToIntIterator(lookupDictionary(n), ippr, originator));
    }

    public int graphNodeToInt(PointsToGraphNode n) {
        return lookupDictionary(n);
    }

    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n, InterProgramPointReplica ippr,
                                           StmtAndContext originator) {
        n = this.getRepresentative(n);
        if (originator != null) {
            // If the originating statement is null then the graph is finished and there is no need to record this read
            this.recordRead(n, originator);
        }
        if (isFlowSensitivePointsToGraphNode(n)) {
            return new ProgramPointIntIterator(pointsToFS.get(n), ippr);
        }
        return this.pointsToSetFI(n).intIterator();
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
     * XXXX DOCO TODO.
     *
     */
    @SuppressWarnings("deprecation")
    public boolean addCall(CallSiteProgramPoint callSite,
                           Context callerContext, IMethod callee,
                           Context calleeContext) {
        OrderedPair<CallSiteProgramPoint, Context> callerPair = new OrderedPair<>(callSite, callerContext);
        OrderedPair<IMethod, Context> calleePair = new OrderedPair<>(callee, calleeContext);

        Set<OrderedPair<IMethod, Context>> s = this.callGraphMap.get(callerPair);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = this.callGraphMap.putIfAbsent(callerPair,
                                                                                                                     s);
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
        if (this.outputLevel >= 1) {
            System.err.println("RECORDING: " + callee + " in " + calleeContext
                               + " hc " + calleeContext);
        }
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

    private <T> ConcurrentIntMap<T> getOrCreateIntMap(int key, ConcurrentIntMap<ConcurrentIntMap<T>> map) {
        ConcurrentIntMap<T> set = map.get(key);
        if (set == null) {
            set = new SimpleConcurrentIntMap<>();
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

    /**
     * Get the procedure call graph
     *
     * @return call graph
     */
    public CallGraph getCallGraph() {
        assert graphFinished;
        if (this.callGraph != null) {
            return this.callGraph;
        }

        // Construct the call graph.
        HafCallGraph callGraph = new HafCallGraph(this.haf);
        this.callGraph = callGraph;
        try {
            for (OrderedPair<CallSiteProgramPoint, Context> callerPair : this.callGraphMap.keySet()) {
                CallSiteProgramPoint caller = callerPair.fst();
                Context callerContext = callerPair.snd();
                CGNode src = callGraph.findOrCreateNode(caller.containingProcedure(), callerContext);
                Set<OrderedPair<IMethod, Context>> s = this.callGraphMap.get(callerPair);
                for (OrderedPair<IMethod, Context> calleePair : s) {
                    IMethod callee = calleePair.fst();
                    Context calleeContext = calleePair.snd();

                    CGNode dst = callGraph.findOrCreateNode(callee, calleeContext);

                    // We are building a call graph so it is safe to call this "deprecated" method
                    src.addTarget(caller.getReference(), dst);

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


    public void setOutputLevel(int outputLevel) {
        this.outputLevel = outputLevel;
    }

    public int clinitCount = 0;

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
                this.clinitCount++;
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
            this.clinitCount++;
        }
        return changed;
    }

    class FilteredIntSet extends AbstractIntSet implements IntSet {
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
            this.s = allImpossible ? EmptyIntSet.instance : s;
        }

        @Override
        public IntIterator intIterator() {
            return new FilteredIterator(this.s.intIterator(), this.filters);
        }

        @Override
        public boolean contains(int o) {
            return this.s.contains(o) && satisfiesAny(filters, PointsToGraph.this.concreteTypeDictionary.get(o));
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

        @Override
        public boolean hasNext() {
            while (this.next < 0 && this.iter.hasNext()) {
                int i = this.iter.next();
                IClass type = PointsToGraph.this.concreteTypeDictionary.get(i);
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

    class ProgramPointIntIterator implements IntIterator {
        private final IntIterator iter;
        private final IntMap<ProgramPointSetClosure> ppmap;
        private final InterProgramPointReplica ippr;
        private int next = -1;

        ProgramPointIntIterator(IntMap<ProgramPointSetClosure> ppmap, InterProgramPointReplica ippr) {
            this.iter = ppmap == null ? EmptyIntIterator.instance() : ppmap.keyIterator();
            this.ppmap = ppmap;
            this.ippr = ippr;
        }

        @Override
        public boolean hasNext() {
            while (this.next < 0 && this.iter.hasNext()) {
                int i = this.iter.next();
                ProgramPointSetClosure pps;
                if (ippr == null || ((pps = ppmap.get(i)) != null && pps.contains(ippr))) {
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

    class SortedIntSetUnion extends AbstractIntSet implements IntSet {
        final IntSet a;
        final IntSet b;

        SortedIntSetUnion(IntSet a, IntSet b) {
            this.a = a;
            this.b = b;
            if (a == null) {
                throw new IllegalArgumentException("a is null");
            }
            if (b == null) {
                throw new IllegalArgumentException("b is null");
            }
        }

        @Override
        public boolean isEmpty() {
            return this.a.isEmpty() && this.b.isEmpty();
        }

        @Override
        public boolean contains(int x) {
            return this.a.contains(x) || this.b.contains(x);
        }

        @Override
        public IntIterator intIterator() {
            return new SortedIntSetUnionIterator(this.a.intIterator(),
                                                 this.b.intIterator());
        }

    }

    class SortedIntSetUnionIterator implements IntIterator {
        final IntIterator a;
        final IntIterator b;
        int aNext = 0;
        int bNext = 0;
        boolean aNextValid = false;
        boolean bNextValid = false;

        SortedIntSetUnionIterator(IntIterator a, IntIterator b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean hasNext() {
            if (!this.aNextValid && this.a.hasNext()) {
                this.aNext = this.a.next();
                this.aNextValid = true;
            }
            if (!this.bNextValid && this.b.hasNext()) {
                this.bNext = this.b.next();
                this.bNextValid = true;
            }
            return this.aNextValid || this.bNextValid;
        }

        @Override
        public int next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            // at least one of aNext and bNext is non-null
            if (!this.aNextValid) {
                this.bNextValid = false;
                return this.bNext;
            }
            if (!this.bNextValid) {
                this.aNextValid = false;
                return this.aNext;
            }
            // both are non-null
            if (this.aNext == this.bNext) {
                // they are the same value
                this.aNextValid = this.bNextValid = false;
                return this.aNext;
            }
            if (this.aNext < this.bNext) {
                this.aNextValid = false;
                return this.aNext;
            }
            this.bNextValid = false;
            return this.bNext;
        }

    }

    static Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> composeIterators(Set<PointsToGraphNode> unfilteredSet,
                                                                                 Set<OrderedPair<PointsToGraphNode, TypeFilter>> filteredSet) {
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> unfilteredIterator = unfilteredSet == null
                ? null : new LiftUnfilteredIterator(unfilteredSet.iterator());
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> filteredIterator = filteredSet == null
                ? null : filteredSet.iterator();

        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter;
        if (unfilteredIterator == null) {
            if (filteredIterator == null) {
                iter = Collections.<OrderedPair<PointsToGraphNode, TypeFilter>> emptyIterator();
            }
            else {
                iter = filteredIterator;
            }
        }
        else {
            if (filteredIterator == null) {
                iter = unfilteredIterator;
            }
            else {
                iter = new ComposedIterators<>(unfilteredIterator,
                        filteredIterator);
            }
        }
        return iter;
    }

    public static class ComposedIterators<T> implements Iterator<T> {
        Iterator<T> iter1;
        Iterator<T> iter2;

        public ComposedIterators(Iterator<T> iter1, Iterator<T> iter2) {
            this.iter1 = iter1;
            this.iter2 = iter2;
        }

        @Override
        public boolean hasNext() {
            return this.iter1 != null && this.iter1.hasNext()
                    || this.iter2.hasNext();
        }

        @Override
        public T next() {
            if (this.iter1 != null) {
                if (this.iter1.hasNext()) {
                    return this.iter1.next();
                }
                this.iter1 = null;
            }
            return this.iter2.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public static class LiftUnfilteredIterator implements
    Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> {
        private final Iterator<PointsToGraphNode> iter;

        public LiftUnfilteredIterator(Iterator<PointsToGraphNode> iter) {
            this.iter = iter;
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public OrderedPair<PointsToGraphNode, TypeFilter> next() {
            return new OrderedPair<>(this.iter.next(), (TypeFilter) null);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public class IntToInstanceKeyIterator implements Iterator<InstanceKeyRecency> {
        private final IntIterator iter;

        public IntToInstanceKeyIterator(IntIterator iter) {
            this.iter = iter;
        }

        @Override
        public boolean hasNext() {
            return this.iter.hasNext();
        }

        @Override
        public InstanceKeyRecency next() {
            InstanceKeyRecency ik = PointsToGraph.this.instanceKeyDictionary.get(this.iter.next());
            assert ik != null;
            return ik;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    /**
     * Return the set of InstanceKeys that are in source (and satisfy filter)
     * but are not in target.
     *
     * @param source
     * @param filter
     * @param target
     * @return
     */
    IntSet getDifference(/*PointsToGraphNode*/int source, boolean sourceIsFlowSensitive, TypeFilter filter, /*PointsToGraphNode*/
                         int target, boolean targetIsFlowSensitive,
                         InterProgramPointReplica ippr) {
        source = this.getRepresentative(source);

        IntIterator srcIter;
        if (ippr == null || !sourceIsFlowSensitive) {

            IntSet s = this.pointsToSetFI(source);
            if (filter == null) {
                srcIter = s.intIterator();
            }
            else {
                srcIter = new FilteredIterator(s.intIterator(), filter);
            }
        }
        else {
            assert sourceIsFlowSensitive;
            srcIter = new ProgramPointIntIterator(pointsToFS.get(source), ippr);
        }
        return this.getDifference(srcIter, target, targetIsFlowSensitive, ExplicitProgramPointSet.singleton(ippr));

    }

    private IntSet getDifference(IntIterator srcIter, /*PointsToGraphNode*/int target, boolean targetIsFlowSensitive,
                                 ExplicitProgramPointSet addAtPoints) {
        assert !targetIsFlowSensitive || addAtPoints != null && !addAtPoints.isEmpty() : "If target is flow sensitive, then addAtPoints must be nonempty";
        target = this.getRepresentative(target);

        if (!srcIter.hasNext()) {
            // nothing in there, return an empty set.
            return EmptyIntSet.instance;
        }

        MutableIntSet s = MutableSparseIntSet.makeEmpty();

        if (!targetIsFlowSensitive) {
            IntSet targetSet = this.pointsToSetFI(target);

            while (srcIter.hasNext()) {
                int i = srcIter.next();
                if (!targetSet.contains(i)) {
                    s.add(i);
                }
            }
        }
        else {
            ConcurrentIntMap<ProgramPointSetClosure> m = this.pointsToSetFS(target);
            while (srcIter.hasNext()) {
                int i = srcIter.next();
                ProgramPointSetClosure pps = m.get(i);
                if (pps == null || !pps.containsAll(addAtPoints)) {
                    // we have i \not\in pointsTo(target, ippr) for some ippr \in addAtPoints
                    s.add(i);
                }
            }

        }
        return s;

    }

    public int numPointsToGraphNodes() {
        return this.pointsToFI.size() + this.pointsToFS.size();
    }
    private MutableIntSet pointsToSetFI(/*PointsToGraphNode*/int n) {
        MutableIntSet s = this.pointsToFI.get(n);
        if (s == null) {
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet ex = this.pointsToFI.putIfAbsent(n, s);
            if (ex != null) {
                // someone beat us to it!
                s = ex;
            }
        }
        return s;
    }

    private ConcurrentIntMap<ProgramPointSetClosure> pointsToSetFS(/*PointsToGraphNode*/int n) {
        ConcurrentIntMap<ProgramPointSetClosure> s = this.pointsToFS.get(n);
        if (s == null) {
            s = new SimpleConcurrentIntMap<>();
            ConcurrentIntMap<ProgramPointSetClosure> ex = this.pointsToFS.putIfAbsent(n, s);
            if (ex != null) {
                // someone beat us to it!
                s = ex;
            }
        }
        return s;
    }

    protected boolean addAllToSet(/*PointsToGraphNode*/int n, boolean nIsFlowSensitive,
                                  ExplicitProgramPointSet ppsToAdd,
                                  IntSet set) {
        if (set.isEmpty()) {
            return false;
        }

        if (!nIsFlowSensitive) {
            assert !isFlowSensitivePointsToGraphNode(n);
            return pointsToSetFI(n).addAll(set);
        }
        // flow sensitive!
        boolean changed = false;
        IntMap<ProgramPointSetClosure> m = pointsToSetFS(n);
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            int to = iter.next();
            changed |= addProgramPoints(m, to, ppsToAdd);
        }
        return changed;
    }

    private static boolean addProgramPoints(IntMap<ProgramPointSetClosure> m, /*PointsToGraphNode*/int to,
                                            ExplicitProgramPointSet toAdd) {
        ProgramPointSetClosure p = m.get(to);
        if (p == null) {
            p = new ProgramPointSetClosure(to);
            m.put(to, p);
        }
        return p.addAll(toAdd);
    }

    public int cycleRemovalCount() {
        return this.representative.size();
    }

    public void findCycles() {
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();

        MutableIntSet visited = MutableSparseIntSet.makeEmpty();
        IntIterator iter = this.isUnfilteredSubsetOf.domain();
        while (iter.hasNext()) {
            int n = iter.next();
            this.findCycles(n, visited, MutableSparseIntSet.makeEmpty(), new IntStack(), toCollapse);
        }

        MutableIntSet collapsed = MutableSparseIntSet.makeEmpty();
        IntIterator repIter = toCollapse.keyIterator();
        while (repIter.hasNext()) {
            int rep = repIter.next();
            rep = this.getRepresentative(rep); // it is possible that rep was already collapsed to something else. So we get the representative of it to shortcut things.
            IntIterator nIter = toCollapse.get(rep).intIterator();
            while (nIter.hasNext()) {
                int n = nIter.next();
                if (collapsed.contains(n)) {
                    // we have already collapsed n with something. let's skip it.
                    continue;
                }
                collapsed.add(n);
                //this.collapseNodes(n, rep);
            }
        }

    }

    private void findCycles(/*PointsToGraphNode*/int n, MutableIntSet visited, MutableIntSet currentlyVisiting,
                            IntStack currentlyVisitingStack, IntMap<MutableIntSet> toCollapse) {
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
            MutableIntSet toCollapseSet = toCollapse.get(n);
            if (toCollapseSet == null) {
                toCollapseSet = MutableSparseIntSet.makeEmpty();
                toCollapse.put(n, toCollapseSet);
            }
            for (int i = foundAt + 1; i < currentlyVisitingStack.size(); i++) {
                toCollapseSet.add(currentlyVisitingStack.get(i));
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
            children = EmptyIntSet.instance;
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

    }
}
