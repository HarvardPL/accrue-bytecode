package analysis.pointer.statements;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
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
     * @param local
     *            points-to graph node for the assigned value
     * @param staticField
     *            points-to graph node for assignee
     * @param m
     */
    protected StaticFieldToLocalStatement(ReferenceVariable local, ReferenceVariable staticField, IMethod m) {
        super(m);
        assert staticField.isSingleton() : staticField + " is not static";
        assert !local.isSingleton() : local + " is static";

        this.staticField = staticField;
        this.local = local;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                                    StatementRegistrar registrar) {

        PointsToGraphNode l = new ReferenceVariableReplica(context, local);
        PointsToGraphNode r = new ReferenceVariableReplica(haf.initialContext(), staticField);

        return g.copyEdgesWithDelta(r, l, delta);
    }

    @Override
    public String toString() {
        return local + " = " + staticField;
    }
}
