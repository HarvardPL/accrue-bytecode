package analysis.dataflow.interprocedural;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.dataflow.util.ExitType;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;

/**
 * Manages the running of an inter-procedural data-flow analysis
 * 
 * <F> Type of data-flow facts propagated by this analysis
 */
public abstract class InterproceduralDataFlowManager<F> {

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
     * Whether the analysis results for the given CGNode were soundly computed
     * i.e. they did not use unsound results for a recursive call
     */
    private final Map<CGNode, Boolean> soundResultsSoFar = new HashMap<>();
    /**
     * Specifies the logging level
     */
    private int outputLevel = 0;
    /**
     * debugging map to make sure there are infinite loops
     */
    private Map<CGNode, Integer> iterations = new HashMap<>();

    /**
     * Construct a new inter-procedural analysis over the given call graph
     * 
     * @param cg
     *            call graph this analysis will be over
     * @param ptg
     *            points-to graph
     */
    public InterproceduralDataFlowManager(CallGraph cg, PointsToGraph ptg) {
        this.cg = cg;
        this.ptg = ptg;
        this.q = new WorkQueue<>();
    }

    /**
     * Run the inter-procedural analysis starting with the root node
     */
    public void runAnalysis() {
        Collection<CGNode> entryPoints = cg.getEntrypointNodes();
        
        // These are the class initializers
        q.addAll(entryPoints);
        // Also add the fake root method (which calls main)
        q.add(cg.getFakeRootNode());

        System.err.println("Initial Q: " + q);
        
        while (!q.isEmpty()) {
            CGNode current = q.poll();
            if (getOutputLevel() >=2) {
                System.err.println("QUEUE_POLL: " + PrettyPrinter.parseCGNode(current));
            }
            AnalysisRecord<F> results = getLatestResults(current);

            F input;
            if (current.equals(cg.getFakeRootNode()) || entryPoints.contains(current)) {
                // This is the root node get the root input
                input = getInputForRoot();
            } else {
                input = results.getInput();
            }

            process(current, input, results == null ? null : results.getOutput());
        }
    }

    /**
     * Increment the counter giving the number of times the given node has been
     * analyzed
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
        if (i >= 5) {
            throw new RuntimeException("Analyzed the same CG node " + i + " times for method: "
                                            + PrettyPrinter.parseCGNode(n));
        }
        return i;
    }

    /**
     * Get any nodes that have to be reanalyzed if the result of analyzing n
     * changes
     * 
     * @param n
     *            call graph node to get the dependencies for
     * @return set of nodes that need to be reanalyzed if the result of
     *         analyzing n changes
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
     * @param newInput
     *            initial data-flow fact
     * @return map from exit type (normal or exceptional) to data-flow fact
     * 
     */
    public Map<ExitType, F> getResults(CGNode caller, CGNode callee, F newInput) {
        if (getOutputLevel() >= 2) {
            System.err.println("GETTING:\n\t" + PrettyPrinter.parseCGNode(callee));
            System.err.println("\tFROM: " + PrettyPrinter.parseCGNode(caller));
            System.err.println("\tINPUT: " + newInput);
        }

        AnalysisRecord<F> previous = getLatestResults(callee);

        AnalysisRecord<F> results;
        if (previous != null && existingResultSuitable(newInput, previous)) {
            results = previous;
            printResults(callee, "PREVIOUS", results.output);
        } else {
            if (previous == null) {
                results = new AnalysisRecord<F>(newInput, getDefaultOutput(newInput), false);
            } else {
                // TODO use widen for back edges
                results = new AnalysisRecord<F>(join(newInput, previous.getInput()), previous.output, false);
            }

            if (currentlyProcessing.contains(callee)) {
                // Already processing the callee, this is a recursive call, just
                // use latest results and process this later with the new input
                q.add(callee);
                recordedResults.put(callee, results);
                printResults(callee, "LATEST", results.getOutput());
            } else {
                results = process(callee, results.input, previous == null ? null : previous.getOutput());
            }
        }

        if (!results.isSoundResult()) {
            // Ensure that the caller will be re-analyzed if the results for the
            // callee change
            addDependency(callee, caller);
        }

        // Record whether sound results will be returned to the caller
        soundResultsSoFar.put(caller, isSoundResultsSoFar(caller) && results.isSoundResult());
        return results.output;
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
            System.err.println("RESULTS:\n\t" + PrettyPrinter.parseCGNode(n));
            System.err.println("\t" + typeLabel + ": " + output);
        }
    }

    /**
     * Check if the results computed for the given node have only used sound
     * results so far. This could be false if there are recursive calls for
     * which we had to use unsound results.
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
     * Process the given node using the given input, record the results. If the
     * output changes then add dependencies to the work-queue.
     * 
     * @param n
     *            node to process
     * @param input
     *            input to the data-flow for the node
     * @param previousOutput
     *            previous analysis results, used to determine if the output
     *            changed
     * @return output after analyzing the given node with the given input
     */
    private AnalysisRecord<F> process(CGNode n, F input, Map<ExitType, F> previousOutput) {
        incrementCounter(n);
        currentlyProcessing.add(n);
        Map<ExitType, F> output = analyze(n, input);
        AnalysisRecord<F> results = new AnalysisRecord<F>(input, output, isSoundResultsSoFar(n));
        recordedResults.put(n, results);
        currentlyProcessing.remove(n);

        if (previousOutput == null || outputChanged(previousOutput, output)) {
            // The output changed add dependencies to the queue
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
    private final AnalysisRecord<F> getLatestResults(CGNode n) {
        return recordedResults.get(n);
    }

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
     * Get the default output data-flow facts (given an input fact), this is
     * used as the output for a recursive call before a fixed point is reached.
     * 
     * @param input
     *            input data-flow fact
     * @return output to be returned to callers when the callee is already in
     *         the middle of being analyzed
     */
    protected abstract Map<ExitType, F> getDefaultOutput(F input);

    /**
     * Get the input for the root node of the call graph. This is the initial
     * input to the inter-procedural analysis
     * 
     * @return initial data-flow fact
     */
    protected abstract F getInputForRoot();

    /**
     * Compute a single data-flow fact from two facts. This is used to compute
     * (intra-procedural) analysis input when there are for merges in the call
     * graph.
     * 
     * @param fact1
     *            first data-flow fact
     * @param fact2
     *            second data-flow fact
     * @return least upper bound of item1 and item2
     */
    protected abstract F join(F fact1, F fact2);

    /**
     * Check whether the output changed after analysis, and dependencies need to
     * be reanalyzed.
     * 
     * @param previousOutput
     *            previous output results
     * @param currentOutput
     *            current output results
     * @return true if the output results have changed (and dependencies have to
     *         be computed)
     */
    protected abstract boolean outputChanged(Map<ExitType, F> previousOutput,
                                    Map<ExitType, F> currentOutput);

    /**
     * Check whether existing output results are suitable, given a new input
     * 
     * @param newInput
     *            new input to the analysis
     * @param existingResults
     *            previous results
     * @return true if the existing results can be reused, false if they must be
     *         recomputed using the new input
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
         *            False if there are back edges and this result could be
         *            unsound
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
         * Check whether this record contains sound results. This will be false
         * if there were back edges in the call graph due to recursive calls,
         * and temporary unsound results were used to compute the output.
         * 
         * @return whether the results are sound
         */
        public boolean isSoundResult() {
            return isSoundResult;
        }
    }
}
