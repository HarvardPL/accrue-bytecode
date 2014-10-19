package analysis.pointer.statements;

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
     * @param staticField
     *            points-to graph node for the assigned value
     * @param local
     *            points-to graph node for assignee
     * @param m
     *            method containing the statement
     */
    protected LocalToStaticFieldStatement(ReferenceVariable staticField,
 ReferenceVariable local, ProgramPoint pp) {
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
        return g.copyEdges(r, pre, l, post, originator);
    }

    @Override
    public PointsToGraphNode killed(Context context, PointsToGraph g) {
        return staticField.isFlowSensitive() ? new ReferenceVariableReplica(context, staticField, g.getHaf()) : null;
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
    public ReferenceVariable getDef() {
        // The static field is not a local
        return null;
    }

    @Override
    public Collection<?> getReadDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        ReferenceVariableReplica r = new ReferenceVariableReplica(ctxt, local, haf);
        return Collections.singleton(r);
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        return Collections.singleton(new ReferenceVariableReplica(haf.initialContext(), staticField, haf));
    }

    @Override
    public boolean mayChangeFlowSensPointsToGraph() {
        return staticField.isFlowSensitive();
    }

}
