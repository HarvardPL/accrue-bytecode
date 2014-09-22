package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
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
     * @param v
     *            value assigned into
     * @param xs
     *            list of arguments to the phi, v is a choice amongst these
     * @param m
     *            method containing the phi instruction
     */
    protected PhiStatement(ReferenceVariable v, List<ReferenceVariable> xs, ProgramPoint pp) {
        super(pp);
        assert !xs.isEmpty();
        assignee = v;
        uses = xs;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode a = new ReferenceVariableReplica(context, assignee);
        InterProgramPointReplica ippr = InterProgramPointReplica.create(context, this.programPoint().post());

        GraphDelta changed = new GraphDelta(g);
        // For every possible branch add edges into assignee
        for (ReferenceVariable use : uses) {
            PointsToGraphNode n = new ReferenceVariableReplica(context, use);
            // no need to use delta, as this just adds subset relations.
            GraphDelta d1 = g.copyEdges(n, a, ippr);

            changed = changed.combine(d1);
        }
        return changed;
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
    public ReferenceVariable getDef() {
        return assignee;
    }

    @Override
    public Collection<?> getReadDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        List<ReferenceVariableReplica> l = new ArrayList<>(uses.size());
        for (ReferenceVariable use : uses) {
            ReferenceVariableReplica n =
                    new ReferenceVariableReplica(ctxt, use);
            l.add(n);
        }
        return l;
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        return Collections.singleton(new ReferenceVariableReplica(ctxt,
                                                                  assignee));
    }

}
