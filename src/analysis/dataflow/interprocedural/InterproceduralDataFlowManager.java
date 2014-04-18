package analysis.dataflow.interprocedural;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import util.WorkQueue;
import util.print.PrettyPrinter;
import analysis.dataflow.ExitType;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;

/**
 * Manages the running of an inter-procedural data-flow analysis
 */
public abstract class InterproceduralDataFlowManager<FlowItem> {
    
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
    protected final Map<CGNode, AnalysisResults<FlowItem>> recordedResults = new LinkedHashMap<>();
    /**
     * Nodes that are currently being processed, used to detect recursive calls
     */
    private final Set<CGNode> currentlyProcessing = new HashSet<>();
    /**
     * Record of which CG nodes need to be re-analyzed if a given node changes
     */
    private Map<CGNode, Set<CGNode>> dependencyMap;
    
    public InterproceduralDataFlowManager(CallGraph cg, PointsToGraph ptg) {
        this.cg = cg;
        this.ptg = ptg;
        // TODO does not allow duplicates, do I need something that does?
        this.q = new WorkQueue<>();
    }
    
    public void runAnalysis() {
        // Get the entry point add it to the queue
        q.add(cg.getFakeRootNode());
        
        // debugging map to make sure there are infinite loops
        Map<CGNode, Integer> iterations = new HashMap<>();
        
        while (!q.isEmpty()) {
            CGNode current = q.poll();

            FlowItem input;
            if (current.equals(cg.getFakeRootNode())) {
                // This is the root node get the root input
                input = getInputForRoot();
            } else {
                // TODO why is this never null?
                input = recordedResults.get(current).getInput();
            }
            
            AnalysisResults<FlowItem> results = getLatestResults(current);
            if (!existingResultSuitable(input, results)) {
                // trigger processing of this node with the given input
                Map<ExitType, FlowItem> output = analyze(current, input);

                if (outputChanged(results.getOutput(), output)) {
                    // the output changed so add any dependencies to the queue
                    q.addAll(getDependencies(current));
                    results = new AnalysisResults<>(input, output, true);
                }
            }
            
            Integer iterationsForCurrent = iterations.get(current);
            if (iterationsForCurrent == null) {
                iterationsForCurrent = 0;
            }
            iterationsForCurrent++;
            if (iterationsForCurrent >= 100) {
                throw new RuntimeException("Analyzed the same CG node 100 times for method: "
                                                + PrettyPrinter.parseMethod(current.getIR().getMethod().getReference())
                                                + " in " + current.getContext());
            }
            iterations.put(current, iterationsForCurrent);
        }
    }
    
    /**
     * Get any nodes that have to be reanalyzed if the result of analyzing n changes
     * 
     * @param n call graph node to get the dependencies for
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

    public CallGraph getCallGraph() {
        return cg;
    }
    
    public PointsToGraph getPointsToGraph() {
        return ptg;
    }
    
    public WorkQueue<CGNode> getWorkQueue() {
        return q;
    }
    
    /**
     * Get the results of analyzing the callee
     * 
     * @param caller
     *            caller's call graph node
     * @param callee
     *            calee's call graph node
     * @param initial
     *            initial data-flow facts
     * @param isInputSound
     *            true if the input was soundly computed
     * @return
     */
    public Map<ExitType, FlowItem> getResults(CGNode caller, CGNode callee, FlowItem input) {
        if (currentlyProcessing.contains(callee)) {
            // This is a recursive call get the most recent results and add it
            // to the queue for later processing
            q.add(callee);
            
            AnalysisResults<FlowItem> latest = getLatestResults(callee);
            AnalysisResults<FlowItem> newResults = new AnalysisResults<>(join(input, latest.getInput()), latest.getOutput(), true);
            recordedResults.put(callee, newResults);
                                            
            // Make sure the caller gets recomputed if the callee changes
            addDependency(callee, caller);
            return newResults.getOutput();
        }
        
        Map<ExitType, FlowItem> output = analyze(callee, input);
        AnalysisResults<FlowItem> results = new AnalysisResults<>(input, output, false);
        recordedResults.put(callee, results);
        return output;
    }
    
    private final AnalysisResults<FlowItem> getLatestResults(CGNode n) {
        return recordedResults.get(n);
    }

    protected abstract FlowItem join(FlowItem item1, FlowItem item2);
    protected abstract Map<ExitType, FlowItem> analyze(CGNode n, FlowItem input);
    protected abstract Map<ExitType, FlowItem> getDefaultOutput();
    protected abstract FlowItem getInputForRoot();
    protected abstract boolean outputChanged(Map<ExitType, FlowItem> previousOutput, Map<ExitType, FlowItem> currentOutput);
    protected abstract boolean existingResultSuitable(FlowItem input, AnalysisResults<FlowItem> rec);
    
    protected static class AnalysisResults<FlowItem> {

        private final Map<ExitType, FlowItem> output;
        private final FlowItem input;
        /**
         * True if there are back edges and this result could be unsound
         * TODO unsoundResult never used
         */
        private final boolean unsoundResult;
        
        /**
         * 
         * 
         * @param input
         * @param output
         * @param unsoundResult
         *            True if there are back edges and this result could be
         *            unsound
         */
        public AnalysisResults(FlowItem input, Map<ExitType, FlowItem> output, boolean unsoundResult) {
            this.input = input;
            this.output = output;
            this.unsoundResult = unsoundResult;
        }
        
        public FlowItem getInput() {
            return input;
        }
        
        public Map<ExitType, FlowItem> getOutput() {
            return output;
        }
        
        public boolean isUnsoundResult() {
            return unsoundResult;
        }
        
    }
}
