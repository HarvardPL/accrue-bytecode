package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to statement for a local assignment, left = right
 */
public class LocalToLocalStatement extends PointsToStatement {

    /**
     * assignee
     */
    private final ReferenceVariable left;
    /**
     * assigned
     */
    private ReferenceVariable right;

    private final boolean filter;

    /**
     * Statement for a local assignment, left = right
     *
     * @param left
     *            points-to graph node for assignee
     * @param right
     *            points-to graph node for the assigned value
     * @param m
     *            method the assignment is from
     */
    protected LocalToLocalStatement(ReferenceVariable left, ReferenceVariable right, IMethod m,
                                    boolean filterBasedOnType) {
        super(m);
        assert !left.isSingleton() : left + " is static";
        this.left = left;
        this.right = right;
        filter = filterBasedOnType;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, left, haf);
        PointsToGraphNode r = new ReferenceVariableReplica(context, right, haf);
        // don't need to use delta, as this just adds a subset edge
        if (filter) {
            TypeFilter typeFilter = TypeFilter.create(left.getExpectedType());
            return g.copyFilteredEdges(r, typeFilter, l);
        }
        return g.copyEdges(r, l);
    }

    @Override
    public String toString() {
        return left + " = (" + PrettyPrinter.typeString(left.getExpectedType())
                + ") " + right;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        assert useNumber == 0;
        right = newVariable;
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.singletonList(right);
    }

    @Override
    public ReferenceVariable getDef() {
        return left;
    }
}
