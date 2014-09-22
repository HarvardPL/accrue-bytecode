package analysis.pointer.statements;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to statement for a "return" instruction
 */
public class ReturnStatement extends PointsToStatement {

    /**
     * Node for return result
     */
    private ReferenceVariable result;
    /**
     * Node summarizing all return values for the method
     */
    private final ReferenceVariable returnSummary;

    /**
     * Create a points-to statement for a return instruction
     *
     * @param result
     *            Node for return result
     * @param returnSummary
     *            Node summarizing all return values for the method
     * @param m
     *            method the points-to statement came from
     */
    protected ReturnStatement(ReferenceVariable result,
 ReferenceVariable returnSummary, ProgramPoint pp) {
        super(pp);
        this.result = result;
        this.returnSummary = returnSummary;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        ReferenceVariableReplica returnRes =
                new ReferenceVariableReplica(context, result);
        ReferenceVariableReplica summaryRes =
                new ReferenceVariableReplica(context, returnSummary);

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        // don't need to use delta, as this just adds a subset edge
        return g.copyEdges(returnRes, pre, summaryRes, post);

    }

    @Override
    public String toString() {
        return "return " + result;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        result = newVariable;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(result);
    }

    @Override
    public ReferenceVariable getDef() {
        return null;
    }

    @Override
    public Collection<?> getReadDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        ReferenceVariableReplica r = new ReferenceVariableReplica(ctxt, result);
        return Collections.singleton(r);
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        return Collections.singleton(new ReferenceVariableReplica(ctxt,
                                                                  returnSummary));
    }

}
