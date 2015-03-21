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
 * Points-to statement for an assignment from a local into a static field
 */
public class LocalToStaticFieldStatement extends PointsToStatement {

    /**
     * assignee
     */
    private ReferenceVariable local;
    /**
     * assigned
     */
    private final ReferenceVariable staticField;

    /**
     * Statement for an assignment from a local into a static field, ClassName.staticField = local
     *
     * @param staticField points-to graph node for the assigned value
     * @param local points-to graph node for assignee
     * @param pp program point for the statement
     */
    protected LocalToStaticFieldStatement(ReferenceVariable staticField, ReferenceVariable local, ProgramPoint pp) {
        super(pp);
        assert !local.isSingleton() : local + " is static";
        assert staticField.isSingleton() : staticField + " is not static";
        assert !local.isFlowSensitive();

        this.local = local;
        this.staticField = staticField;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode l = new ReferenceVariableReplica(haf.initialContext(), staticField, haf);
        PointsToGraphNode r = new ReferenceVariableReplica(context, local, haf);
        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());

        // don't need to use delta, as this just adds a subset edge
        return g.copyEdges(r, pre, l, post);
    }


    @Override
    public boolean mayKillNode(Context context, PointsToGraph g) {
        return staticField.isFlowSensitive();
    }

    @Override
    public boolean isImportant() {
        return staticField.isFlowSensitive();
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        if (!staticField.isFlowSensitive()) {
            return null;
        }
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, new ReferenceVariableReplica(context,
                                                                                                      staticField,
                                                                                                      g.getHaf()));
    }

    @Override
    public String toString() {
        return staticField + " = " + local;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        local = newVariable;

    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(local);
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.emptyList();
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        return staticField.isFlowSensitive() || local.hasLocalScope();
    }

}
