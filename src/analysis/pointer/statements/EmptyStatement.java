package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;

import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to graph statement for a "new" statement, e.g. Object o = new Object()
 */
public class EmptyStatement extends PointsToStatement {

    protected EmptyStatement(ProgramPoint pp) {
        super(pp);
    }

    @Override
    public String toString() {
        return "empty stmt";
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        throw new UnsupportedOperationException("EmptyStatement has no uses that can be reassigned");
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.emptyList();
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.emptyList();
    }

    @Override
    public boolean mayChangeFlowSensPointsToGraph() {
        return true;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        return new GraphDelta(g);
    }

}
