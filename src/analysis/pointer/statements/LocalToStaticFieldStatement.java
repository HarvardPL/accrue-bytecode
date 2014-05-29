package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to statement for an assignment from a local into a static field
 */
public class LocalToStaticFieldStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final ReferenceVariable local;
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
     */
    protected LocalToStaticFieldStatement(ReferenceVariable staticField, ReferenceVariable local, IMethod m) {
        super(ir, i);
        assert !local.isSingleton() : local + " is static";
        assert staticField.isSingleton() : staticField + " is not static";
        this.local = local;
        this.staticField = staticField;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(haf.initialContext(), staticField);
        PointsToGraphNode r = new ReferenceVariableReplica(context, local);

        if (DEBUG && g.getPointsToSet(r).isEmpty()) {
            System.err.println("LOCAL: " + local + " for "
                                            + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()));
        }
        return g.addEdges(l, g.getPointsToSet(r));
    }

    @Override
    public String toString() {
        return staticField + " = " + local;
    }
}
