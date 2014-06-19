package analysis.dataflow.interprocedural.reachability;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableCache;

import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Analysis that determines which basic blocks are reachable on which edges
 */
public class ReachabilityInterProceduralDataFlow extends InterproceduralDataFlow<ReachabilityAbsVal> {

    private final ReachabilityResults results = new ReachabilityResults();
    private final PreciseExceptionResults preciseEx;

    /**
     * Create an inter-procedural analysis to compute which basic blocks are reachable
     * 
     * @param ptg
     *            points to graph
     * @param rvCache
     *            reference variable cache
     * @param preciseEx
     *            results of an inter-procedural precise exceptions analysis (null if no results are available)
     */
    public ReachabilityInterProceduralDataFlow(PointsToGraph ptg, ReferenceVariableCache rvCache,
                                    PreciseExceptionResults preciseEx) {
        super(ptg, ReachabilityResults.ALWAYS_REACHABLE, rvCache);
        this.preciseEx = preciseEx;
    }

    @Override
    protected String getAnalysisName() {
        return "Reachability data-flow";
    }

    @Override
    protected Map<ExitType, ReachabilityAbsVal> analyze(CGNode n, ReachabilityAbsVal input) {
        if (getOutputLevel() >= 2) {
            System.err.println("ANALYZING:\t" + PrettyPrinter.cgNodeString(n) + "\tINPUT: " + input);
        }
        ReachabilityDataFlow df = new ReachabilityDataFlow(n, this);
        df.setOutputLevel(getOutputLevel());
        return df.dataflow(input);
    }

    @Override
    protected Map<ExitType, ReachabilityAbsVal> analyzeMissingCode(CGNode n, ReachabilityAbsVal input) {
        if (getOutputLevel() >= 2) {
            System.err.println("ANALYZING NATIVE:\n\t" + PrettyPrinter.cgNodeString(n) + "\n\tINPUT: " + input);
        }

        // If the method is reachable then the exits are reachable
        // If the method is reachable then the exits are unreachable
        Map<ExitType, ReachabilityAbsVal> results = new HashMap<>();
        results.put(ExitType.NORMAL, input);
        results.put(ExitType.EXCEPTIONAL, input);
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
        return !previousOutput.equals(currentOutput);
    }

    @Override
    protected boolean existingResultSuitable(ReachabilityAbsVal newInput,
                                    AnalysisRecord<ReachabilityAbsVal> existingResults) {
        // TODO is leq ok here if we want precise results
        return existingResults != null && newInput.equals(existingResults.getInput());
    }

    @Override
    public ReachabilityResults getAnalysisResults() {
        return results;
    }

    /**
     * Results of a precise exceptions analysis
     * 
     * @return results of a precise exceptions analysis or null if there are no such results
     */
    public PreciseExceptionResults getPreciseEx() {
        return preciseEx;
    }
}
