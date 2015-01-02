package analysis.pointer.statements;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

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
     * @param m method containing the statement
     */
    protected LocalToStaticFieldStatement(ReferenceVariable staticField, ReferenceVariable local, IMethod m) {
        super(m);
        assert !local.isSingleton() : local + " is static";
        assert staticField.isSingleton() : staticField + " is not static";
        this.local = local;
        this.staticField = staticField;
    }

    @Override
    public <IK extends InstanceKey, C extends Context> GraphDelta process(C context, HeapAbstractionFactory<IK, C> haf,
                                                                          PointsToGraph g, GraphDelta delta,
                                                                          StatementRegistrar registrar,
                                                                          StmtAndContext originator) {
        PointsToGraphNode l = new ReferenceVariableReplica(haf.initialContext(), staticField, haf);
        PointsToGraphNode r = new ReferenceVariableReplica(context, local, haf);
        // don't need to use delta, as this just adds a subset edge
        return g.copyEdges(r, l);
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
    public <IK extends InstanceKey, C extends Context> Collection<?> getReadDependencies(C ctxt,
                                                                                         HeapAbstractionFactory<IK, C> haf) {
        ReferenceVariableReplica r = new ReferenceVariableReplica(ctxt, local, haf);
        return Collections.singleton(r);
    }

    @Override
    public <IK extends InstanceKey, C extends Context> Collection<?> getWriteDependencies(C ctxt,
                                                                                          HeapAbstractionFactory<IK, C> haf) {
        return Collections.singleton(new ReferenceVariableReplica(haf.initialContext(), staticField, haf));
    }
}
