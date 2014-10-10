package analysis.dataflow.interprocedural;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
    private final WorkQueue<CGNode> q = new WorkQueue<>();
    /**
     * Nodes that are currently being processed, used to detect recursive calls
     */
    protected final Set<CGNode> currentlyProcessing = new HashSet<>();
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
     * Analysis records (input and output) for each call graph node
     */
    protected final AnalysisRecordMap recordedResults = new AnalysisRecordMap();

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
        this.reachable = reachable;
        this.rvCache = rvCache;
    }

    /**
     * Run the inter-procedural analysis
     */
    public final void runAnalysis() {
        System.err.println("RUNNING: " + getAnalysisName());
        long start = System.currentTimeMillis();

        Collection<CGNode> entryPoints = cg.getEntrypointNodes();

        // These are the class initializers
        q.addAll(entryPoints);
        // Also add the fake root method (which calls main)
        q.add(cg.getFakeRootNode());

        while (!q.isEmpty()) {
            CGNode current = q.poll();
            if (getOutputLevel() >= 2) {
                System.err.println("QUEUE_POLL: " + PrettyPrinter.cgNodeString(current));
            }
            AnalysisRecord<F> results = recordedResults.getRecord(current);
            if (getOutputLevel() >= 2) {
                System.err.println("\tPREVIOUS RESULTS: " + results);
            }

            F input;
            if (current.equals(cg.getFakeRootNode()) || cg.getEntrypointNodes().contains(current)) {
                // This is an entry node
                input = getInputForEntryPoint();
                recordedResults.setInitialRecord(current, new AnalysisRecord<>(input, null, true));
            } else {
                assert results != null;
                input = results.getInput();
            }

            processCallGraphNode(current);
        }

        System.err.println("FINISHED: " + getAnalysisName() + " it took " + (System.currentTimeMillis() - start) + "ms");
        System.gc();
        System.err.println("Memory used so far: "
                + (ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1000000) + "MB");
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
        if (getOutputLevel() >= 5) {
            System.err.println("GETTING:\n\t" + PrettyPrinter.cgNodeString(callee));
            System.err.println("\tFROM: " + PrettyPrinter.cgNodeString(caller));
            System.err.println("\tINPUT: " + input);
        }

        incrementRequestCounter(callee);
        AnalysisRecord<F> previous = recordedResults.getRecord(callee);

        AnalysisRecord<F> results;
        if (previous != null && existingResultSuitable(input, previous)) {
            printResults(callee, "PREVIOUS", previous);
        } else {
            if (previous == null) {
                AnalysisRecord<F> initial = new AnalysisRecord<>(input, getDefaultOutput(input), false);
                recordedResults.setInitialRecord(callee, initial);
            } else {
                // TODO use widen for back edges
                recordedResults.updateInput(callee, input.join(previous.getInput()), false);
            }

            if (currentlyProcessing.contains(callee)) {
                // Already processing the callee, this is a recursive call, just
                // use latest results and process this later with the new input
                q.add(callee);
                if (outputLevel >= 4) {
                    System.err.println("ALREADY PROCESSING: " + PrettyPrinter.cgNodeString(callee) + " requested from "
                                                    + PrettyPrinter.cgNodeString(caller));
                }
                printResults(callee, "LATEST", recordedResults.getRecord(callee));
            } else {
                processCallGraphNode(callee);
            }
        }

        results = recordedResults.getRecord(callee);
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
     * @param n node the results are for
     * @param label label for type of output (e.g. "PREVIOUS", "NEW", "LATEST")
     * @param results input and output of analysis
     */
    private void printResults(CGNode n, String typeLabel, AnalysisRecord<F> results) {
        if (getOutputLevel() >= 2) {
            System.err.println("RESULTS " + typeLabel + ":\t" + PrettyPrinter.cgNodeString(n));
            System.err.println("\tINPUT:\t" + results.getInput());
            System.err.println("\tOUTPUT:\t" + results.getOutput());
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
    protected final void processCallGraphNode(CGNode n) {
        incrementCounter(n);
        currentlyProcessing.add(n);

        AnalysisRecord<F> latest = recordedResults.getRecord(n);
        Map<ExitType, F> output;
        F input = latest.getInput();
        assert input != null;

        if (n.getMethod().isNative() && !AnalysisUtil.hasSignature(n.getMethod())) {
            output = analyzeMissingCode(n, input);
        } else {
            output = analyze(n, input);
        }
        currentlyProcessing.remove(n);

        if (latest.getOutput() == null || outputChanged(latest.getOutput(), output)) {
            // The output changed record the change and add dependencies to the queue
            recordedResults.updateOutput(n, output, isSoundResultsSoFar(n));
            q.addAll(getDependencies(n));

            if (outputLevel >= 4) {
                System.err.println("OUTPUT CHANGED from\n\t" + latest.getOutput() + " TO\n\t" + output);
                System.err.println("\tFOR: " + PrettyPrinter.cgNodeString(n));
                for (CGNode cgn : getDependencies(n)) {
                    System.err.println("\tDEP: " + PrettyPrinter.cgNodeString(cgn));
                }
            }
        }
        printResults(n, "NEW", latest);
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
    protected abstract Map<ExitType, F> analyzeMissingCode(CGNode n, F input);

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
        Iterator<InstanceKey> pointsToIter = ptg.pointsToIterator(getReplica(receiver, n), null);
        if (!pointsToIter.hasNext() && outputLevel >= 1) {
            System.err.println("Field target doesn't point to anything. v" + receiver + " in "
                                            + PrettyPrinter.cgNodeString(n) + " accessing field: "
                                            + PrettyPrinter.typeString(field.getDeclaringClass()) + "."
                                            + field.getName());
        }

        Set<AbstractLocation> ret = new LinkedHashSet<>();

        while (pointsToIter.hasNext()) {
            InstanceKey o = pointsToIter.next();
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
        Iterator<InstanceKey> pointsToIter = ptg.pointsToIterator(getReplica(array, n), null);
        if (!pointsToIter.hasNext() && outputLevel >= 1) {
            System.err.println("Array doesn't point to anything. v" + array + " in " + PrettyPrinter.cgNodeString(n));
            System.err.println("\tReplica was " + getReplica(array, n));
        }

        Set<AbstractLocation> ret = new LinkedHashSet<>();
        while (pointsToIter.hasNext()) {
            InstanceKey o = pointsToIter.next();
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
        return new ReferenceVariableReplica(n.getContext(), rv, ptg.getHaf());
    }

    /**
     * Get the cache containing reference variables for local variables
     *
     * @return reference variable cache
     */
    public ReferenceVariableCache getRvCache() {
        return rvCache;
    }

    /**
     * Computed results for each call graph node analyzed
     */
    protected class AnalysisRecordMap {
        /**
         * Internal map of computed results for each call graph node analyzed
         */
        private final Map<CGNode, AnalysisRecord<F>> recordMap = new LinkedHashMap<>();

        /**
         * Update the input for the given call graph node
         *
         * @param n node to update the input for
         * @param newInput new input
         */
        public void updateInput(CGNode n, F newInput, boolean isSoundResultsSoFar) {
            AnalysisRecord<F> prevRec = recordMap.get(n);
            AnalysisRecord<F> newRec = new AnalysisRecord<>(newInput, prevRec.getOutput(), isSoundResultsSoFar);
            recordMap.put(n, newRec);
        }

        /**
         * Update the output for the given call graph node
         *
         * @param n node to update the output for
         * @param newOutput new output
         * @param isSoundResultsSoFar whether the new results are sound
         */
        public void updateOutput(CGNode n, Map<ExitType, F> newOutput, boolean isSoundResultsSoFar) {
            AnalysisRecord<F> prevRec = recordMap.get(n);
            AnalysisRecord<F> newRec = new AnalysisRecord<>(prevRec.getInput(), newOutput, isSoundResultsSoFar);
            recordMap.put(n, newRec);
        }

        /**
         * Get the analysis record containing the latest input and output for the given call graph node.
         *
         * @param n call graph node
         * @return Analysis input and output
         */
        public AnalysisRecord<F> getRecord(CGNode n) {
            assert recordMap.containsKey(n);
            return recordMap.get(n);
        }

        /**
         * Set the initial analyis record for the given call graph node. This is only valid if there was no previous
         * entry for the given call graph node.
         *
         * @param n node to set the record for
         * @param initialRecord record to put into the map
         */
        public void setInitialRecord(CGNode n, AnalysisRecord<F> initialRecord) {
            assert recordedResults.getRecord(n) == null : "Already a record for " + PrettyPrinter.cgNodeString(n);
            recordMap.put(n, initialRecord);
        }
    }
}
