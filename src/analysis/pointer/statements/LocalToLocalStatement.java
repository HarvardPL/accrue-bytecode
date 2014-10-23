package analysis.pointer.statements;

import java.util.Collections;
import java.util.List;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.graph.TypeFilter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

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
     * Is right from a method summary?
     */
    private final boolean isFromMethodSummaryVariable;

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
    protected LocalToLocalStatement(ReferenceVariable left,
 ReferenceVariable right, ProgramPoint pp,
                                    boolean filterBasedOnType,
            boolean isFromMethodSummaryVariable) {
        super(pp);
        assert !left.isSingleton() : left + " is static";
        assert !right.isFlowSensitive();

        this.left = left;
        this.right = right;
        filter = filterBasedOnType;
        this.isFromMethodSummaryVariable = isFromMethodSummaryVariable;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        PointsToGraphNode l = new ReferenceVariableReplica(context, left, haf);
        PointsToGraphNode r = new ReferenceVariableReplica(context, right, haf);
        InterProgramPointReplica pre = InterProgramPointReplica.create(context, this.programPoint().pre());
        InterProgramPointReplica post = InterProgramPointReplica.create(context, this.programPoint().post());
        // don't need to use delta, as this just adds a subset edge
        if (filter) {
            TypeFilter typeFilter = TypeFilter.create(left.getExpectedType());
            return g.copyFilteredEdges(r, typeFilter, l, originator);
        }
        return g.copyEdges(r, pre, l, post, originator);
    }

    @Override
    public OrderedPair<Boolean, PointsToGraphNode> killsNode(Context context, PointsToGraph g) {
        return new OrderedPair<Boolean, PointsToGraphNode>(Boolean.TRUE, left.isFlowSensitive()
                ? new ReferenceVariableReplica(context, left, g.getHaf()) : null);
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

    @Override
    public boolean mayChangeFlowSensPointsToGraph() {
        return left.isFlowSensitive();
    }

}
