package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to graph statement for a "null alloc" statement, e.g. o = null;
 */
public class NullToLocalStatement extends PointsToStatement {

    /**
     * Points-to graph node for the assignee of null
     */
    private final ReferenceVariable result;

    /**
     * Points-to graph statement for an allocation resulting from a new instruction, e.g. o = null
     *
     * @param result Points-to graph node for the assignee of the new
     * @param pp program point of this statement
     */
    protected NullToLocalStatement(ReferenceVariable result, ProgramPoint pp) {
        super(pp);
        this.result = result;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        InstanceKeyRecency nullIkr = g.nullInstanceKey();

        ReferenceVariableReplica r = new ReferenceVariableReplica(context, result, haf);
        ProgramPointReplica ppr = ProgramPointReplica.create(context, this.programPoint());

        return g.addEdge(r, nullIkr, ppr.post());
    }


    @Override
    public boolean mayKillNode(Context context, PointsToGraph g) {
        return result.isFlowSensitive();
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        if (!result.isFlowSensitive()) {
            return null;
        }
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, new ReferenceVariableReplica(context,
                                                                                                      result,
                                                                                                      g.getHaf()));
    }

    @Override
    public String toString() {
        return result + " = null";
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        throw new UnsupportedOperationException("NewStatement has no uses that can be reassigned");
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.emptyList();
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.singletonList(result);
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        // NOTE: if we end up not being flow sensitive for some allocations,
        // we could improve this.
        return true;
    }

}
