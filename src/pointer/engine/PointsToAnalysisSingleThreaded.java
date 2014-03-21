package pointer.engine;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.PointsToGraph;
import pointer.statements.PointsToStatement;
import pointer.statements.StatementRegistrar;
import analysis.AnalysisUtil;

import com.ibm.wala.ipa.callgraph.Context;

public class PointsToAnalysisSingleThreaded extends PointsToAnalysis {

    public PointsToAnalysisSingleThreaded(HeapAbstractionFactory haf, AnalysisUtil util) {
        super(haf, util);
    }

    @Override
    public PointsToGraph solve(StatementRegistrar registrar) {
        return solveSimple(registrar);
    }

    /**
     * Slow naive implementation suitable for testing
     * 
     * @param registrar
     *            Point-to statement registrar
     * 
     * @return Points-to graph
     */
    @Deprecated
    public PointsToGraph solveSimple(StatementRegistrar registrar) {
        PointsToGraph g = new PointsToGraph(util, registrar, haf);

        boolean changed = true;
        int count = 0;
        while (changed) {
            changed = false;
//            System.err.println("\nITERATION: " + count);
            for (PointsToStatement s : registrar.getAllStatements()) {
                for (Context c : g.getContexts(s.getCode().getMethod())) {
//                    System.err.println(s + " in " + c.toString());
                    changed |= s.process(c, haf, g, registrar);
//                    System.err.println("changed = " + changed);
                }
            }
            count++;
            if (count % 10 == 0) {
                System.err.println(count + " iterations ");
                break;
            }
        }
        return g;
    }
}
