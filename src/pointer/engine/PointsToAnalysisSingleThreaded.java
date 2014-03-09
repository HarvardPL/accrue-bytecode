package pointer.engine;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.cha.IClassHierarchy;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.PointsToGraph;
import pointer.statements.PointsToStatement;
import pointer.statements.StatementRegistrar;

public class PointsToAnalysisSingleThreaded extends PointsToAnalysis {

    public PointsToAnalysisSingleThreaded(HeapAbstractionFactory haf, IClassHierarchy cha) {
        super(haf, cha);
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
        PointsToGraph g = new PointsToGraph(cha, registrar, haf);

        boolean changed = true;
        while (changed) {
            for (PointsToStatement s : registrar.getAllStatements()) {
                for (Context c : g.getContexts(s.getCode().getMethod().getReference())) {
                    changed |= s.process(c, haf, g, registrar);
                }
            }
        }
        return g;
    }
}
