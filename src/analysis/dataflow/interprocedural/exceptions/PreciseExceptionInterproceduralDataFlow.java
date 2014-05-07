package analysis.dataflow.interprocedural.exceptions;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.InterproceduralDataFlow;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
import analysis.dataflow.interprocedural.reachability.ReachabilityResults;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.TypeReference;

/**
 * Inter-procedural data-flow coordinator for a precise exceptions analysis.
 * This analysis more precisely determines which exceptions can actually be
 * thrown by a basic block (possibly using a previously run non-null analysis).
 */
public class PreciseExceptionInterproceduralDataFlow extends InterproceduralDataFlow<PreciseExceptionAbsVal> {

    /**
     * Results of this analysis
     */
    private final PreciseExceptionResults preciseEx;
    /**
     * Results of a previously run non-null analysis
     */
    private final NonNullResults nonNull;
    /**
     * WALA analysis utility classes
     */
    private final WalaAnalysisUtil util;
    /**
     * Name of this analysis
     */
    private static final String ANALYSIS_NAME = "Precise Exceptions Analysis";

    /**
     * Create a new inter-procedural precise exception analysis
     * 
     * @param ptg
     *            previously computed points-to graph
     * @param nonNull
     *            results of previous non-null analysis
     * @param reachable
     *            results of a reachability analysis
     * @param util
     *            WALA utilities
     */
    public PreciseExceptionInterproceduralDataFlow(PointsToGraph ptg, NonNullResults nonNull,
                                    ReachabilityResults reachable, WalaAnalysisUtil util) {
        super(ptg, reachable);
        preciseEx = new PreciseExceptionResults(util.getClassHierarchy());
        this.nonNull = nonNull;
        this.util = util;
    }

    @Override
    protected String getAnalysisName() {
        return ANALYSIS_NAME;
    }

    @Override
    protected Map<ExitType, PreciseExceptionAbsVal> analyze(CGNode n, PreciseExceptionAbsVal input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING:\n\t" + PrettyPrinter.parseCGNode(n) + "\n\tINPUT: " + input);
        }
        PreciseExceptionDataFlow df = new PreciseExceptionDataFlow(nonNull, n, this, util.getClassHierarchy());
        df.setOutputLevel(getOutputLevel());
        return df.dataflow(input);
    }

    @Override
    protected Map<ExitType, PreciseExceptionAbsVal> analyzeNative(CGNode n, PreciseExceptionAbsVal input) {
        if (getOutputLevel() >= 2) {
            System.err.println("\tANALYZING NATIVE:\n\t" + PrettyPrinter.parseCGNode(n) + "\n\tINPUT: " + input);
        }
        Map<ExitType, PreciseExceptionAbsVal> results = new HashMap<>();
        Set<TypeReference> types;
        try {
            types = new LinkedHashSet<TypeReference>(Arrays.asList(n.getMethod().getDeclaredExceptions()));
        } catch (UnsupportedOperationException | InvalidClassFileException e) {
            throw new RuntimeException("Trouble getting exceptions from method "
                                            + PrettyPrinter.parseMethod(n.getMethod()));
        }
        types.add(TypeReference.JavaLangRuntimeException);
        results.put(ExitType.EXCEPTIONAL, new PreciseExceptionAbsVal(types));
        results.put(ExitType.NORMAL, PreciseExceptionAbsVal.EMPTY);
        return results;
    }

    @Override
    protected Map<ExitType, PreciseExceptionAbsVal> getDefaultOutput(PreciseExceptionAbsVal input) {
        Map<ExitType, PreciseExceptionAbsVal> res = new HashMap<ExitType, PreciseExceptionAbsVal>();
        res.put(ExitType.NORMAL, PreciseExceptionAbsVal.EMPTY);
        res.put(ExitType.EXCEPTIONAL, PreciseExceptionAbsVal.EMPTY);
        return res;
    }

    @Override
    protected PreciseExceptionAbsVal getInputForEntryPoint() {
        return PreciseExceptionAbsVal.EMPTY;
    }

    @Override
    protected boolean outputChanged(Map<ExitType, PreciseExceptionAbsVal> previousOutput,
                                    Map<ExitType, PreciseExceptionAbsVal> currentOutput) {
        assert previousOutput != null;
        assert currentOutput != null;
        return previousOutput.equals(currentOutput);
    }

    @Override
    protected boolean existingResultSuitable(PreciseExceptionAbsVal newInput,
                                    AnalysisRecord<PreciseExceptionAbsVal> existingResults) {
        return existingResults != null && newInput.leq(existingResults.getInput());
    }

    /**
     * Get the results for the analysis (these may be unsound during the
     * analysis)
     * 
     * @return the set of exceptions on each edge in each call graph node's
     *         control flow graph
     */
    public PreciseExceptionResults getAnalysisResults() {
        return preciseEx;
    }
}
