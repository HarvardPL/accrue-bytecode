package analysis.pointer.statements;

import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class ExceptionAssignmentStatement extends PointsToStatement {

    private final ReferenceVariable thrown;
    private final ReferenceVariable caught;
    private final TypeFilter filter;

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
    protected ExceptionAssignmentStatement(ReferenceVariable thrown,
            ReferenceVariable caught, Set<IClass> notType, IMethod m) {
        super(m);
        this.thrown = thrown;
        this.caught = caught;
        filter = TypeFilter.create(caught.getExpectedType(), notType);

    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, caught);
        PointsToGraphNode r;
        if (thrown.isSingleton()) {
            // This was a generated exception and the flag was set in StatementRegistrar so that only one reference
            // variable is created for each generated exception type
            r = new ReferenceVariableReplica(haf.initialContext(), thrown);
        }
        else {
            r = new ReferenceVariableReplica(context, thrown);
        }

        // don't need to use delta, as this just adds a subset edge
        return g.copyFilteredEdges(r, filter, l);

    }

    @Override
    public String toString() {
        return caught + " = " + thrown + " ("
                + PrettyPrinter.typeString(caught.getExpectedType()) + " NOT "
                + filter.notTypes + ")";
    }
}
