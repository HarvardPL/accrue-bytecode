package analysis.dataflow.interprocedural.reachability;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Analysis that determines which basic blocks are reachable on which edges
 */
public class ReachabilityInterProceduralDataFlow extends InterproceduralDataFlow<ReachabilityAbsVal> {

    private final ReachabilityResults results = new ReachabilityResults();

    public ReachabilityInterProceduralDataFlow(PointsToGraph ptg) {
        super(ptg, ReachabilityResults.ALWAYS_REACHABLE);
    }

    @Override
    protected String getAnalysisName() {
        return "Reachability data-flow";
    }

    @Override
    protected Map<ExitType, ReachabilityAbsVal> analyze(CGNode n, ReachabilityAbsVal input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.parseCGNode(n) + "\n\tINPUT: " + input);
        }
        ReachabilityDataFlow df = new ReachabilityDataFlow(n, this);
        df.setOutputLevel(getOutputLevel());
        return df.dataflow(input);
    }

    @Override
    protected Map<ExitType, ReachabilityAbsVal> analyzeNative(CGNode n, ReachabilityAbsVal input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING NATIVE:\n\t" + PrettyPrinter.parseCGNode(n) + "\n\tINPUT: " + input);
        }

        // Assume all native methods can both terminate normally and throw an
        // exception
        Map<ExitType, ReachabilityAbsVal> results = new HashMap<>();
        results.put(ExitType.NORMAL, ReachabilityAbsVal.REACHABLE);
        results.put(ExitType.EXCEPTIONAL, ReachabilityAbsVal.REACHABLE);
        return results;
    }

    @Override
    protected Map<ExitType, ReachabilityAbsVal> getDefaultOutput(ReachabilityAbsVal input) {
        // Assume the output is the same as the input
        Map<ExitType, ReachabilityAbsVal> results = new HashMap<>();
        results.put(ExitType.NORMAL, input);
        results.put(ExitType.EXCEPTIONAL, input);
        return results;
    }

    @Override
    protected ReachabilityAbsVal getInputForEntryPoint() {
        // The root is always reachable!
        return ReachabilityAbsVal.REACHABLE;
    }

    @Override
    protected boolean outputChanged(Map<ExitType, ReachabilityAbsVal> previousOutput,
                                    Map<ExitType, ReachabilityAbsVal> currentOutput) {
        assert previousOutput != null;
        assert currentOutput != null;
        return previousOutput.equals(currentOutput);
    }

    @Override
    protected boolean existingResultSuitable(ReachabilityAbsVal newInput,
                                    AnalysisRecord<ReachabilityAbsVal> existingResults) {
        // TODO is leq ok here if we want precise results
        return existingResults != null && newInput.equals(existingResults.getInput());
    }

    public ReachabilityResults getReachabilityResults() {
        return results;
    }
}
