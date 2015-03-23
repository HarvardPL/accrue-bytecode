package results;

import java.util.Set;

import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.interval.IntervalResults;
import analysis.dataflow.interprocedural.nonnull.NonNullResults;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;

public class CollectResultsPass {
    private final NonNullResults nonnull;
    private final IntervalResults interval;
    private final PointsToGraph g;

    public CollectResultsPass(NonNullResults nonnull, IntervalResults interval, PointsToGraph g) {
        this.nonnull = nonnull;
        this.interval = interval;
        this.g = g;
    }

    public CollectedResults run() {
        Set<IMethod> allMethods = g.getRegistrar().getRegisteredMethods();
        CollectedResults results = new CollectedResults();
        // Loop through all methods and process each one
        for (IMethod m : allMethods) {
            Set<Context> contexts = g.getContexts(m);
            if (contexts.isEmpty()) {
                // This was registered, but does not have any contexts, which means it does not appear in the call graph
                continue;
            }

            IR ir = AnalysisUtil.getIR(m);
            if (ir == null) {
                assert m.isNative();
                // No code to analyze for native methods without signatures skip it
                continue;
            }

            new CollectIntervalResultsDataFlow(interval, g, results, contexts).run(m);
            new CollectNonNullClassCastResults(m, nonnull, g, results, contexts, g.getRegistrar().getRvCache()).run(m);

        } // end of method loop

        return results;
    }
}
