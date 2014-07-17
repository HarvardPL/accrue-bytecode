package analysis.pointer.engine;

import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;

public class PointsToAnalysisMulitThreaded extends PointsToAnalysis {

    public PointsToAnalysisMulitThreaded(HeapAbstractionFactory haf, WalaAnalysisUtil util) {
        super(haf, util);
    }

    @Override
    public PointsToGraph solve(StatementRegistrar registrar) {
        // TODO Auto-generated method stub
        return null;
    }

}
