package analysis.pointer.statements;

import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public class ExceptionAssignmentStatement extends PointsToStatement {

    private final ReferenceVariable thrown;
    private final ReferenceVariable caught;
    private final Set<IClass> notType;

    /**
     * Statement for the assignment from a thrown exception to a caught exception or the summary node for the
     * exceptional exit to a method
     * 
     * @param thrown
     *            reference variable for the exception being thrown
     * @param caught
     *            reference variable for the caught exception (or summary for the method exit)
     * @param notType
     *            types that the exception being caught cannot have since those types must have been caught by previous
     *            catch blocks
     * @param m
     *            method the exception is thrown in
     */
    protected ExceptionAssignmentStatement(ReferenceVariable thrown, ReferenceVariable caught, Set<IClass> notType,
                                    IMethod m) {
        super(m);
        this.thrown = thrown;
        this.caught = caught;
        this.notType = notType;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, caught);
        PointsToGraphNode r;
        if (thrown.isSingleton()) {
            // This was a generated exception and the flag was set in StatementRegistrar so that only one reference
            // variable is created for each generated exception type
            r = new ReferenceVariableReplica(haf.initialContext(), thrown);
        } else {
            r = new ReferenceVariableReplica(context, thrown);
        }

        Set<InstanceKey> s = g.getPointsToSetFiltered(r, caught.getExpectedType(), notType);
        assert checkForNonEmpty(s, r, "EX ASSIGN filtered on " + caught.getExpectedType() + " not " + notType);

        return g.addEdges(l, s);
    }

    @Override
    public String toString() {
        return caught + " = " + thrown + "(" + PrettyPrinter.typeString(caught.getExpectedType()) + " NOT " + notType
                                        + ")";
    }
}
