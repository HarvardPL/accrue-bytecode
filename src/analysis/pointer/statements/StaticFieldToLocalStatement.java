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
 * Points-to statement for an assignment from a static field to a local variable, v = o.x
 */
public class StaticFieldToLocalStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final ReferenceVariable staticField;
    /**
     * assigned
     */
    private final ReferenceVariable local;

    /**
     * Statement for an assignment from a static field to a local, local = ClassName.staticField
     *
     * @param local points-to graph node for the assigned value
     * @param staticField points-to graph node for assignee
     * @param pp program point the statement was created at
     */
    protected StaticFieldToLocalStatement(ReferenceVariable local, ReferenceVariable staticField, ProgramPoint pp) {
        super(pp);
        assert staticField.isSingleton() : staticField + " is not static";
        assert !local.isSingleton() : local + " is static";
        assert !local.isFlowSensitive();

        this.staticField = staticField;
        this.local = local;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {

        PointsToGraphNode l = new ReferenceVariableReplica(context, local, haf);
        PointsToGraphNode r = new ReferenceVariableReplica(haf.initialContext(), staticField, haf);

        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        // don't need to use delta, as this just adds a subset edge
        return g.copyEdges(r, pre, l, post);
    }


    @Override
    public boolean mayKillNode() {
        return local.isFlowSensitive();
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        if (!local.isFlowSensitive()) {
            return null;
        }
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, new ReferenceVariableReplica(context,
                                                                                                      local,
                                                                                                      g.getHaf()));
    }

    @Override
    public String toString() {
        return local + " = " + staticField;
    }

    /**
     * Reference variable for the static field being accessed
     *
     * @return variable for the static field
     */
    public ReferenceVariable getStaticField() {
        return staticField;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        throw new UnsupportedOperationException("StaticFieldToLocal has no replacable uses");
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.emptyList();
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.singletonList(local);
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        assert !local.isFlowSensitive();
        // This will only update the
        // points to set of local, which is flow insensitive.
        // However, the static field may be flow sensitive.
        return true;
    }

}
