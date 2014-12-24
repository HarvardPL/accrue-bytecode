package analysis.pointer.graph;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import util.OrderedPair;
import util.intmap.ConcurrentIntMap;
import util.intmap.DenseIntMap;
import util.intmap.IntMap;
import util.intmap.ReadOnlyConcurrentIntMap;
import util.intmap.SimpleConcurrentIntMap;
import util.intmap.SparseIntMap;
import util.intset.EmptyIntSet;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.DependencyRecorder;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisHandle;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.graph.AnnotatedIntRelation.SetAnnotatedIntRelation;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
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
 * Graph mapping local variables (in a particular context) and fields to abstract heap locations (representing zero or
 * more actual heap locations)
 */
public class PointsToGraph {

    public static final String ARRAY_CONTENTS = "[contents]";

    private final StatementRegistrar registrar;

    public final ProgramPointReachability ppReach;

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
    private ConcurrentIntMap<InstanceKeyRecency> instanceKeyDictionary = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * Dictionary for mapping InstanceKeys to ints
     */
    private ConcurrentMap<InstanceKeyRecency, Integer> reverseInstanceKeyDictionary = AnalysisUtil.createConcurrentHashMap();

    /**
     * Dictionary to record the concrete type of instance keys.
     */
    ConcurrentIntMap<IClass> concreteTypeDictionary = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * GraphNode counter, for unique integers for GraphNodes
     */
    private final AtomicInteger graphNodeCounter = new AtomicInteger(0);

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private ConcurrentMap<PointsToGraphNode, Integer> reverseGraphNodeDictionary = AnalysisUtil.createConcurrentHashMap();

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private ConcurrentIntMap<PointsToGraphNode> graphNodeDictionary = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /* ***************************************************************************
     *
     * Record allocation sites of an InstanceKeyRecency.
     */
    final ConcurrentIntMap<Set<ProgramPointReplica>> allocationSites = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /* ***************************************************************************
     *
     * The Points To graph itself.
     *
     * The pointsToFI map records the actual flow-insensitive points to sets.
     * The pointsToFS map records the actual flow-sensitive points to sets.
     * isUnfilteredSubsetOf, isFilteredSubsetOf, isFlowSensSubsetOf relations record subset
     * relations between PointsToGraphNodes, in order to let us more efficiently
     * propagate changes to the graph.
     *
     * Moreover, since cycles may be collapsed, we use the map representative to
     * record the "representative" node for nodes that have been collapsed. Note
     * that we maintain the invariants:
     *   - if a \in domain(representative) then a \not\in domain(pointsToFI)
     *   - if a \in domain(representative) then a \not\in domain(pointsToFS)
     *   - if a \in domain(representative) then a \not\in domain(isUnfilteredSubsetOf)
     *   - if a \in domain(representative) then a \not\in domain(isFilteredSubsetOf)
     *   - if a \in domain(representative) then a \not\in domain(isFlowSensSubsetOf)
     *
     * Note also that it is possible that (a,b) \in representative and b \in domain(representative),
     * i.e., the representative of a collapsed node may itself get collapsed.
     *
     * Lastly, nullInstanceKey and nullInstaceKeyInt are representations of the null instance key, i.e. what a node points n to after statement n = null.
     */

    /**
     * Map from PointsToGraphNode to sets of InstanceKeys (where PointsToGraphNodes and InstanceKeys are represented by
     * ints). These are the flow-insensitive facts, i.e., they hold true at all program points.
     */
    private ConcurrentIntMap<MutableIntSet> pointsToFI = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * Map from PointsToGraphNode to InstanceKeys, including the program points (actually, the interprogrampoint
     * replicas) at which they are valid. These are the flow sensitive points to information. if (s,t,ps) \in deltaFS,
     * and p \in ps, then s points to t at program point p.
     */
    private final ConcurrentIntMap<ConcurrentIntMap<ProgramPointSetClosure>> pointsToFS = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * If "a isUnfilteredSubsetOf b" then the points to set of a is always a subset of the points to set of b.
     */
    private IntRelation isUnfilteredSubsetOf = new IntRelation();

    /**
     * If "a isFilteredSubsetOf b with filter" then the filter(pointsTo(a)) is a subset of pointsTo(b).
     */
    private SetAnnotatedIntRelation<TypeFilter> isFilteredSubsetOf = new SetAnnotatedIntRelation<>();

    /**
     * If "a isFlowSensSubsetOf b with (noFilterPPSet, filterPPSet)" then for all ippr \in noFilterPPSet, we have
     * pointsTo(a, ippr) is a subset of pointsTo(b, ippr). At least one of a and b is a flow-sensitive
     * PointsToGraphNode. That is, if ippr \in noFilterPPSet, then if a is flow sensitive, we have: pointsToFS(a, ippr)
     * \subseteq pointsToFI(b) and if b is flow sensitive we have pointsToFI(a) \subseteq pointsToFS(b, ippr).
     *
     * filterPPSet is specifically used for the preservation of FS object-field o.f across program points that allocate
     * o. More specifically, if filterPPSet is not empty, a is flowsensitive and b is not and they should represent the
     * same o.f. Also, pointsToFS(a, ippr) \subseteq pointsToFI(b) except that any InstanceKeyRecency ikr == o in
     * pointsToFS(a, ippr) will be replaced with the non-most recent version of o.
     */
    private final AnnotatedIntRelation<OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet>> isFlowSensSubsetOf = new AnnotatedIntRelation<OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet>>() {
        @Override
        protected OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet> createInitialAnnotation() {
            return new OrderedPair<>(new ExplicitProgramPointSet(), new ExplicitProgramPointSet());
        }

        @Override
        protected boolean merge(OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet> existing,
                                OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet> annotation) {
            boolean changed = false;

            ExplicitProgramPointSet f = annotation.fst();
            if (f != null) {
                changed |= (existing.fst().addAll(f));
            }

            ExplicitProgramPointSet s = annotation.snd();
            if (s != null) {
                changed |= (existing.snd().addAll(s));
            }

            return changed;
        }

    };

    /**
     * Map from PointsToGraphNodes to PointsToGraphNodes, indicating which nodes have been collapsed (due to being in
     * cycles) and which node now represents them.
     */
    private final ConcurrentIntMap<Integer> representative = PointsToAnalysisMultiThreaded.makeConcurrentIntMap();

    /**
     * InstanceKey pointed to at null allocation sites. Represented by one node in the points to graph.
     */

    private final InstanceKeyRecency nullInstanceKey = new InstanceKeyRecency(null, false, false);

    /**
     * Int representation of nullInstanceKey.
     */
    private final int nullInstanceKeyInt = lookupDictionary(nullInstanceKey);

    /* ***************************************************************************
     * Reachable contexts and entry points, and call graph representations.
     */

    /**
     * The contexts that a method may appear in.
     */
    private ConcurrentMap<IMethod, Set<Context>> reachableContexts = AnalysisUtil.createConcurrentHashMap();

    /**
     * The classes that will be loaded (i.e., we need to analyze their static initializers).
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
    private ConcurrentMap<OrderedPair<CallSiteProgramPoint, Context>, Set<OrderedPair<IMethod, Context>>> callGraphMap = AnalysisUtil.createConcurrentHashMap();

    /**
     * A thread-safe representation of the call graph that we populate during the analysis, and then convert it to a
     * HafCallGraph later.
     */
    private final ConcurrentMap<OrderedPair<IMethod, Context>, Set<OrderedPair<CallSiteProgramPoint, Context>>> callGraphReverseMap = AnalysisUtil.createConcurrentHashMap();

    /**
     * Call graph.
     */
    private HafCallGraph callGraph = null;

    /**
     * Heap abstraction factory.
     */
    private final RecencyHeapAbstractionFactory haf;

    /**
     * Dependency recorder.
     */
    private final DependencyRecorder depRecorder;

    /**
     * Is the graph still being constructed, or is it finished? Certain operations should be called only once the graph
     * has finished being constructed.
     */
    private boolean graphFinished = false;

    private int outputLevel = 0;

    public PointsToGraph(StatementRegistrar registrar, RecencyHeapAbstractionFactory haf,
                         DependencyRecorder depRecorder, PointsToAnalysisHandle analysisHandle) {
        assert analysisHandle != null;
        this.depRecorder = depRecorder;

        this.registrar = registrar;

        this.haf = haf;

        this.ppReach = ProgramPointReachability.getOrCreate(this, analysisHandle);
        this.populateInitialContexts(registrar.getInitialContextMethods());
    }

    /**
     * Populate the contexts map by adding the initial context for all the given methods
     *
     * @param haf abstraction factory defining the initial context
     * @param initialMethods methods to be paired with the initial context
     * @return mapping from each method in the given set to the singleton set containing the initial context
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
        int rep;
        Integer x = n;
        do {
            rep = x;
            x = this.representative.get(x);
        } while (x != null);
        return rep;
    }

    /**
     * Return the int for the InstanceKeyRecency for the base node of the PointsToGraphNode n, if n is an ObjectField.
     * Otherwise return -1.
     *
     * @param n
     * @return
     */
    protected/*InstanceKeyRecency*/int baseNodeForPointsToGraphNode(int/*PointsToGaphNode*/n) {
        assert n >= 0;
        PointsToGraphNode fromGraphNode = this.graphNodeDictionary.get(n);
        if (fromGraphNode instanceof ObjectField) {
            ObjectField of = (ObjectField) fromGraphNode;
            return this.reverseInstanceKeyDictionary.get(of.receiver());
        }
        return -1;
    }

    protected/*InstanceKeyRecency*/int baseNodeForAnyPointsToGraphNode(int/*PointsToGaphNode*/n) {
        assert n >= 0;
        PointsToGraphNode fromGraphNode = this.graphNodeDictionary.get(n);
        if (fromGraphNode instanceof ObjectField) {
            ObjectField of = (ObjectField) fromGraphNode;
            return this.reverseInstanceKeyDictionary.get(of.receiver());
        }
        return -1;
    }

    /**
     * Add an edge from node to heapContext in the graph.
     *
     * @param node
     * @param heapContext
     * @return
     */
    public GraphDelta addEdge(PointsToGraphNode node, InstanceKeyRecency heapContext, InterProgramPointReplica ippr) {

        // System.err.println("ADDING EDGE:(" + node + "," + heapContext + "," + ippr + ")");

        assert node != null && heapContext != null;
        assert !this.graphFinished;

        assert !node.isFlowSensitive() || isNullInstanceKey(heapContext) : "Base nodes (i.e., nodes that point directly to heap contexts) should be flow-insensitive"
                + "or heap context is null.";

        int n = lookupDictionary(node);

        n = this.getRepresentative(n);

        int h = this.lookupDictionary(heapContext);

        GraphDelta delta = new GraphDelta(this);
        if (!node.isFlowSensitive() && !this.pointsToSetFI(n).contains(h)) {
            IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
            addToSetAndSupersets(delta,
                                 n,
                                 false,
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
        else if (node.isFlowSensitive() && !this.pointsTo(n, h, ippr, null)) {
            // in this case, heapContext should be nullInstanceKey
            assert h == this.nullInstanceKeyInt;
            IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();
            addToSetAndSupersets(delta,
                                 n,
                                 true,
                                 ExplicitProgramPointSet.singleton(ippr),
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

    @SuppressWarnings("unused")
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
                //XXXdelta.collapseNodes(n, rep);
            }
        }
    }

    /*
     * Lookup functions that queries the dictionaries.
     */

    protected InstanceKeyRecency lookupInstanceKeyDictionary(int key) {
        return this.instanceKeyDictionary.get(key);

    }

    protected int lookupDictionary(InstanceKeyRecency key) {
        Integer n = this.reverseInstanceKeyDictionary.get(key);
        if (n == null) {
            // not in the dictionary yet
            n = instanceKeyCounter.getAndIncrement();

            // Put the mapping into instanceKeyDictionary and concreteTypeDictionary
            // Note that it is important to do this before putting it into reverseInstanceKeyDictionary
            // to avoid a race (i.e., someone looking up heapContext in reverseInstanceKeyDictionary, getting
            // int h, yet getting null when trying instanceKeyDictionary.get(h).)
            // try a put if absent
            // Note that we can do a put instead of a putIfAbsent, since h is guaranteed unique.
            if (concreteTypeDictionary != null && key != nullInstanceKey) {
                this.concreteTypeDictionary.put(n, key.getConcreteType());
            }
            this.instanceKeyDictionary.put(n, key);
            Integer existing = this.reverseInstanceKeyDictionary.putIfAbsent(key, n);
            if (existing != null) {
                // someone beat us. h will never be used.
                this.instanceKeyDictionary.remove(n);
                if (concreteTypeDictionary != null && key != nullInstanceKey) {
                    this.concreteTypeDictionary.remove(n);
                }
                n = existing;
            }
        }
        return n;
    }

    protected PointsToGraphNode lookupPointsToGraphNodeDictionary(int node) {
        return this.graphNodeDictionary.get(node);
    }

    protected int lookupDictionary(PointsToGraphNode node) {
        Integer n = this.reverseGraphNodeDictionary.get(node);
        if (n == null) {
            // not in the dictionary yet
            if (this.graphFinished) {
                return -1;
            }
            n = graphNodeCounter.getAndIncrement();

            // Put the mapping into graphNodeDictionary
            // Note that it is important to do this before putting it into reverseGraphNodeDictionary
            // to avoid a race (i.e., someone looking up node in reverseGraphNodeDictionary, getting
            // int n, yet getting null when trying graphNodeDictionary.get(n).)
            // Note that we can do a put instead of a putIfAbsent, since n is guaranteed unique.
            this.graphNodeDictionary.put(n, node);
            Integer existing = this.reverseGraphNodeDictionary.putIfAbsent(node, n);
            if (existing != null) {
                // Someone beat us to it. Clean up graphNodeDictionary
                this.graphNodeDictionary.remove(n);
                return existing;
            }
        }
        return n;
    }

    /**
     * Copy the pointsto set of the source to the pointsto set of the target. This should be used when the pointsto set
     * of the target is a supserset of the pointsto set of the source.
     *
     * @param source
     * @param target
     * @return
     */
    public GraphDelta copyEdges(PointsToGraphNode source, InterProgramPointReplica sourceIppr,
                                PointsToGraphNode target, InterProgramPointReplica targetIppr) {

        assert !(source.isFlowSensitive() && target.isFlowSensitive()) : "At most one of the source and target should be flow sensitive";
        assert !this.graphFinished;
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
                computeDeltaForAddedSubsetRelation(changed, s, false, null, null, t, false, null);
            }
        }
        else {
            // exactly one of source and target is flow sensitive.
            assert !(source.isFlowSensitive() && target.isFlowSensitive());

            // Choose the ippr to be the source or the target ippr, based on which is flow sensitive.
            InterProgramPointReplica ippr = source.isFlowSensitive() ? sourceIppr : targetIppr;

            if (isFlowSensSubsetOf.addAnnotation(s,
                                                 t,
                                                 new OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet>(ExplicitProgramPointSet.singleton(ippr),
                                                                                                                   null))) {
                // this is a new subset relation!
                computeDeltaForAddedSubsetRelation(changed,
                                                   s,
                                                   source.isFlowSensitive(),
                                                   null,
                                                   null,
                                                   t,
                                                   target.isFlowSensitive(),
                                                   ippr);
            }
        }
        return changed;
    }

    /**
     * This function is only used when processing new allocation sites. It ensures that the PointsToFS of o.f before the
     * program point ppr will be copied to PointsToFI of o.f if ppr is an allocation site of o.
     */
    public GraphDelta copyEdgesForAllFields(InstanceKeyRecency newlyAllocated, ProgramPointReplica ppr) {
        assert !this.graphFinished;
        GraphDelta changed = new GraphDelta(this);

        int ikrecent = lookupDictionary(newlyAllocated);
        if (!isMostRecentObject(ikrecent)) {
            // it's not the most recent object. Nothing to do.
            return changed;
        }

        int iknotrecent = this.nonMostRecentVersion(ikrecent);

        // for all fields fld in the concrete type of ik, we want to make sure that
        // pointsToFS(ikrecent.fld, ppr_pre) \subseteq pointstoFI(iknotrecent.fle, ppr_post)

        IClass concreteType = newlyAllocated.getConcreteType();
        assert !concreteType.isArrayClass() : "We don't currently track the most recent version of arrays.";

        for (IField fld : concreteType.getAllInstanceFields()) {
            int recFld = lookupDictionary(new ObjectField(newlyAllocated, fld.getReference()));
            recFld = getRepresentative(recFld);

            int notrecFld = lookupDictionary(new ObjectField(lookupInstanceKeyDictionary(iknotrecent),
                                                             fld.getReference()));
            notrecFld = getRepresentative(notrecFld);

            assert isFlowSensitivePointsToGraphNode(recFld);
            assert !isFlowSensitivePointsToGraphNode(notrecFld);
            if (isFlowSensSubsetOf.addAnnotation(recFld,
                                                 notrecFld,
                                                 new OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet>(null,
                                                                                                                   ExplicitProgramPointSet.singleton(ppr.pre())))) {
                // this is a new subset relation!
                computeDeltaForAddedSubsetRelation(changed, recFld, true, null, ikrecent, notrecFld, false, ppr.pre());
            }
        }

        return changed;
    }

    public GraphDelta copyFilteredEdges(PointsToGraphNode source, TypeFilter filter, PointsToGraphNode target) {
        assert !source.isFlowSensitive() && !target.isFlowSensitive() : "Filtered subset relations can only be on flow-insensitive nodes";
        assert !this.graphFinished;
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
            computeDeltaForAddedSubsetRelation(changed, s, false, filter, null, t, false, null);
        }
        return changed;
    }

    /**
     * This method adds everything in s that satisfies filter to t, in both the cache and the GraphDelta, and recurses.
     *
     * If ippr is not null it means either: pointsToFS(source, ippr) \subseteq pointsToFI(target) (if source is flow
     * sensitive) or pointsToFI(source) \subseteq pointsToFS(target, ippr) (if target is flow sensitive)
     *
     * @param changed
     * @param source
     * @param sourceIsFlowSensitive
     * @param filter
     * @param filterInstanceKey
     * @param target
     * @param targetIsFlowSensitive
     * @param ippr
     */
    private void computeDeltaForAddedSubsetRelation(GraphDelta changed, /*PointsToGraphNode*/int source,
                                                    boolean sourceIsFlowSensitive, TypeFilter filter,
                                                    /*InstanceKey*/Integer filterInstanceKey,
                                                    /*PointsToGraphNode*/
                                                    int target, boolean targetIsFlowSensitive,
                                                    InterProgramPointReplica ippr) {
        assert !(sourceIsFlowSensitive && targetIsFlowSensitive) : "At most one can be flow sensitive";
        assert !(sourceIsFlowSensitive || targetIsFlowSensitive) || filter == null : "If either is flow sensitive then filter must be null";
        assert !(sourceIsFlowSensitive || targetIsFlowSensitive) || ippr != null : "If either is flow sensitive then ippr must be non null";

        assert filterInstanceKey != null ? (filterInstanceKey >= 0 && isMostRecentObject(filterInstanceKey)) : true;
        assert filterInstanceKey != null
                ? (filterInstanceKey == baseNodeForPointsToGraphNode(source) && sourceIsFlowSensitive) : true;

        assert source >= 0 && target >= 0;

        //XXX make an addtoset for target.
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
     * ippr \in targetPoints. We then check all the subset relations for which target is a subset, and continue to
     * propagate according to the subset relations.
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
                                      /*Set<InstanceKeyRecency>*/IntSet setToAdd, MutableIntSet currentlyAdding,
                                      IntStack currentlyAddingStack, Stack<Set<TypeFilter>> filterStack,
                                      Stack<ExplicitProgramPointSet> programPointStack, IntMap<MutableIntSet> toCollapse) {

        assert !targetIsFlowSensitive ? (targetPoints == null || targetPoints.isEmpty()) : true : "If target is not flow sensitive then targetPoints must be null";

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
                //isFlowSensitive |= programPointStack.get(i) != null; // for the moment, we won't try to do anything smart with flow-sensitive cycles. Could do a lot better...
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

        // propagate the set setToAdd to the immediate supersets
        // The target points are no longer important.

        // First, do the unfiltered flow-insensitive subset relations
        IntSet unfilteredSupersets = this.isUnfilteredSubsetOf.forward(target);
        IntIterator iter = unfilteredSupersets == null ? EmptyIntIterator.instance()
                : unfilteredSupersets.intIterator();
        while (iter.hasNext()) {
            int m = iter.next();
            assert !isFlowSensitivePointsToGraphNode(m);

            filterStack.push(null);
            propagateDifferenceToFlowInsensitive(changed,
                                                 m,
                                                 setToAdd.intIterator(),
                                                 currentlyAdding,
                                                 currentlyAddingStack,
                                                 filterStack,
                                                 programPointStack,
                                                 toCollapse);
            filterStack.pop();
        }

        // Second, do the filtered flow-insensitive subset relations
        IntMap<Set<TypeFilter>> filteredSupersets = this.isFilteredSubsetOf.forward(target);
        iter = filteredSupersets == null ? EmptyIntIterator.instance() : filteredSupersets.keyIterator();
        while (iter.hasNext()) {
            int m = iter.next();
            assert !isFlowSensitivePointsToGraphNode(m);

            @SuppressWarnings("null")
            Set<TypeFilter> filterSet = filteredSupersets.get(m);
            // it is possible that the filter set is empty, due to race conditions.
            // No trouble, we will just ignore it, and pretend we got in there before
            // the relation between target and m was created.
            if (!filterSet.isEmpty()) {
                filterStack.push(filterSet);
                propagateDifferenceToFlowInsensitive(changed,
                                                     m,
                                                     setToAdd.intIterator(),
                                                     currentlyAdding,
                                                     currentlyAddingStack,
                                                     filterStack,
                                                     programPointStack,
                                                     toCollapse);
                filterStack.pop();

            }
        }

        // Third, do the unfiltered flow-sensitive subset relations
        ConcurrentIntMap<OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet>> flowSensSupersetsMap = this.isFlowSensSubsetOf.forward(target);

        iter = flowSensSupersetsMap == null ? EmptyIntIterator.instance() : flowSensSupersetsMap.keyIterator();

        while (iter.hasNext()) {
            int m = iter.next();
            assert flowSensSupersetsMap != null;
            OrderedPair<ExplicitProgramPointSet, ExplicitProgramPointSet> annotation = flowSensSupersetsMap.get(m);
            ExplicitProgramPointSet noFilterPPSet = annotation.fst();
            ExplicitProgramPointSet filterPPSet = annotation.snd();

            boolean mIsFlowSensitive = isFlowSensitivePointsToGraphNode(m);
            assert !(targetIsFlowSensitive && mIsFlowSensitive);

            assert filterPPSet != null && !filterPPSet.isEmpty() ? targetIsFlowSensitive && !mIsFlowSensitive : true;
            assert filterPPSet != null && !filterPPSet.isEmpty()
                    ? (nonMostRecentVersion(baseNodeForPointsToGraphNode(target)) == baseNodeForPointsToGraphNode(m))
                    : true : "target is " + target + " = " + lookupPointsToGraphNodeDictionary(target) + " and m is "
                    + m + " = " + lookupPointsToGraphNodeDictionary(m) + ", base node for m is"
                    + baseNodeForPointsToGraphNode(m);
            // Let's do the nofilterpp set first.
            // "target isFlowSensSubsetOf m with ppSet"
            if (targetIsFlowSensitive) {
                assert !mIsFlowSensitive;
                // For all p \in ppSet, we want pointsToFS(target, p) \subset pointsToFI(m).
                // So we will add the intersection of setToAdd and \bigcup { pointsToFS(target, p) | p \in ppSet}
                filterStack.push(null);

                ReachabilityQueryOriginMaker originMaker = new AddToSetOriginMaker(m, this);//if i now gets added, then we need to add it to m.

                propagateDifferenceToFlowInsensitive(changed,
                                                     m,
                                                     new PointsToIntersectIntIterator(setToAdd.intIterator(),
                                                                                      target,
                                                                                      noFilterPPSet,
                                                                                      originMaker),
                                                     currentlyAdding,
                                                     currentlyAddingStack,
                                                     filterStack,
                                                     programPointStack,
                                                     toCollapse);
                filterStack.pop();

            }
            else {
                // target is not flow sensitive, and so
                // for all p \in ppSet, we want pointsToFI(target) \subset pointsToFS(m, p)
                // so we will add setToAdd to each of  pointsToFS(m, p).
                assert filterPPSet == null || filterPPSet.isEmpty();
                assert mIsFlowSensitive;
                filterStack.push(null);
                propagateDifferenceToFlowSensitive(changed,
                                                   m,
                                                   noFilterPPSet,
                                                   setToAdd,
                                                   currentlyAdding,
                                                   currentlyAddingStack,
                                                   filterStack,
                                                   programPointStack,
                                                   toCollapse);
                filterStack.pop();
            }
            if (filterPPSet != null && !filterPPSet.isEmpty()) {
                assert !mIsFlowSensitive;
                // we have one of these special constraints, that
                // for all ippr in filterPPSet, we have
                // { f(n) | n \in PointsToFS(target, ippr) } \subseteq PointsToFI(m)
                // where f(n) = o_{notmostrecent} if n==o_{mostrecent} where target = o_{mostrecent}.fld and m = o_{notmostrecent}.fld
                assert nonMostRecentVersion(baseNodeForPointsToGraphNode(target)) == baseNodeForAnyPointsToGraphNode(m);
                assert baseNodeForPointsToGraphNode(target) >= 0;
                assert isFlowSensitivePointsToGraphNode(target) : "base node for target is not flow sensitive:"
                        + lookupPointsToGraphNodeDictionary(baseNodeForPointsToGraphNode(target));

                ReachabilityQueryOriginMaker originMaker = new AddToSetOriginMaker(m, this);//if i now gets added, then we need to add it to m.

                IntIterator filteredIntIterator = new ChangeRecentInstanceKeyIterator(new PointsToIntersectIntIterator(setToAdd.intIterator(),
                                                                                                                       target,
                                                                                                                       filterPPSet,
                                                                                                                       originMaker),
                                                                                      baseNodeForPointsToGraphNode(target),
                                                                                      this);
                filterStack.push(null);
                propagateDifferenceToFlowInsensitive(changed,
                                                     m,
                                                     filteredIntIterator,
                                                     currentlyAdding,
                                                     currentlyAddingStack,
                                                     filterStack,
                                                     programPointStack,
                                                     toCollapse);
                filterStack.pop();
            }
        }
        currentlyAdding.remove(target);
        currentlyAddingStack.pop();
    }

    /**
     * Add the setToAdd to the points to set of target. If target is not flow sensitive, then setToAdd is added to
     * PointsToFI(target). Otherwise, if target is flow sensitive, then targetPointsToAdd should be non-null, and
     * setToAdd will be added to PointsToFS(target, ippr) for each ippr in targetPointsToAdd.
     *
     * This method will then propagate the set using the supersets relation.
     *
     * @param changed
     * @param target
     * @param targetIsFlowSensitive
     * @param targetPointsToAdd
     * @param setToAdd
     * @param currentlyAdding
     * @param currentlyAddingStack
     * @param filterStack
     * @param programPointStack
     * @param toCollapse
     * @param originator
     */
    private void propagateDifferenceToFlowInsensitive(GraphDelta changed, /*PointsToGraphNode*/int target,
    /*Iterator<InstanceKeyRecency>*/IntIterator setToAdd, MutableIntSet currentlyAdding,
                                                      IntStack currentlyAddingStack,
                                                      Stack<Set<TypeFilter>> filterStack,
                                                      Stack<ExplicitProgramPointSet> programPointStack,
                                                      IntMap<MutableIntSet> toCollapse) {

        // First, let's handle the noFilterTargetPoints.
        //     we want setToAdd is added to PointsToFI(target)
        IntSet diff = this.getDifferenceFlowInsensitive(setToAdd, target);
        addToSetAndSupersets(changed,
                             target,
                             false,
                             null,
                             diff,
                             currentlyAdding,
                             currentlyAddingStack,
                             filterStack,
                             programPointStack,
                             toCollapse);
    }

    /**
     * Add the setToAdd to the points to set of target. If target is not flow sensitive, then setToAdd is added to
     * PointsToFI(target). Otherwise, if target is flow sensitive, then targetPointsToAdd should be non-null, and
     * setToAdd will be added to PointsToFS(target, ippr) for each ippr in targetPointsToAdd.
     *
     * This method will then propagate the set using the supersets relation.
     *
     * @param changed
     * @param target
     * @param targetIsFlowSensitive
     * @param targetPointsToAdd
     * @param setToAdd
     * @param currentlyAdding
     * @param currentlyAddingStack
     * @param filterStack
     * @param programPointStack
     * @param toCollapse
     * @param originator
     */
    private void propagateDifferenceToFlowSensitive(GraphDelta changed, /*PointsToGraphNode*/int target,
                                                    ExplicitProgramPointSet targetPointsToAdd,
                                                    /*Set<InstanceKeyRecency>*/IntSet setToAdd,
                                                    MutableIntSet currentlyAdding, IntStack currentlyAddingStack,
                                                    Stack<Set<TypeFilter>> filterStack,
                                                    Stack<ExplicitProgramPointSet> programPointStack,
                                                    IntMap<MutableIntSet> toCollapse) {

        assert isFlowSensitivePointsToGraphNode(target);
        assert targetPointsToAdd != null;
        // First, let's handle the noFilterTargetPoints.
        // If targetIsFlowSensitive then
        //     for each ippr \in noFilterTargetPoints, we want setToAdd is added to PointsToFS(target, ippr)
        IntSet diff = this.getDifferenceFlowSensitive(setToAdd.intIterator(), target, targetPointsToAdd);
        addToSetAndSupersets(changed,
                             target,
                             true,
                             targetPointsToAdd,
                             diff,
                             currentlyAdding,
                             currentlyAddingStack,
                             filterStack,
                             programPointStack,
                             toCollapse);
    }

    /**
     * Provide an iterator for the things that n points to. Note that we may not return a set, i.e., some InstanceKeys
     * may be returned multiple times. XXX we may change this in the future...
     *
     * @param n
     * @return
     */
    public Iterator<? extends InstanceKey> pointsToIterator(PointsToGraphNode n) {
        assert this.graphFinished : "Can only get a points to set without an originator if the graph is finished";
        return pointsToIterator(n, null, null);
    }

    public Iterator<? extends InstanceKey> pointsToIterator(PointsToGraphNode n, InterProgramPointReplica ippr) {
        assert this.graphFinished : "Can only get a points to set without an originator if the graph is finished";
        return pointsToIterator(n, ippr, null);
    }

    //    public boolean pointsTo(PointsToGraphNode from, InstanceKeyRecency to, InterProgramPointReplica ippr,
    //                            StmtAndContext originator) {
    //        int f = this.lookupDictionary(from);
    //        int t = this.lookupDictionary(to);
    //        return pointsTo(f, t, ippr, originator);
    //    }

    /**
     * Returns true if the node from points to the instance key to at inter program point ippr
     *
     * @param from
     * @param to
     * @param ippr
     * @return
     */

    public boolean pointsTo(/*PointsToGraphNode*/int from, /*InstanceKeyRecency*/int to,
                            InterProgramPointReplica ippr, ReachabilityQueryOrigin originator) {
        if (this.isFlowSensitivePointsToGraphNode(from)) {
            // from is flow sensitive
            ConcurrentIntMap<ProgramPointSetClosure> s = this.pointsToFS.get(from);
            if (s == null) {
                return false;
            }
            ProgramPointSetClosure ppsc = s.get(to);
            if (ppsc == null) {
                return false;
            }
            return ppsc.contains(ippr, this, originator);
        }
        // from is not flow sensitive
        MutableIntSet s = this.pointsToFI.get(from);
        if (s == null) {
            return false;
        }
        return s.contains(to);
    }

    public Iterator<InstanceKeyRecency> pointsToIterator(PointsToGraphNode node, InterProgramPointReplica ippr,
                                                         StmtAndContext originator) {
        assert this.graphFinished || originator != null;
        int n = lookupDictionary(node);
        if (this.graphFinished && n < 0) {
            return Collections.emptyIterator();
        }
        return new IntToInstanceKeyIterator(this.pointsToIntIterator(n, ippr, originator));
    }

    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n, InterProgramPointReplica ippr,
                                           StmtAndContext originator) {
        n = this.getRepresentative(n);
        if (originator != null) {
            // If the originating statement is null then the graph is finished and there is no need to record this read
            this.recordRead(n, originator);
        }
        if (isFlowSensitivePointsToGraphNode(n)) {
            return new ProgramPointIntIterator(pointsToFS.get(n),
                                               ippr,
                                               this,
                                               new StmtAndContextReachabilityOriginator(originator));
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
     * Add a call at a particular call site from a caller (in a context) to a callee (in a context)
     *
     * @param callSite call site the new call is being added for
     * @param caller method invocation occurs in
     * @param callerContext analysis context for the caller
     * @param callee method being called
     * @param calleeContext analyis context for the callee
     */
    public boolean addCall(CallSiteProgramPoint callSite, Context callerContext, IMethod callee, Context calleeContext) {
        OrderedPair<CallSiteProgramPoint, Context> callerPair = new OrderedPair<>(callSite, callerContext);
        OrderedPair<IMethod, Context> calleePair = new OrderedPair<>(callee, calleeContext);

        Set<OrderedPair<IMethod, Context>> s = this.callGraphMap.get(callerPair);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<IMethod, Context>> existing = this.callGraphMap.putIfAbsent(callerPair, s);

            if (existing != null) {
                s = existing;
            }
        }
        boolean changed = s.add(calleePair);

        this.recordReachableContext(callee, calleeContext);

        // set up reverse call graph map.
        Set<OrderedPair<CallSiteProgramPoint, Context>> t = this.callGraphReverseMap.get(calleePair);
        if (t == null) {
            t = AnalysisUtil.createConcurrentSet();
            Set<OrderedPair<CallSiteProgramPoint, Context>> existing = this.callGraphReverseMap.putIfAbsent(calleePair,
                                                                                                            t);
            if (existing != null) {
                t = existing;
            }
        }
        t.add(callerPair);

        if (changed) {
            // we added a new edge in the call graph.
            // Let the pp reach know.
            this.ppReach.addCallGraphEdge(callSite, callerContext, callee, calleeContext);
        }
        return changed;
    }

    /**
     * Record a callee context for the given method
     *
     * @param callee method
     * @param calleeContext context
     */
    private void recordReachableContext(IMethod callee, Context calleeContext) {
        if (this.outputLevel >= 1) {
            System.err.println("RECORDING: " + callee + " in " + calleeContext + " hc " + calleeContext);
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
    public RecencyHeapAbstractionFactory getHaf() {
        return haf;
    }

    /**
     * Get all the class initializer that have been registered
     *
     * @return set of registered class initializers
     */
    public Set<IMethod> getClassInitializers() {
        return this.classInitializers;
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
            for (OrderedPair<CallSiteProgramPoint, Context> callerPair : this.callGraphMap.keySet()) {
                CallSiteProgramPoint caller = callerPair.fst();
                Context callerContext = callerPair.snd();
                CGNode src = callGraph.findOrCreateNode(caller.containingProcedure(), callerContext);
                Set<OrderedPair<IMethod, Context>> s = this.callGraphMap.get(callerPair);
                for (OrderedPair<IMethod, Context> calleePair : s) {
                    IMethod callee = calleePair.fst();
                    Context calleeContext = calleePair.snd();

                    CGNode dst = callGraph.findOrCreateNode(callee, calleeContext);
                    if (!caller.isClinit() /* Class inits are handled as entry points below */) {
                        // We are building a call graph so it is safe to call this "deprecated" method
                        src.addTarget(caller.getReference(), dst);
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

    /**
     * Get the statement registrar.
     */
    public StatementRegistrar getRegistrar() {
        return registrar;
    }

    private void recordRead(/*PointsToGraphNode*/int node, StmtAndContext sac) {
        this.depRecorder.recordRead(node, sac);
    }

    public void recordAllocationDependency(/*InstanceKeyDependency*/int ikr, AllocationDepender sac) {
        this.depRecorder.recordAllocationDependency(ikr, sac);
    }

    public void setOutputLevel(int outputLevel) {
        this.outputLevel = outputLevel;
    }

    public int clinitCount = 0;

    /**
     * Add class initialization methods
     *
     * @param classInits list of class initializer is initialization order (i.e. element j is a super class of element
     *            j+1)
     * @return true if the call graph changed as a result of this call, false otherwise
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

                CallSiteProgramPoint cspp = this.registrar.getClassInitPP(clinit);
                addCall(cspp, haf.initialContext(), clinit, haf.initialContext());

                this.clinitCount++;
            }
            else {
                // Already added an initializer and thus must have added initializers for super classes. These are all
                // that are left to process since we are adding from sub class to super class order
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
     * @return true if the call graph changed as a result of this call, false otherwise
     */
    public boolean addEntryPoint(IMethod newEntryPoint) {
        boolean changed = this.entryPoints.add(newEntryPoint);
        if (changed) {
            this.recordReachableContext(newEntryPoint, this.haf.initialContext());
            this.clinitCount++;
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
                assert type != null || isNullInstanceKey(i) : "Null type in instance key that is not the null instance key: "
                        + lookupInstanceKeyDictionary(i);
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

    /**
     * Very similar to an IntIterator, other than replacing recentInstanceKey with the non-most-recent version.
     */
    class ChangeRecentInstanceKeyIterator implements IntIterator {
        private final IntIterator iter;
        private final int recentInstanceKey;
        private final PointsToGraph g;
        private int next = -1;

        ChangeRecentInstanceKeyIterator(IntIterator iter, int recentInstanceKey, PointsToGraph g) {
            this.iter = iter;
            this.recentInstanceKey = recentInstanceKey;
            this.g = g;
            assert recentInstanceKey >= 0;
            assert g.isMostRecentObject(recentInstanceKey);
        }

        @Override
        public boolean hasNext() {
            while (this.next < 0 && this.iter.hasNext()) {
                int i = this.iter.next();
                if (i == this.recentInstanceKey) {
                    this.next = this.g.nonMostRecentVersion(i);
                }
                else {
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

    /**
     * A ProgramPointIntIterator filters an underlying intinterator (iter) so that it returns only those
     * InstanceKeyRecencys such that ppmap points to it at program point replica ippr.
     */
    static class ProgramPointIntIterator implements IntIterator {
        private final IntIterator iter;
        private final IntMap<ProgramPointSetClosure> ppmap;
        private final InterProgramPointReplica ippr;
        private final PointsToGraph g;
        private final ReachabilityQueryOriginMaker originMaker;
        private final IntMap<Set<ProgramPointReplica>> newAllocationSites;
        private int next = -1;

        ProgramPointIntIterator(IntMap<ProgramPointSetClosure> ppmap, InterProgramPointReplica ippr, PointsToGraph g,
                                ReachabilityQueryOriginMaker originMaker) {
            this.iter = ppmap == null ? EmptyIntIterator.instance() : ppmap.keyIterator();
            this.ppmap = ppmap;
            this.ippr = ippr;
            this.g = g;
            this.originMaker = originMaker;
            this.newAllocationSites = null;
        }

        ProgramPointIntIterator(IntMap<ProgramPointSetClosure> ppmap, InterProgramPointReplica ippr, PointsToGraph g,
                                ReachabilityQueryOriginMaker originMaker,
                                IntMap<Set<ProgramPointReplica>> newAllocationSites) {
            this.iter = ppmap == null ? EmptyIntIterator.instance() : ppmap.keyIterator();
            this.ppmap = ppmap;
            this.ippr = ippr;
            this.g = g;
            this.originMaker = originMaker;
            this.newAllocationSites = newAllocationSites.isEmpty() ? null : newAllocationSites;
        }

        @Override
        public boolean hasNext() {
            assert newAllocationSites == null || !newAllocationSites.isEmpty();
            while (this.next < 0 && this.iter.hasNext()) {
                int i = this.iter.next();
                ProgramPointSetClosure pps;
                if (ippr == null
                        || ((pps = ppmap.get(i)) != null && pps.contains(ippr,
                                                                         g,
                                                                         originMaker.makeOrigin(-1, i, ippr),
                                                                         newAllocationSites))) {
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

    class SortedIntSetUnion extends AbstractIntSet {
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
            return new SortedIntSetUnionIterator(this.a.intIterator(), this.b.intIterator());
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
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> unfilteredIterator = unfilteredSet == null ? null
                : new LiftUnfilteredIterator(unfilteredSet.iterator());
        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> filteredIterator = filteredSet == null ? null
                : filteredSet.iterator();

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
                iter = new ComposedIterators<>(unfilteredIterator, filteredIterator);
            }
        }
        return iter;
    }

    private IClass concreteType(/*InstanceKey*/int i) {
        if (this.concreteTypeDictionary != null) {
            return this.concreteTypeDictionary.get(i);
        }
        return this.instanceKeyDictionary.get(i).getConcreteType();
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
            return this.iter1 != null && this.iter1.hasNext() || this.iter2.hasNext();
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

    public static class LiftUnfilteredIterator implements Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> {
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
            @SuppressWarnings("synthetic-access")
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
     * Return the set of InstanceKeys that are pointed to by source (and satisfy filter) but are not pointed to by
     * target.
     *
     * If ippr is not null it means we are interested either in:
     *
     * - the difference between pointsToFS(source, ippr) and pointsToFI(target) (if source is flow sensitive)
     *
     * or
     *
     * - the difference between pointsToFI(source) and pointsToFS(target, ippr) (if target is flow sensitive)
     *
     *
     *
     * @param source the source PointsToGraphNode
     * @param sourceIsFlowSensitive is the source PointsToGraphNode flow sensitive? Should be equal to
     *            this.isFlowSensitivePointsToGraphNode(source)
     * @param filter TypeFilter to use to filter the contents of the points to set of source.
     * @param target The target node.
     * @param targetIsFlowSensitive is the target PointsToGraphNode flow sensitive? Should be equal to
     *            this.isFlowSensitivePointsToGraphNode(target)
     * @param ippr the InterProgramPointReplica for the flow sensitive points to set.
     * @param originator The StmtAndContext that is responsible for causing this to run.
     * @return
     */
    private IntSet getDifference(/*PointsToGraphNode*/int source, boolean sourceIsFlowSensitive, TypeFilter filter,
    /*PointsToGraphNode*/int target, boolean targetIsFlowSensitive, InterProgramPointReplica ippr) {

        source = this.getRepresentative(source);

        IntIterator srcIter;
        if (ippr == null || !sourceIsFlowSensitive) {
            assert !sourceIsFlowSensitive : "Source should be flow insensitive!";
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
            srcIter = new ProgramPointIntIterator(pointsToFS.get(source), ippr, this, new AddToSetOriginMaker(target,
                                                                                                              this,
                                                                                                              source));
        }
        if (targetIsFlowSensitive) {
            return getDifferenceFlowSensitive(srcIter, target, ExplicitProgramPointSet.singleton(ippr));
        }
        return getDifferenceFlowInsensitive(srcIter, target);
    }

    /**
     * Compute the difference between the set implied by srcIter and the set pointed to by target.
     *
     * @param srcIter Iterator over the InstanceKeyRecencys that we want to check whether they are in the points to set
     *            of target.
     * @param target The PointsToGraphNode for the set
     * @param targetIsFlowSensitive Is target flow sensitive?
     * @param addAtPoints If the target is flow sensitive, then we are interested in adding, for each ippr \in
     *            addAtPoints, the set srcIter to PointsToFS(target, ippr). If target is not flow sensitive then this
     *            argument is ignored.
     * @param filterBaseNodeOfField
     * @param originator
     * @return
     */
    private IntSet getDifferenceFlowInsensitive(/*Iterator<InstanceKeyRecency>*/IntIterator srcIter,
                                                /*PointsToGraphNode*/int target) {
        assert !isFlowSensitivePointsToGraphNode(target);
        target = this.getRepresentative(target);

        if (!srcIter.hasNext()) {
            // nothing in there, return an empty set.
            return EmptyIntSet.INSTANCE;
        }

        MutableIntSet s = MutableSparseIntSet.makeEmpty();

        IntSet targetSet = this.pointsToSetFI(target);

        while (srcIter.hasNext()) {
            int i = srcIter.next();
            if (!targetSet.contains(i)) {
                s.add(i);
            }
            if (isMostRecentObject(i)) {
                if (!targetSet.contains(nonMostRecentVersion(i))) {
                    // target is a flow-insensitive pointstographnode, so if it
                    // points to the most recent version, it may also need to point to
                    // the non-most recent version.
                    boolean needsNonMostRecent = true;
                    PointsToGraphNode tn = lookupPointsToGraphNodeDictionary(target);
                    if (tn instanceof ReferenceVariableReplica) {
                        ReferenceVariableReplica rvr = (ReferenceVariableReplica) tn;
                        if (rvr.hasInstantaneousScope()) {
                            needsNonMostRecent = false;
                        }
                        else if (rvr.hasLocalScope()) {
                            // rvr has a local scope, and we can possible be more precise.
                            needsNonMostRecent = isAllocInScope(rvr, i, new AddNonMostRecentOrigin(target, rvr, i));
                        }
                    }
                    if (needsNonMostRecent) {
                        s.add(nonMostRecentVersion(i));
                    }
                }
            }
        }
        return s;

    }

    /**
     * For a ReferenceVariableReplica rvr with local scope (i.e., it represents a local variable) is there an allocation
     * site of i in the local scope?
     *
     * @param rvr
     * @param i
     * @return
     */
    boolean isAllocInScope(ReferenceVariableReplica rvr, /*InstanceKeyRecency*/int i, ReachabilityQueryOrigin origin) {
        assert !rvr.isFlowSensitive() && !rvr.hasInstantaneousScope() && rvr.hasLocalScope();
        assert isMostRecentObject(i);

        // Specifically:
        //      If
        //            rvr points to  the most recent version of InstanceKey ik AND
        //            there is an allocation site allocSite of ik such that
        //                         there exists a ippr_use in rvr.getLocalUses() such that
        //                               rvr.getLocalDef() can reach allocSite without going through ippr_use or rvr's method exit nodes AND
        //                               allocSite can reach ippr_use without going through rvr.getLocalDef() or rvr's method exit nodes
        //     Then
        //          rvr must also point to the non-most-recent version of ik.
        InterProgramPointReplica localDef = rvr.localDef();
        Set<ProgramPointReplica> allocSites = getAllocationSitesOf(i);
        for (ProgramPointReplica allocSite : allocSites) {
            for (InterProgramPointReplica use : rvr.localUses()) {
                Set<InterProgramPointReplica> forbidden = new LinkedHashSet<>();
                forbidden.add(use);
                MethodSummaryNodes ms = this.registrar.getMethodSummary(use.getContainingProcedure());
                forbidden.add(ms.getNormalExitPP().pre().getReplica(use.getContext()));
                forbidden.add(ms.getExceptionExitPP().pre().getReplica(use.getContext()));
                if (this.ppReach.reachable(localDef, allocSite.pre(), forbidden, origin)) {
                    // Create a new set so that the original set is not modified inside cached/queued subqueries
                    forbidden = new LinkedHashSet<>(forbidden);
                    forbidden.remove(use);
                    forbidden.add(rvr.localDef());
                    if (this.ppReach.reachable(allocSite.post(), use, forbidden, origin)) {
                        // we need it!
                        return true;
                    }
                }

            }

        }
        return false;
    }

    /**
     * Compute the difference between the set implied by srcIter and the set pointed to by target. here.
     *
     * @param srcIter Iterator over the InstanceKeyRecencys that we want to check whether they are in the points to set
     *            of target.
     * @param target The PointsToGraphNode for the set
     * @param targetIsFlowSensitive Is target flow sensitive?
     * @param addAtPoints If the target is flow sensitive, then we are interested in adding, for each ippr \in
     *            addAtPoints, the set srcIter to PointsToFS(target, ippr). If target is not flow sensitive then this
     *            argument is ignored.
     * @param filterBaseNodeOfField
     * @param originator
     * @return
     */
    private IntSet getDifferenceFlowSensitive(/*Iterator<InstanceKeyRecency>*/IntIterator srcIter,
    /*PointsToGraphNode*/int target, ExplicitProgramPointSet addAtPoints) {
        assert isFlowSensitivePointsToGraphNode(target);
        target = this.getRepresentative(target);

        assert addAtPoints != null && !addAtPoints.isEmpty() : "If the target is flow sensitive, then we should have at least one program point to consider.";

        if (!srcIter.hasNext()) {
            // nothing in there, return an empty set.
            return EmptyIntSet.INSTANCE;
        }

        MutableIntSet s = MutableSparseIntSet.makeEmpty();

        // target is flow sensitive.
        ConcurrentIntMap<ProgramPointSetClosure> m = this.pointsToSetFS(target);
        while (srcIter.hasNext()) {
            int i = srcIter.next();
            ProgramPointSetClosure pps = m.get(i);
            if (pps == null || !pps.containsAll(addAtPoints, this, null)) {
                // Note that we don't need to pass an origin to pps.containsAll, since we don't care if a reachability query goes from false to true,
                // since it won't cause us to do any additional work, it would just mean that we wouldn't have added i to the set.

                // we have i \not\in pointsTo(target, ippr) for some ippr \in addAtPoints
                s.add(i);
            }
        }

        return s;

    }

    public int numPointsToGraphNodes() {
        return this.pointsToFI.size() + this.pointsToFS.size();
    }

    /**
     * Get the flow-insensitive points to set of node n.
     *
     * @param n node
     * @return points to set of n
     */
    private MutableIntSet pointsToSetFI(/*PointsToGraphNode*/int n) {
        assert !isFlowSensitivePointsToGraphNode(n);
        MutableIntSet s = this.pointsToFI.get(n);
        if (s == null && !graphFinished) {
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet ex = this.pointsToFI.putIfAbsent(n, s);
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

    /**
     * Get the flow-sensitive points to set of node n.
     *
     * @param n node
     * @return points to set of n
     */
    ConcurrentIntMap<ProgramPointSetClosure> pointsToSetFS(/*PointsToGraphNode*/int n) {
        assert isFlowSensitivePointsToGraphNode(n);
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

    /**
     * Add all the InstanceKeyRecency in set to the pointsTo set of n. If n is not flow sensitive, then we ignore
     * ppsToAdd (and indeed, it should be null). If n is flow sensitive, then we add the set to n at the program points
     * specified. That is, for all ippr in ppsToAdd, we make sure that everything in set is added to pointsToFS(n,
     * ippr).
     *
     * @param n
     * @param nIsFlowSensitive
     * @param ppsToAdd
     * @param set
     * @return
     */
    protected boolean addAllToSet(/*PointsToGraphNode*/int n, boolean nIsFlowSensitive,
                                  ExplicitProgramPointSet ppsToAdd,
                                  /*Set<InstanceKeyRecency>*/IntSet set) {
        if (set.isEmpty()) {
            return false;
        }

        if (!nIsFlowSensitive) {
            assert !isFlowSensitivePointsToGraphNode(n);
            assert ppsToAdd == null;
            MutableIntSet s = pointsToSetFI(n);
            boolean nIsObjectField = baseNodeForPointsToGraphNode(n) >= 0;
            boolean changed = false;
            IntIterator iter = set.intIterator();
            while (iter.hasNext()) {
                int next = iter.next();
                changed |= s.add(next);
                if (isMostRecentObject(next) && nIsObjectField) { // XXX TODO: I don't understand why we restrict to object fields; make some of the code in the conditional dead. What's going on?
                    // n is a flow-insensitive pointstographnode, so if it
                    // points to the most resent version, may also need to point to
                    // the non-most recent version.
                    boolean needsNonMostRecent = true;
                    PointsToGraphNode tn = lookupPointsToGraphNodeDictionary(n);
                    if (tn instanceof ReferenceVariableReplica) {
                        ReferenceVariableReplica rvr = (ReferenceVariableReplica) tn;
                        if (rvr.hasInstantaneousScope()) {
                            needsNonMostRecent = false;
                        }
                        else if (rvr.hasLocalScope()) {
                            // rvr has a local scope, and we can possible be more precise.
                            needsNonMostRecent = isAllocInScope(rvr, next, new AddNonMostRecentOrigin(n, rvr, next));
                        }
                    }
                    if (needsNonMostRecent) {
                        changed |= s.add(nonMostRecentVersion(next));
                    }
                }
            }
            return changed;
        }
        // flow sensitive!
        assert ppsToAdd != null;
        boolean changed = false;
        ConcurrentIntMap<ProgramPointSetClosure> m = pointsToSetFS(n);
        IntIterator iter = set.intIterator();
        while (iter.hasNext()) {
            int to = iter.next();
            changed |= addProgramPoints(m, n, to, ppsToAdd);
            // If we are adding an edge to the most recent version of a node,
            // make sure that the ConcurrentIntMap contains a ProgramPointSetClosure for the not-most recent version.
            if (this.isMostRecentObject(to) && this.isTrackingMostRecentObject(to)) {
                addProgramPoints(m, n, this.nonMostRecentVersion(to), ExplicitProgramPointSet.EMPTY_SET);
            }
        }
        return changed;
    }

    private boolean addProgramPoints(ConcurrentIntMap<ProgramPointSetClosure> m, /*PointsToGraphNode*/int from,
    /*InstanceKeyRecency*/int to, ExplicitProgramPointSet toAdd) {
        ProgramPointSetClosure p = m.get(to);
        if (p == null) {
            p = new ProgramPointSetClosure(from, to, this);
            ProgramPointSetClosure existing = m.putIfAbsent(to, p);
            if (existing != null) {
                p = existing;
            }
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
            children = EmptyIntSet.INSTANCE;
        }
        IntIterator childIterator = children.intIterator();
        while (childIterator.hasNext()) {
            int child = childIterator.next();
            this.findCycles(child, visited, currentlyVisiting, currentlyVisitingStack, toCollapse);
        }

        currentlyVisiting.remove(n);
        currentlyVisitingStack.pop();

    }

    public void constructionFinished() {
        this.graphFinished = true;

        // set various fields to null to allow them to be garbage collected.
        this.concreteTypeDictionary = null;
        this.isUnfilteredSubsetOf = null;
        this.isFilteredSubsetOf = null;

        // construct the call graph before we clear out a lot of stuff.
        this.getCallGraph();
        this.reachableContexts = null;
        this.classInitializers = null;
        this.entryPoints = null;


        // make more compact, read-only versions of the sets.
        IntIterator keyIterator = pointsToFI.keyIterator();
        while (keyIterator.hasNext()) {
            int key = keyIterator.next();
            MutableIntSet ms = pointsToFI.get(key);
            MutableIntSet newMS = MutableSparseIntSet.make(ms);
            pointsToFI.put(key, newMS);
        }

        this.pointsToFI = compact(this.pointsToFI);
        this.instanceKeyDictionary = compact(this.instanceKeyDictionary);
        this.reverseGraphNodeDictionary = compact(this.reverseGraphNodeDictionary);
        // XXX needed for points-to iterator
        this.graphNodeDictionary = compact(this.graphNodeDictionary);
        // XXX needed for reachability for points-to set iteration
        this.callGraphMap = compact(this.callGraphMap);
        this.reverseInstanceKeyDictionary = compact(this.reverseInstanceKeyDictionary);

        keyIterator = pointsToFS.keyIterator();
        while (keyIterator.hasNext()) {
            int key = keyIterator.next();
            ConcurrentIntMap<ProgramPointSetClosure> ms = pointsToFS.get(key);
            pointsToFS.put(key, compact(ms));
        }
    }

    /**
     * Produce a more compact map. This reduces memory usage, but gives back a read-only map.
     */
    private static <V> ConcurrentIntMap<V> compact(ConcurrentIntMap<V> m) {
        boolean dense = ((float) m.size() / ((float) (m.max() + 1))) > 0.5;

        IntMap<V> newMap = dense ? new DenseIntMap<V>(Math.max(m.max() + 1, 0))
                : new SparseIntMap<V>(Math.max(m.max() + 1, 0));
        IntIterator keyIterator = m.keyIterator();
        while (keyIterator.hasNext()) {
            int key = keyIterator.next();
            newMap.put(key, m.get(key));
        }
        if (dense) {
            float util = ((DenseIntMap<?>) newMap).utilization();
            int length = Math.round(newMap.size() / util);
            if (util < 1.0) {
                System.err.println("   Utilization of DenseIntMap: " + String.format("%.3f", util) + " (approx "
                        + (length - newMap.size()) + " empty slots out of " + length + ")");
            }
        }
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

    /**
     * If n is the most recent object.
     */
    public boolean isMostRecentObject(/*InstanceKeyRecency*/int n) {
        return this.lookupInstanceKeyDictionary(n).isRecent();
    }

    /**
     * If n is tracking the most recent object.
     */
    public boolean isTrackingMostRecentObject(/*InstanceKeyRecency*/int n) {
        return this.lookupInstanceKeyDictionary(n).isTrackingMostRecent();
    }

    /**
     * Record the allocation of ikr at programpoint ppr.
     */
    public boolean recordAllocation(InstanceKeyRecency ikr, ProgramPointReplica ppr) {
        int n = this.lookupDictionary(ikr);
        Set<ProgramPointReplica> s = this.allocationSites.get(n);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<ProgramPointReplica> ex = this.allocationSites.putIfAbsent(n, s);
            if (ex != null) {
                // someone beat us to it!
                s = ex;
            }
        }
        return s.add(ppr);
    }

    /**
     * Get allocation sites of an InstanceKeyRecency n.
     */
    public Set<ProgramPointReplica> getAllocationSitesOf(/*InstanceKeyRecency*/int n) {
        Set<ProgramPointReplica> s = this.allocationSites.get(n);
        if (s == null) {
            return Collections.emptySet();
        }
        return s;
    }

    /**
     * Get the integer representation of the most recent version of InstanceKeyRecency ikr that is mapped by n
     */
    public int mostRecentVersion(/*InstanceKeyRecency*/int n) {
        InstanceKeyRecency ikr = lookupInstanceKeyDictionary(n);
        return this.lookupDictionary(ikr.recent(true));
    }

    /**
     * Get the integer representation of the non-most recent version of InstanceKeyRecency ikr that is mapped by n
     */
    public int nonMostRecentVersion(/*InstanceKeyRecency*/int n) {
        assert n >= 0;
        InstanceKeyRecency ikr = lookupInstanceKeyDictionary(n);
        assert ikr != null;
        return this.lookupDictionary(ikr.recent(false));
    }

    /**
     * Check if a node is flow-senstive.
     */
    public boolean isFlowSensitivePointsToGraphNode(/*PointsToGraphNode*/int n) {
        return this.graphNodeDictionary.get(n).isFlowSensitive();
    }

    /**
     * Get the nullInstanceKey.
     */
    public InstanceKeyRecency nullInstanceKey() {
        return nullInstanceKey;
    }

    /**
     * Check if an InstanceKeyRecency is the nullInstanceKey.
     */
    public boolean isNullInstanceKey(InstanceKeyRecency ikr) {
        return ikr == nullInstanceKey;
    }

    /**
     * Check if ikr is the int representation of nullInstanceKey.
     */
    public boolean isNullInstanceKey(/*InstanceKeyRecency*/int ikr) {
        return ikr == nullInstanceKeyInt;
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

    public ProgramPointReachability programPointReachability() {
        return this.ppReach;
    }

    /**
     * Get the call sites within the given method.
     *
     * @param caller
     * @return
     */
    public Set<ProgramPointReplica> getCallSitesWithinMethod(OrderedPair<IMethod, Context> cgnode) {
        assert !cgnode.fst().isClinit() || cgnode.snd().equals(haf.initialContext()) : " Class inits such as, "
                + PrettyPrinter.methodString(cgnode.fst()) + " should be analyzed in the initial context not "
                + cgnode.snd();
        Set<ProgramPointReplica> callSites = new LinkedHashSet<>();
        for (CallSiteProgramPoint cs : this.registrar.getCallSitesWithinMethod(cgnode.fst())) {
            callSites.add(cs.getReplica(cgnode.snd()));
        }
        return callSites;
    }

    /**
     * Get the methods that the callSite may call.
     *
     * @param callee
     * @return
     */

    public Set<OrderedPair<IMethod, Context>> getCalleesOf(ProgramPointReplica callSite) {
        OrderedPair<CallSiteProgramPoint, Context> callSiteNode = new OrderedPair<>((CallSiteProgramPoint) callSite.getPP(),
                                                                                    callSite.getContext());
        Set<OrderedPair<IMethod, Context>> calleeCallGraphNodes = this.callGraphMap.get(callSiteNode);
        if (calleeCallGraphNodes != null) {
            return calleeCallGraphNodes;
        }
        return Collections.emptySet();

    }

    /**
     * Get the call sites that call the callee.
     *
     * @param callee
     * @return
     */
    public Set<OrderedPair<CallSiteProgramPoint, Context>> getCallersOf(OrderedPair<IMethod, Context> callee) {
        Set<OrderedPair<CallSiteProgramPoint, Context>> callerCallGraphNodes = this.callGraphReverseMap.get(callee);
        if (callerCallGraphNodes != null) {
            return callerCallGraphNodes;
        }
        return Collections.emptySet();
    }

    public class PointsToIntersectIntIterator implements IntIterator {
        final IntIterator iter;
        final/*PointsToGraphNode*/int n;
        final ExplicitProgramPointSet pps;
        final ReachabilityQueryOriginMaker originMaker;

        private int next = -1;

        public PointsToIntersectIntIterator(IntIterator iter, /*PointsToGraphNode*/int n, ExplicitProgramPointSet pps,
                                            ReachabilityQueryOriginMaker originMaker) {
            this.iter = iter;
            this.n = n;
            this.pps = pps;
            this.originMaker = originMaker;
        }

        @Override
        public boolean hasNext() {
            while (next < 0 && iter.hasNext()) {
                int i = iter.next();
                ProgramPointSetClosure ppc = PointsToGraph.this.pointsToSetFS(n).get(i);
                if (ppc == null || ppc.isEmpty()) {
                    // nope
                    continue;
                }
                for (InterProgramPointReplica x : pps) {
                    if (ppc.contains(x, PointsToGraph.this, originMaker.makeOrigin(n, i, x))) {
                        next = i;
                        break;
                    }
                }
            }
            return next >= 0;
        }

        @Override
        public int next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            assert next >= 0;
            int temp = next;
            next = -1;
            return temp;
        }

    }

    /**
     * Write points-to-graph representation to file.
     *
     * For rvr nodes, Red: tracking flow-sensitive --> outgoing edges are red with program points, Blue: not tracking
     * flow-sensitive --> outgoing edges are blue without annotations
     *
     * For ikr nodes, Red: most-recent object --> outgoing edges are red with field name and program points, Blue:
     * non-most-recent object --> outgoing edges are blue with field name
     *
     */
    public void dumpPointsToGraphToFile(String filename) {
        String file = filename;
        String fullFilename = file + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpPointsToGraph(out);
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    public Writer dumpPointsToGraph(Writer writer) throws IOException {
        double spread = 2.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]" + ";\n");

        DotNodesRepMap repMap = new DotNodesRepMap();

        IntIterator fromIter = pointsToFS.keyIterator();
        writer.write("// EDGES\n");
        while (fromIter.hasNext()) {
            int f = fromIter.next();
            PointsToGraphNode from = lookupPointsToGraphNodeDictionary(f);

            // print flow-sensitive points-to relations for reference variable
            if (from instanceof ReferenceVariableReplica) {
                ReferenceVariableReplica rvr = (ReferenceVariableReplica) from;
                String fromNode = repMap.getRepOrPutIfAbsent(rvr);

                IntMap<ProgramPointSetClosure> ikrToPP = pointsToFS.get(f);
                IntIterator toIter = ikrToPP.keyIterator();
                while (toIter.hasNext()) {
                    int t = toIter.next();
                    InstanceKeyRecency to = lookupInstanceKeyDictionary(t);
                    String toNode = repMap.getRepOrPutIfAbsent(to);
                    if (!registrar.shouldUseSimplePrint() || (shouldPrint(to) && shouldPrint(rvr))) {
                        repMap.addPrint(rvr);
                        repMap.addPrint(to);
                        Collection<InterProgramPointReplica> sources = ikrToPP.get(t).getSources(this, null);
                        if (!sources.isEmpty()) {
                            writer.write("\t" + fromNode + " -> " + toNode + " [color=red,label=\"" + sources
                                    + "\"];\n");
                        }
                    }
                }
            }

            // print flow-sensitive points-to relations for object field
            else {
                assert from instanceof ObjectField : "Invalid PointsToGraphNode type.";
                ObjectField of = (ObjectField) from;
                InstanceKeyRecency fromIkr = of.receiver();
                String fromNode = repMap.getRepOrPutIfAbsent(fromIkr);

                IntMap<ProgramPointSetClosure> ikrToPP = pointsToFS.get(f);
                IntIterator toIter = ikrToPP.keyIterator();
                while (toIter.hasNext()) {
                    int t = toIter.next();
                    InstanceKeyRecency to = lookupInstanceKeyDictionary(t);
                    String toNode = repMap.getRepOrPutIfAbsent(to);
                    if (!registrar.shouldUseSimplePrint() || (shouldPrint(to) && shouldPrint(fromIkr))) {
                        repMap.addPrint(fromIkr);
                        repMap.addPrint(to);
                        Collection<InterProgramPointReplica> sources = ikrToPP.get(t).getSources(this, null);
                        if (!sources.isEmpty()) {
                            writer.write("\t" + fromNode + " -> " + toNode + " [color=red,label=\"" + of.fieldName()
                                    + "," + sources + "\"];\n");
                        }
                    }
                }
            }
        }

        fromIter = pointsToFI.keyIterator();
        while (fromIter.hasNext()) {
            int f = fromIter.next();
            PointsToGraphNode from = lookupPointsToGraphNodeDictionary(f);

            // print flow-insentsitive points-to relations for reference variable
            if (from instanceof ReferenceVariableReplica) {
                ReferenceVariableReplica rvr = (ReferenceVariableReplica) from;
                String fromNode = repMap.getRepOrPutIfAbsent(rvr);

                MutableIntSet ikrToPP = pointsToFI.get(f);
                IntIterator toIter = ikrToPP.intIterator();
                while (toIter.hasNext()) {
                    int t = toIter.next();
                    InstanceKeyRecency to = lookupInstanceKeyDictionary(t);
                    String toNode = repMap.getRepOrPutIfAbsent(to);
                    if (!registrar.shouldUseSimplePrint() || (shouldPrint(to) && shouldPrint(rvr))) {
                        repMap.addPrint(rvr);
                        repMap.addPrint(to);
                        writer.write("\t" + fromNode + " -> " + toNode + " [color=blue];\n");
                    }
                }
            }

            // print flow-insentsitive points-to relations for object field
            else {
                assert from instanceof ObjectField : "Invalid PointsToGraphNode type.";
                ObjectField of = (ObjectField) from;
                InstanceKeyRecency fromIkr = of.receiver();
                String fromNode = repMap.getRepOrPutIfAbsent(fromIkr);

                MutableIntSet ikrToPP = pointsToFI.get(f);
                IntIterator toIter = ikrToPP.intIterator();
                while (toIter.hasNext()) {
                    int t = toIter.next();
                    InstanceKeyRecency to = lookupInstanceKeyDictionary(t);
                    String toNode = repMap.getRepOrPutIfAbsent(to);
                    if (!registrar.shouldUseSimplePrint() || (shouldPrint(to) && shouldPrint(fromIkr))) {
                        repMap.addPrint(fromIkr);
                        repMap.addPrint(to);
                        writer.write("\t" + fromNode + " -> " + toNode + " [color=blue,label=\"" + of.fieldName()
                            + "\"];\n");
                    }
                }
            }
        }

        writer.write("\n// NODES\n");
        repMap.writeNodes(writer);

        writer.write("};\n");
        return writer;
    }

    private boolean shouldPrint(InstanceKeyRecency ikr) {
        return isNullInstanceKey(ikr) || !ikr.getConcreteType().toString().contains("Exception")
                && !ikr.toString().contains("allocated at void com.ibm.wala.FakeRootClass")
                && !ikr.toString().contains("allocated at void java.lang.String.<clinit>");
    }

    private static boolean shouldPrint(ReferenceVariableReplica rvr) {
        String s = rvr.getExpectedType().toString();
        return !s.contains("Exception") && !rvr.toString().contains("java.lang.String.CASE_INSENSITIVE_ORDER")
                && !rvr.toString().contains("java.lang.String.serialPersistentFields");
    }

    protected static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private class DotNodesRepMap {
        // map from ikr to string in dot file
        final Map<InstanceKeyRecency, String> ikrToDotNode;
        // map from rvr to string in dot file
        final Map<ReferenceVariableReplica, String> rvrToDotNode;
        // set of ikr that should be printed
        final Set<InstanceKeyRecency> printIkr;
        // set of rvr that should be printed
        final Set<ReferenceVariableReplica> printRvr;

        public DotNodesRepMap() {
            ikrToDotNode = new HashMap<>();
            rvrToDotNode = new HashMap<>();
            printIkr = new HashSet<>();
            printRvr = new HashSet<>();
        }

        /**
         * get String from rvr, put if not present
         *
         * @param rvr
         * @return
         */
        public String getRepOrPutIfAbsent(ReferenceVariableReplica rvr) {
            String s = rvrToDotNode.get(rvr);
            if (s == null) {
                s = "\"" + escape(rvr.toString()) + "\"";
                rvrToDotNode.put(rvr, s);
            }
            return s;
        }

        /**
         * get String from ikr, put if not presence
         *
         * @param ikr
         * @return
         */
        public String getRepOrPutIfAbsent(InstanceKeyRecency ikr) {
            String s = ikrToDotNode.get(ikr);
            if (s == null) {
                s = "\"" + escape(ikr.toString()) + "\"";
                ikrToDotNode.put(ikr, s);
            }
            return s;
        }

        /**
         * print all nodes with labels and colors
         *
         * @param writer
         * @throws IOException
         */
        public void writeNodes(Writer writer) throws IOException {
            for (ReferenceVariableReplica rvr : printRvr) {
                String color = rvr.isFlowSensitive() ? "red" : "blue";
                writer.write("\t" + rvrToDotNode.get(rvr) + " [color=" + color + "];\n");
            }

            for (InstanceKeyRecency ikr : printIkr) {
                String color = ikr.isRecent() ? "red" : "blue";
                writer.write("\t" + ikrToDotNode.get(ikr) + " [fontcolor=white, style=filled, fillcolor=" + color
                        + "];\n");
            }
        }

        public void addPrint(InstanceKeyRecency ikr) {
            printIkr.add(ikr);
        }

        public void addPrint(ReferenceVariableReplica rvr) {
            printRvr.add(rvr);
        }

    }
}
