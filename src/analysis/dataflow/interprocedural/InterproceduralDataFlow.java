package analysis.dataflow.interprocedural;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.AbstractValue;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

/**
 * Manages the running of an inter-procedural data-flow analysis
 * 
 * <F> Type of data-flow facts propagated by this analysis
 */
public abstract class InterproceduralDataFlow<F extends AbstractValue<F>> {

    /**
     * Procedure call graph
     */
    private final CallGraph cg;
    /**
     * Points-to graph
     */
    private final PointsToGraph ptg;
    /**
     * Analysis work-queue containing call graph nodes to be processed
     */
    private final WorkQueue<CGNode> q;
    /**
     * Computed results for each call graph node analyzed
     */
    protected final Map<CGNode, AnalysisRecord<F>> recordedResults = new LinkedHashMap<>();
    /**
     * Nodes that are currently being processed, used to detect recursive calls
     */
    private final Set<CGNode> currentlyProcessing = new HashSet<>();
    /**
     * Record of which CG nodes need to be re-analyzed if a given node changes
     */
    private final Map<CGNode, Set<CGNode>> dependencyMap = new HashMap<>();
    /**
     * Whether the analysis results for the given CGNode were soundly computed i.e. they did not use unsound results for
     * a recursive call
     */
    private final Map<CGNode, Boolean> soundResultsSoFar = new HashMap<>();
    /**
     * Specifies the logging level
     */
    private int outputLevel = 0;
    /**
     * debugging map to make sure there are infinite loops
     */
    private final Map<CGNode, Integer> iterations = new HashMap<>();
    /**
     * Results of a reachability analysis
     */
    private final ReachabilityResults reachable;
    /**
     * debugging map to make sure there are infinite loops
     */
    private final Map<CGNode, Integer> requests = new HashMap<>();
    /**
     * Mapping from local variable to reference variable
     */
    private final ReferenceVariableCache rvCache;

    /**
     * Construct a new inter-procedural analysis over the given call graph
     * 
     * @param cg
     *            call graph this analysis will be over
     * @param ptg
     *            points-to graph
     * @param rvCache
     *            Mapping from local variable to reference variable
     */
    public InterproceduralDataFlow(PointsToGraph ptg, ReachabilityResults reachable, ReferenceVariableCache rvCache) {
        this.cg = ptg.getCallGraph();
        this.ptg = ptg;
        this.q = new WorkQueue<>();
        this.reachable = reachable;
        this.rvCache = rvCache;
    }

    /**
     * Run the inter-procedural analysis
     */
    public final void runAnalysis() {
        System.err.println("RUNNING: " + getAnalysisName());
        long start = System.currentTimeMillis();

        preAnalysis(cg, q);
        while (!q.isEmpty()) {
            CGNode current = q.poll();
            if (getOutputLevel() >= 2) {
                System.err.println("QUEUE_POLL: " + PrettyPrinter.cgNodeString(current));
            }
            AnalysisRecord<F> results = getLatestResults(current);

            F input;
            if (current.equals(cg.getFakeRootNode()) || cg.getEntrypointNodes().contains(current)) {
                // This is an entry node
                input = getInputForEntryPoint();
            } else {
                input = results.getInput();
            }

            processCallGraphNode(current, input, results == null ? null : results.getOutput());
        }

        postAnalysis();

        System.err.println("FINISHED: " + getAnalysisName() + " it took " + (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Initialize the work-queue and Perform other operations before this inter-procedural data-flow analysis begins.
     */
    protected void preAnalysis(CallGraph cg, WorkQueue<CGNode> q) {
        Collection<CGNode> entryPoints = cg.getEntrypointNodes();

        // These are the class initializers
        q.addAll(entryPoints);
        // Also add the fake root method (which calls main)
        q.add(cg.getFakeRootNode());
    }

    /**
     * Perform operations after this inter-procedural data-flow analysis has completed. This could be used to construct
     * analysis results.
     */
    protected void postAnalysis() {
        // Intentionally blank
        // Subclasses should override as needed
    }

    protected abstract String getAnalysisName();

    /**
     * Increment the counter giving the number of times the given node has been analyzed
     * 
     * @param n
     *            node to increment for
     * @return incremented counter
     */
    private int incrementCounter(CGNode n) {
        Integer i = iterations.get(n);
        if (i == null) {
            i = 0;
        }
        i++;
        iterations.put(n, i);
        if (i >= 100) {
            throw new RuntimeException("Analyzed the same CG node " + i + " times: " + PrettyPrinter.cgNodeString(n));
        }
        return i;
    }

    /**
     * Increment the counter giving the number of times the given node has been requested (and the request returned the
     * latest results
     * 
     * @param n
     *            node to increment for
     * @return incremented counter
     */
    private int incrementRequestCounter(CGNode n) {
        Integer i = requests.get(n);
        if (i == null) {
            i = 0;
        }
        i++;
        if (i >= 100) {
            throw new RuntimeException("Requested the same CG node " + i + " times for method: "
                                            + PrettyPrinter.cgNodeString(n));
        }
        return i;
    }

    /**
     * Get any nodes that have to be reanalyzed if the result of analyzing n changes
     * 
     * @param n
     *            call graph node to get the dependencies for
     * @return set of nodes that need to be reanalyzed if the result of analyzing n changes
     */
    private Set<CGNode> getDependencies(CGNode n) {
        Set<CGNode> deps = dependencyMap.get(n);
        if (deps == null) {
            deps = Collections.emptySet();
        }
        return deps;
    }

    /**
     * Ensure that n2 will be reanalyzed if the result of analyzing n1 changes
     * 
     * @param n1
     *            node on which the n2 depends
     * @param n2
     *            node that depends on the output of analyzing n1
     */
    private void addDependency(CGNode n1, CGNode n2) {
        Set<CGNode> deps = dependencyMap.get(n1);
        if (deps == null) {
            deps = new HashSet<>();
            dependencyMap.put(n1, deps);
        }
        deps.add(n2);
    }

    /**
     * Get the call graph this is an analysis over
     * 
     * @return call graph
     */
    public CallGraph getCallGraph() {
        return cg;
    }

    /**
     * Get the previously computed points-to graph
     * 
     * @return points-to graph
     */
    public PointsToGraph getPointsToGraph() {
        return ptg;
    }

    /**
     * Get the results of analyzing the callee
     * 
     * @param caller
     *            caller's call graph node
     * @param callee
     *            calee's call graph node
     * @param input
     *            initial data-flow fact
     * @return map from exit type (normal or exceptional) to data-flow fact
     * 
     */
    public Map<ExitType, F> getResults(CGNode caller, CGNode callee, F input) {
        if (getOutputLevel() >= 4) {
            System.err.println("GETTING:\n\t" + PrettyPrinter.cgNodeString(callee));
            System.err.println("\tFROM: " + PrettyPrinter.cgNodeString(caller));
            System.err.println("\tINPUT: " + input);
        }
        incrementRequestCounter(callee);
        AnalysisRecord<F> previous = getLatestResults(callee);

        AnalysisRecord<F> results;
        if (previous != null && existingResultSuitable(input, previous)) {
            results = previous;
            printResults(callee, "PREVIOUS", results.getOutput());
        } else {
            if (previous == null) {
                results = new AnalysisRecord<>(input, getDefaultOutput(input), false);
            } else {
                // TODO use widen for back edges
                results = new AnalysisRecord<>(input.join(previous.getInput()), previous.getOutput(), false);
            }

            if (currentlyProcessing.contains(callee)) {
                // Already processing the callee, this is a recursive call, just
                // use latest results and process this later with the new input
                q.add(callee);
                if (outputLevel >= 4) {
                    System.err.println("ALREADY PROCESSING: " + PrettyPrinter.cgNodeString(callee) + " requested from "
                                                    + PrettyPrinter.cgNodeString(caller));
                }
                recordedResults.put(callee, results);
                printResults(callee, "LATEST", results.getOutput());
            } else {
                results = processCallGraphNode(callee, results.getInput(),
                                                previous == null ? null : previous.getOutput());
            }
        }

        if (!results.isSoundResult()) {
            // Ensure that the caller will be re-analyzed if the results for the
            // callee change
            addDependency(callee, caller);
        }

        // Record whether sound results will be returned to the caller
        soundResultsSoFar.put(caller, isSoundResultsSoFar(caller) && results.isSoundResult());
        return results.getOutput();
    }

    /**
     * Print the results to the screen if the logging level is high enough
     * 
     * @param n
     *            node the results are for
     * @param label
     *            label for type of output (e.g. "PREVIOUS", "NEW", "LATEST")
     * @param output
     *            output of analysis
     */
    private void printResults(CGNode n, String typeLabel, Map<ExitType, F> output) {
        if (getOutputLevel() >= 2) {
            System.err.print("RESULTS:\t" + PrettyPrinter.cgNodeString(n));
            System.err.println("\t" + typeLabel + ": " + output);
        }
    }

    /**
     * Check if the results computed for the given node have only used sound results so far. This could be false if
     * there are recursive calls for which we had to use unsound results.
     * 
     * @param n
     *            node to check
     * @return true if the results for <code>n</code> are sound
     */
    private boolean isSoundResultsSoFar(CGNode n) {
        Boolean b = soundResultsSoFar.get(n);
        return b == null ? true : b;
    }

    /**
     * Process the given node using the given input, record the results. If the output changes then add dependencies to
     * the work-queue.
     * 
     * @param n
     *            node to process
     * @param input
     *            input to the data-flow for the node
     * @param previousOutput
     *            previous analysis results, used to determine if the output changed
     * @return output after analyzing the given node with the given input
     */
    private AnalysisRecord<F> processCallGraphNode(CGNode n, F input, Map<ExitType, F> previousOutput) {
        incrementCounter(n);
        currentlyProcessing.add(n);
        Map<ExitType, F> output;
        if (n.getMethod().isNative() && !AnalysisUtil.hasSignature(n.getMethod())) {
            output = analyzeNative(n, input);
        } else {
            output = analyze(n, input);
        }
        AnalysisRecord<F> results = new AnalysisRecord<>(input, output, isSoundResultsSoFar(n));
        recordedResults.put(n, results);
        currentlyProcessing.remove(n);

        if (previousOutput == null || outputChanged(previousOutput, output)) {
            // The output changed add dependencies to the queue
            if (outputLevel >= 4) {
                System.err.println("OUTPUT CHANGED from\n\t" + previousOutput + " TO\n\t" + output);
                System.err.println("\tFOR: " + PrettyPrinter.cgNodeString(n));
                for (CGNode cgn : getDependencies(n)) {
                    System.err.println("\tDEP: " + PrettyPrinter.cgNodeString(cgn));
                }
            }
            q.addAll(getDependencies(n));
        }
        printResults(n, "NEW", output);
        return results;
    }

    /**
     * Get the logging level for this class
     * 
     * @return logging level (higher is more)
     */
    public int getOutputLevel() {
        return outputLevel;
    }

    /**
     * Set the logging level for this class
     * 
     * @param outputLevel
     *            logging level (higher is more)
     */
    public void setOutputLevel(int outputLevel) {
        this.outputLevel = outputLevel;
    }

    /**
     * Get the latest results, may return null if none have been computed yet
     * 
     * @param n
     *            node to get results for
     * @return latest results for the given node or null if there are none
     */
    private AnalysisRecord<F> getLatestResults(CGNode n) {
        return recordedResults.get(n);
    }

    /**
     * Results of an inter-procedural reachability analysis
     * 
     * @return results of a reachability analysis
     */
    public ReachabilityResults getReachabilityResults() {
        return reachable;
    }

    /**
     * Get the results after running this analysis. These may be unsound until the analysis has completed.
     * 
     * @return results of the inter-procedural analysis
     */
    public abstract AnalysisResults getAnalysisResults();

    /**
     * Analyze the given node with the given input data-flow fact
     * 
     * @param n
     *            node to analyze
     * @param input
     *            initial data-flow fact
     * @return output facts resulting from analyzing <code>n</code>
     */
    protected abstract Map<ExitType, F> analyze(CGNode n, F input);

    /**
     * Analyze the given node with the given input data-flow fact
     * 
     * @param n
     *            node to analyze
     * @param input
     *            initial data-flow fact
     * @return output facts resulting from analyzing <code>n</code>
     */
    protected abstract Map<ExitType, F> analyzeNative(CGNode n, F input);

    /**
     * Get the default output data-flow facts (given an input fact), this is used as the output for a recursive call
     * before a fixed point is reached.
     * 
     * @param input
     *            input data-flow fact
     * @return output to be returned to callers when the callee is already in the middle of being analyzed
     */
    protected abstract Map<ExitType, F> getDefaultOutput(F input);

    /**
     * Get the input for the root node of the call graph. This is the initial input to the inter-procedural analysis
     * 
     * @return initial data-flow fact
     */
    protected abstract F getInputForEntryPoint();

    /**
     * Check whether the output changed after analysis, and dependencies need to be reanalyzed.
     * 
     * @param previousOutput
     *            previous output results
     * @param currentOutput
     *            current output results
     * @return true if the output results have changed (and dependencies have to be computed)
     */
    protected abstract boolean outputChanged(Map<ExitType, F> previousOutput, Map<ExitType, F> currentOutput);

    /**
     * Check whether existing output results are suitable, given a new input
     * 
     * @param newInput
     *            new input to the analysis
     * @param existingResults
     *            previous results
     * @return true if the existing results can be reused, false if they must be recomputed using the new input
     */
    protected abstract boolean existingResultSuitable(F newInput, AnalysisRecord<F> existingResults);

    /**
     * Class holding the input and output values for a specific call graph node
     * 
     * @param <F>
     *            type of the data-flow facts
     */
    protected static class AnalysisRecord<F> {

        /**
         * output of the analysis
         */
        private final Map<ExitType, F> output;
        /**
         * input to the analysis
         */
        private final F input;
        /**
         * False if there are back edges and this result could be unsound
         */
        private final boolean isSoundResult;

        /**
         * Create a record for the analysis of a specific call graph node
         * 
         * @param input
         *            input for the analysis
         * @param output
         *            output of the analysis
         * @param isSoundResult
         *            False if there are back edges and this result could be unsound
         */
        public AnalysisRecord(F input, Map<ExitType, F> output, boolean isSoundResult) {
            this.input = input;
            this.output = output;
            this.isSoundResult = isSoundResult;
        }

        /**
         * Get the input used for this analysis
         * 
         * @return the input
         */
        public F getInput() {
            return input;
        }

        /**
         * Get the output returned by this analysis
         * 
         * @return the output
         */
        public Map<ExitType, F> getOutput() {
            return output;
        }

        /**
         * Check whether this record contains sound results. This will be false if there were back edges in the call
         * graph due to recursive calls, and temporary unsound results were used to compute the output.
         * 
         * @return whether the results are sound
         */
        public boolean isSoundResult() {
            return isSoundResult;
        }

        @Override
        public String toString() {
            return "INPUT: " + input + " OUTPUT: " + output + " sound? " + isSoundResult;
        }
    }

    /**
     * Get the abstract locations for a non-static field
     * 
     * @param receiver
     *            value number for the local variable for the receiver of a field access
     * @param field
     *            field
     * @param n
     *            call graph node giving the method and context for the receiver
     * @return set of abstract locations for the field
     */
    public Set<AbstractLocation> getLocationsForNonStaticField(int receiver, FieldReference field, CGNode n) {
        Set<InstanceKey> pointsTo = ptg.getPointsToSet(getReplica(receiver, n));
        if (pointsTo.isEmpty()) {
            throw new RuntimeException("Field target doesn't point to anything. "
                                            + PrettyPrinter.typeString(field.getDeclaringClass()) + "."
                                            + field.getName());
        }

        Set<AbstractLocation> ret = new LinkedHashSet<>();
        for (InstanceKey o : pointsTo) {
            AbstractLocation loc = AbstractLocation.createNonStatic(o, field);
            ret.add(loc);
        }
        return ret;
    }

    /**
     * Get the abstract locations for the contents of an array
     * 
     * @param arary
     *            value number for the local variable for the array
     * @param n
     *            call graph node for the array
     * @return set of abstract locations for the contents of the array
     */
    public Set<AbstractLocation> getLocationsForArrayContents(int array, CGNode n) {
        Set<InstanceKey> pointsTo = ptg.getPointsToSet(getReplica(array, n));
        // if (pointsTo.isEmpty()) {
        // throw new RuntimeException("Array doesn't point to anything. " + getReplica(array, n)
        // + " in " + PrettyPrinter.cgNodeString(n));
        // }

        Set<AbstractLocation> ret = new LinkedHashSet<>();
        for (InstanceKey o : pointsTo) {
            AbstractLocation loc = AbstractLocation.createArrayContents(o);
            ret.add(loc);
        }
        return ret;
    }

    /**
     * Get the reference variable replica for the given local variable in the current context
     * 
     * @param local
     *            value number of the local variable
     * @param n
     *            call graph node giving the method and context for the local variable
     * @return Reference variable replica in the current context for the local
     */
    public ReferenceVariableReplica getReplica(int local, CGNode n) {
        ReferenceVariable rv = rvCache.getReferenceVariable(local, n.getMethod());
        return new ReferenceVariableReplica(n.getContext(), rv);
    }
}
