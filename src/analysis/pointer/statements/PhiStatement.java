package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to graph statement for a phi, representing choice at control flow merges. v = phi(x1, x2, ...)
 */
public class PhiStatement extends PointsToStatement {

    /**
     * Value assigned into
     */
    private final ReferenceVariable assignee;
    /**
     * Arguments into the phi
     */
    private final List<ReferenceVariable> uses;

    /**
     * Points-to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     * 
     * @param v value assigned into
     * @param xs list of arguments to the phi, v is a choice amongst these
     * @param pp program point of the phi instruction
     */
    protected PhiStatement(ReferenceVariable v, List<ReferenceVariable> xs, ProgramPoint pp) {
        super(pp);
        assert !xs.isEmpty();
        assignee = v;
        uses = xs;
        assert allFlowInsensitive(xs);
        assert !assignee.isFlowSensitive();
    }

    private static boolean allFlowInsensitive(List<ReferenceVariable> vs) {
        for (ReferenceVariable v : vs) {
            if (v.isFlowSensitive()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, assignee, haf);
        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        GraphDelta changed = new GraphDelta(g);
        // For every possible branch add edges into assignee
        for (ReferenceVariable use : uses) {
            PointsToGraphNode n = new ReferenceVariableReplica(context, use, haf);
            // no need to use delta, as this just adds subset relations.
            GraphDelta d1 = g.copyEdges(n, pre, a, post);

            changed = changed.combine(d1);
        }
        return changed;
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, assignee.isFlowSensitive()
                ? new ReferenceVariableReplica(context, assignee, g.getHaf()) : null);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(assignee + " = ");
        s.append("phi(");
        for (int i = 0; i < uses.size() - 1; i++) {
            s.append(uses.get(i) + ", ");
        }
        s.append(uses.get(uses.size() - 1) + ")");
        return s.toString();
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber < uses.size() && useNumber >= 0;
        uses.set(useNumber, newVariable);
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return uses;
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.singletonList(assignee);
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        // all the locals are flow insensitive.
        assert allFlowInsensitive(uses);
        assert !assignee.isFlowSensitive();
        return false;
    }

}
