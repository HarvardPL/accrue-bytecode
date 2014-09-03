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
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.DependencyRecorder;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.engine.PointsToAnalysisMultiThreaded;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.classLoader.CallSiteReference;
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
    private final ConcurrentMap<Integer, InstanceKeyRecency> instanceKeyDictionary = new ConcurrentHashMap<>();
    /**
     * Dictionary for mapping InstanceKeys to ints
     */
    private final ConcurrentMap<InstanceKeyRecency, Integer> reverseInstanceKeyDictionary = new ConcurrentHashMap<>();

    /**
     * Dictionary to record the concrete type of instance keys.
     */
    final ConcurrentMap<Integer, IClass> concreteTypeDictionary = new ConcurrentHashMap<>();


    /**
     * GraphNode counter, for unique integers for GraphNodes
     */
    private final AtomicInteger graphNodeCounter = new AtomicInteger(0);

    /**
     * Dictionary for mapping PointsToGraphNodes to ints
     */
    private final ConcurrentMap<PointsToGraphNode, Integer> reverseGraphNodeDictionary = new ConcurrentHashMap<>();


    /* ***************************************************************************
     *
     * The Points To graph itself. This is represented as several different relations.
     *
     * The base relations express the points to relations of base nodes. This must be flow-insensitive.
     *
     * There are three kinds of subset relations:
     *   1. Flow-insensitive unfiltered
     *   2. Flow-insensitive filtered
     *   3. Flow-sensitive unfiltered
     *
     * (For simplicity, we prevent the fourth possibility, Flow-sensitive filtered, from arising)
     *
     * The RealizedSetCache then instantiates all of the points to sets based on this information.
     */

    private final ConcurrentIntMap<MutableIntSet> base = new SimpleConcurrentIntMap<>();

    /**
     * if "a isBasicSubsetOf b" then the points to set of a is always a subset of the points to set of b.
     */
    private final IntRelation isBasicSubsetOf = new IntRelation();

    /**
     * if "a isFilteredSubsetOf b with filter" then the filter(pointsTo(a)) is a subset of pointsTo(b).
     */
    private final AnnotatedIntRelation<TypeFilter> isFilteredSubsetOf = new AnnotatedIntRelation<>();

    /**
     * if "a isFlowSensitiveSubsetOf b with ippr" then the pointsTo(a) is a subset of pointsTo(b) at program point ippr.
     */
    private final AnnotatedIntRelation<InterProgramPointReplica> isFlowSensitiveSubsetOf = new AnnotatedIntRelation<>();


    /*
     * A cache for realized points to sets.
     */
    RealizedSetCache cache = new RealizedSetCache();

    /**
     * Map from PointsToGraphNodes to PointsToGraphNodes
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
    private final ConcurrentMap<OrderedPair<IMethod, Context>, ConcurrentMap<CallSiteReference, OrderedPair<IMethod, Context>>> callGraphMap = new ConcurrentHashMap<>();

    private HafCallGraph callGraph = null;


    /**
     * Heap abstraction factory.
     */
    private final RecencyHeapAbstractionFactory rhaf;


    private final DependencyRecorder depRecorder;

    private int outputLevel = 0;

    public static boolean DEBUG = false;


    public PointsToGraph(StatementRegistrar registrar, RecencyHeapAbstractionFactory rhaf,
                         DependencyRecorder depRecorder) {
        this.depRecorder = depRecorder;

        this.rhaf = rhaf;

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
            this.getOrCreateContextSet(m).add(this.rhaf.initialContext());
        }
    }

    public IntMap<MutableIntSet> getBaseNodes() {
        return this.base;
    }

    // Return the immediate supersets of PointsToGraphNode n. That is, any node m such that n is an immediate subset of m
    public OrderedPair<IntSet, IntMap<Set<TypeFilter>>> immediateSuperSetsOf(int n) {
        n = this.getRepresentative(n);

        IntSet unfilteredsupersets = this.isBasicSubsetOf.forward(n);
        IntMap<Set<TypeFilter>> supersets = this.isFilteredSubsetOf.forward(n);
        return new OrderedPair<>(unfilteredsupersets, supersets);
    }



    Integer getImmediateRepresentative(/*PointsToGraphNode*/int n) {
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
     * Add an edge from node to an InstanceKey in the graph.
     *
     * @param node
     * @param heapContext
     * @return
     */
    public GraphDelta addEdge(PointsToGraphNode node, InstanceKeyRecency heapContext) {
        assert node != null && heapContext != null;
        assert !node.isFlowSensitive() : "Base nodes must be flow insensitive";
        int n = lookupDictionary(node);

        n = this.getRepresentative(n);

        boolean add = false;
        Integer h = this.reverseInstanceKeyDictionary.get(heapContext);
        if (h == null) {
            // not in the dictionary yet
            h = this.instanceKeyCounter.getAndIncrement();
            // try a put if absent
            Integer existing = this.reverseInstanceKeyDictionary.putIfAbsent(heapContext, h);
            if (existing == null) {
                this.instanceKeyDictionary.put(h, heapContext);
                this.concreteTypeDictionary.put(h, heapContext.getConcreteType());
            }
            else {
                h = existing;
            }
            add = true;
        }

        GraphDelta delta = new GraphDelta(this);
        MutableIntSet pointsToSet = this.getOrCreateBaseSet(n);
        if (add || !pointsToSet.contains(h)) {
            delta.add(n, h);
            pointsToSet.add(h);

            // update the cache for the changes
            this.cache.updateForDelta(delta);
        }


        return delta;
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
    public GraphDelta copyEdges(PointsToGraphNode source, PointsToGraphNode target, InterProgramPointReplica ippr) {
        int s = this.getRepresentative(lookupDictionary(source));
        int t = this.getRepresentative(lookupDictionary(target));

        if (s == t) {
            // don't bother adding
            return new GraphDelta(this);
        }

        // source is a subset of target, target is a superset of source.
        GraphDelta changed = new GraphDelta(this);
        // For the current design, it's important that we tell delta about the copyEdges before actually updating it.
        // XXX!@! AND I'M GOING TO BREAK THAT NOW...
        if (!source.isFlowSensitive() && !target.isFlowSensitive()) {
            // neither source nor target is flow sensitive
            if (this.isBasicSubsetOf.add(s, t)) {
                changed.addCopyEdges(s, t); // XXX THIS WAS EARLIER, i.e., before we modified isUnfilteredSubsetOfFI
                // update the cache for the changes
                this.cache.updateForDelta(changed);
            }
        }
        else {
            // at least one of source and target is flow sensitive
            if (this.isFlowSensitiveSubsetOf.add(s, t, ippr)) {
                changed.addCopyEdges(s, t, ippr); // XXX THIS WAS EARLIER, i.e., before we modified isUnfilteredSubsetOfFI
                // update the cache for the changes
                this.cache.updateForDelta(changed);
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
        assert !source.isFlowSensitive() && !target.isFlowSensitive() : "Can only filter flow-insensitive variables";
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

        // source is a subset of target, target is a superset of source.
        if (isFilteredSubsetOf.add(s, t, filter)) {
            // For the current design, it's important that we tell delta about the copyEdges before actually updating it.
            // XXX!@! AND I'M GOING TO BREAK THAT NOW...
            changed.addCopyEdges(s, filter, t); // XXX THIS WAS EARLIER, i.e., before we modified isUnfilteredSubsetOfFI
            // update the cache for the changes
            this.cache.updateForDelta(changed);
        }


        return changed;
    }


    /**
     * Provide an interatory for the things that n points to. Note that we may
     * not return a set, i.e., some InstanceKeys may be returned multiple times.
     * XXX we may change this in the future...
     *
     * @param n
     * @return
     */
    public Iterator<InstanceKeyRecency> pointsToIterator(PointsToGraphNode n, StmtAndContext originator) {
        return new IntToInstanceKeyIterator(this.pointsToIntIterator(lookupDictionary(n), originator));
    }

    public int graphNodeToInt(PointsToGraphNode n) {
        return lookupDictionary(n);
    }

    public IntIterator pointsToIntIterator(PointsToGraphNode n, StmtAndContext origninator) {
        return pointsToIntIterator(lookupDictionary(n), origninator);
    }
    public IntIterator pointsToIntIterator(/*PointsToGraphNode*/int n, StmtAndContext originator) {
        n = this.getRepresentative(n);
        this.recordRead(n, originator);
        return this.cache.getPointsToSet(n).intIterator();
    }

    /**
     * Does n point to ik?
     */
    //    public boolean pointsTo(/*PointsToGraphNode*/int n, int ik) {
    //        return this.pointsTo(n, ik, null);
    //    }

    /**
     * Does n point to ik?
     */
    private boolean pointsTo(/*PointsToGraphNode*/int n, int ik) {
        IntSet s = this.cache.getPointsToSet(n);
        if (s != null) {
            return s.contains(ik);
        }

        // we don't have a cached version of the points to set. Let's try to be cunning.
        if (this.base.containsKey(n)) {
            // we have a base node,
            return (this.base.get(n).contains(ik));
        }
        throw new RuntimeException("Bad state");//!@!
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
    public boolean addCall(CallSiteReference callSite, IMethod caller,
                           Context callerContext, IMethod callee,
                           Context calleeContext) {
        OrderedPair<IMethod, Context> callerPair = new OrderedPair<>(caller, callerContext);
        OrderedPair<IMethod, Context> calleePair = new OrderedPair<>(callee, calleeContext);

        ConcurrentMap<CallSiteReference, OrderedPair<IMethod, Context>> m = this.callGraphMap.get(callerPair);
        if (m == null) {
            m = AnalysisUtil.createConcurrentHashMap();
            ConcurrentMap<CallSiteReference, OrderedPair<IMethod, Context>> existing = this.callGraphMap.putIfAbsent(callerPair,
                                                                                                                     m);
            if (existing != null) {
                m = existing;
            }
        }
        m.putIfAbsent(callSite, calleePair);

        this.recordContext(callee, calleeContext);
        return true;
    }

    /**
     * Record a callee context for the given method
     *
     * @param callee method
     * @param calleeContext context
     */
    private void recordContext(IMethod callee, Context calleeContext) {
        assert this.callGraph == null : "Cannot record context after callGraph has been instantiated";
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

    private MutableIntSet getOrCreateBaseSet(/*PointsToGraphNode*/int node) {
        MutableIntSet s = this.base.get(node);
        if (s == null) {
            s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
            MutableIntSet existing = this.base.putIfAbsent(node, s);
            if (existing != null) {
                s = existing;
            }
        }
        return s;
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
     * Get the procedure call graph
     *
     * @return call graph
     */
    public CallGraph getCallGraph() {
        if (this.callGraph != null) {
            return this.callGraph;
        }

        // Construct the call graph.
        HafCallGraph callGraph = new HafCallGraph(this.rhaf);
        this.callGraph = callGraph;
        try {
            for (OrderedPair<IMethod, Context> callerPair : this.callGraphMap.keySet()) {
                IMethod caller = callerPair.fst();
                Context callerContext = callerPair.snd();
                CGNode src = callGraph.findOrCreateNode(caller, callerContext);
                ConcurrentMap<CallSiteReference, OrderedPair<IMethod, Context>> m = this.callGraphMap.get(callerPair);
                for (CallSiteReference callSite : m.keySet()) {
                    OrderedPair<IMethod, Context> calleePair = m.get(callSite);
                    IMethod callee = calleePair.fst();
                    Context calleeContext = calleePair.snd();

                    CGNode dst = callGraph.findOrCreateNode(callee, calleeContext);

                    // We are building a call graph so it is safe to call this "deprecated" method
                    src.addTarget(callSite, dst);
                }
            }

            Context initialContext = this.rhaf.initialContext();

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
    void collapseNodes(/*PointsToGraphNode*/int n, /*PointsToGraphNode*/int rep) {
        assert n != rep : "Can't collapse a node with itself";
        if (true) {
            throw new UnsupportedOperationException(); //how to do this concurrently();
        }
        // it is possible that since n and rep were registered, one or both of them were already merged.
        n = this.getRepresentative(n);
        rep = this.getRepresentative(rep);

        if (n == rep) {
            // they have already been merged.
            return;
        }

        // Notify the dependency recorder
        depRecorder.startCollapseNode(n, rep);

        // update the subset and superset graphs.
        this.isBasicSubsetOf.replace(n, rep);
        this.isFilteredSubsetOf.replace(n, rep);

        // update the base nodes.
        assert !this.base.containsKey(n) : "Base nodes shouldn't be collapsed with other nodes";

        this.representative.put(n, rep);
        depRecorder.finishCollapseNode(n, rep);

        // update the cache.
        this.cache.removed(n);

    }

    public void setOutputLevel(int outputLevel) {
        this.outputLevel = outputLevel;
    }

    /**
     * Add class initialization methods
     *
     * @param classInits list of class initializer is initialization order (i.e.
     *            element j is a super class of element j+1)
     * @return true if the call graph changed as a result of this call, false
     *         otherwise
     */
    public boolean addClassInitializers(List<IMethod> classInits) {
        boolean cgChanged = false;
        for (int j = classInits.size() - 1; j >= 0; j--) {
            IMethod clinit = classInits.get(j);
            if (this.classInitializers.add(clinit)) {
                // new initializer
                cgChanged = true;
                Context c = this.rhaf.initialContext();
                this.recordContext(clinit, c);
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
            this.recordContext(newEntryPoint, this.rhaf.initialContext());
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

//    static Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> composeIterators(Set<PointsToGraphNode> unfilteredSet,
//                                                                                 Set<OrderedPair<PointsToGraphNode, TypeFilter>> filteredSet) {
//        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> unfilteredIterator = unfilteredSet == null
//                ? null : new LiftUnfilteredIterator(unfilteredSet.iterator());
//        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> filteredIterator = filteredSet == null
//                ? null : filteredSet.iterator();
//
//        Iterator<OrderedPair<PointsToGraphNode, TypeFilter>> iter;
//        if (unfilteredIterator == null) {
//            if (filteredIterator == null) {
//                iter = Collections.<OrderedPair<PointsToGraphNode, TypeFilter>> emptyIterator();
//            }
//            else {
//                iter = filteredIterator;
//            }
//        }
//        else {
//            if (filteredIterator == null) {
//                iter = unfilteredIterator;
//            }
//            else {
//                iter = new ComposedIterators<>(unfilteredIterator,
//                        filteredIterator);
//            }
//        }
//        return iter;
//    }
    //
    //    public static class ComposedIterators<T> implements Iterator<T> {
    //        Iterator<T> iter1;
    //        Iterator<T> iter2;
    //        public ComposedIterators(Iterator<T> iter1, Iterator<T> iter2) {
    //            this.iter1 = iter1;
    //            this.iter2 = iter2;
    //        }
    //
    //        @Override
    //        public boolean hasNext() {
    //            return this.iter1 != null && this.iter1.hasNext()
    //                    || this.iter2.hasNext();
    //        }
    //
    //        @Override
    //        public T next() {
    //            if (this.iter1 != null) {
    //                if (this.iter1.hasNext()) {
    //                    return this.iter1.next();
    //                }
    //                this.iter1 = null;
    //            }
    //            return this.iter2.next();
    //        }
    //
    //        @Override
    //        public void remove() {
    //            throw new UnsupportedOperationException();
    //        }
    //    }
    //
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
    IntSet getDifference(/*PointsToGraphNode*/int source, TypeFilter filter,
    /*PointsToGraphNode*/int target) {
        source = this.getRepresentative(source);

        IntSet s = this.cache.getPointsToSet(source);
        IntIterator srcIter;
        if (filter == null) {
            srcIter = s.intIterator();
        }
        else {
            srcIter = new FilteredIterator(s.intIterator(), filter);
        }
        return this.getDifference(srcIter, target);

    }

    private IntSet getDifference(IntIterator srcIter, /*PointsToGraphNode*/int target) {
        target = this.getRepresentative(target);

        if (!srcIter.hasNext()) {
            // nothing in there, return an empty set.
            return EmptyIntSet.instance;
        }

        MutableIntSet s = MutableSparseIntSet.makeEmpty();
        while (srcIter.hasNext()) {
            int i = srcIter.next();
            if (!this.pointsTo(target, i)) {
                s.add(i);
            }
        }
        return s;

    }

    /**
     * Return whatever is in set which is not in the points to set of target.
     *
     * @param set
     * @param target
     * @return
     */
    public IntSet getDifference(IntSet set, /*PointsToGraphNode*/int target) {
        return this.getDifference(set.intIterator(), target);
    }

    /**
     * This class implements a cache for realized points-to sets. Well, it's not really a cache, it just keeps
     * everything.
     */
    private class RealizedSetCache {
        private final ConcurrentIntMap<MutableIntSet> cacheFI = new SimpleConcurrentIntMap<>();
        private final ConcurrentIntMap<ConcurrentIntMap<ProgramPointSet>> cacheFS = new SimpleConcurrentIntMap<>();
        private int hits = 0;
        private int misses = 0;
        private int uncachable = 0;


        /**
         * Update or invalidate caches based on the changes represented by the
         * graph delta
         */
        private void updateForDelta(GraphDelta delta) {
            IntIterator iter = delta.domainIterator();//!@! TO DO FOR FLOW SENSITIVE
            while (iter.hasNext()) {
                /*PointsToGraphNode*/int n = iter.next();
                MutableIntSet s = this.getPointsToSetInternal(n);
                if (s != null) {
                    // the set is in the cache!
                    IntSet deltaSet = delta.pointsToSet(n);
                    s.addAll(deltaSet);
                }
            }
        }

        /**
         * Node n has been removed (e.g., collapsed with another node)
         */
        public void removed(/*PointsToGraphNode*/int n) {
            this.cacheFI.remove(n);
            this.cacheFS.remove(n);
        }

        public IntSet getPointsToSet(/*PointsToGraphNode*/int n) {

        }
        public IntSet getPointsToSet(/*PointsToGraphNode*/int n, InterProgramPointReplica ippr) {
            return getPointsToSetInternal(n);
        }

        private MutableIntSet getPointsToSetInternal(/*PointsToGraphNode*/int n) {
            MutableIntSet s = this.cache.get(n);
            if (s != null) {
                // we have it in the cache!
                hits++;
                return s;
            }

            return this.realizePointsToSet(n, MutableSparseIntSet.makeEmpty(), new IntStack(), new Stack<Boolean>());

        }

        private MutableIntSet realizePointsToSet(/*PointsToGraphNode*/int n, MutableIntSet currentlyRealizing,
                                                 IntStack currentlyRealizingStack, Stack<Boolean> shouldCache) {
            assert !PointsToGraph.this.representative.containsKey(n) : "Getting points to set of node that has been merged with another node.";

            try {
                MutableIntSet s = this.cache.get(n);
                if (s != null) {
                    // we have it in the cache!
                    hits++;
                    return s;
                }

                if (currentlyRealizing.contains(n)) {
                    // we are called recursively. Need to handle this specially.
                    // find the index that n first appears at
                    int foundAt = -1;
                    for (int i = 0; i < currentlyRealizingStack.size(); i++) {
                        if (foundAt < 0 && currentlyRealizingStack.get(i) == n) {
                            foundAt = i;
                        }
                        else if (foundAt >= 0) {
                            // it is not safe to cache the result of i. (but will be ok to cache foundAt).
                            shouldCache.setElementAt(false, i);
                        }
                    }
                    // return an empty set, which will let us compute the realization of the
                    // point to set.
                    return PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
                }
                boolean baseContains = PointsToGraph.this.base.containsKey(n);

                IntSet unfilteredSubsets = PointsToGraph.this.isBasicSubsetOf.backward(n);
                IntMap<Set<TypeFilter>> filteredSubsets = PointsToGraph.this.isFilteredSubsetOf.backward(n);

                boolean hasUnfilteredSubsets = unfilteredSubsets != null
                        && !unfilteredSubsets.isEmpty();
                boolean hasFilteredSubsets = filteredSubsets != null
                        && !filteredSubsets.isEmpty();

                if (baseContains
                        && !(hasUnfilteredSubsets || hasFilteredSubsets)) {
                    // n is a base node, and no superset relations
                    return PointsToGraph.this.base.get(n);
                }


                // We now know we definitely missed in the cache (i.e., not a base node, and not one we choose not to cache).
                this.misses++;

                s = PointsToAnalysisMultiThreaded.makeConcurrentIntSet();
                if (baseContains) {
                    s.addAll(PointsToGraph.this.base.get(n));
                }
                shouldCache.push(true);
                currentlyRealizing.add(n);
                currentlyRealizingStack.push(n);

                if (hasUnfilteredSubsets) {
                    IntIterator iter = unfilteredSubsets.intIterator();
                    while (iter.hasNext()) {
                        int x = iter.next();
                        s.addAll(this.realizePointsToSet(x,
                                                         currentlyRealizing,
                                                         currentlyRealizingStack,
                                                         shouldCache));
                    }
                }
                if (hasFilteredSubsets) {
                    IntIterator iter = filteredSubsets.keyIterator();
                    while (iter.hasNext()) {
                        int x = iter.next();
                        Set<TypeFilter> filters = filteredSubsets.get(x);
                        s.addAll(new FilteredIntSet(this.realizePointsToSet(x,
                                                                            currentlyRealizing,
                                                                            currentlyRealizingStack,
                                                                            shouldCache), filters));
                    }
                }
                currentlyRealizing.remove(n);
                currentlyRealizingStack.pop();
                this.storeInCache(n, s);
                return this.cache.get(n);
            }
            finally {
                if (this.hits + this.misses >= 1000000) {
                    System.err.println("  Cache: " + (this.hits + this.misses) + " accesses: " + 100 * this.hits
                            / (this.hits + this.misses) + "% hits; " + this.misses + " misses; " + this.uncachable
                            + " of the misses were uncachable; cache size: " + this.cache.size());
                    this.hits = this.misses = this.uncachable = 0;
                }
            }

        }

        private void storeInCache(/*PointsToGraphNode*/int n, MutableIntSet s) {
            // it is safe for us to cache the result of this realization.

            MutableIntSet ex = this.cache.putIfAbsent(n, s);
            if (ex != null) {
                // someone beat us to it!
                ex.addAll(s);
            }
        }
    }

    public int cycleRemovalCount() {
        return this.representative.size();
    }

    public void findCycles() {
        IntMap<MutableIntSet> toCollapse = new SparseIntMap<>();

        MutableIntSet visited = MutableSparseIntSet.makeEmpty();
        IntIterator iter = this.isBasicSubsetOf.domain();
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
                this.collapseNodes(n, rep);
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

        IntSet children = this.isBasicSubsetOf.backward(n);
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
}
