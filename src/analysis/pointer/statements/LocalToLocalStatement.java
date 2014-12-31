package analysis.pointer.statements;

import java.util.ArrayList;
import java.util.Collection;
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
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

/**
 * Points-to statement for a local assignment, left = right
 */
public class LocalToLocalStatement<IK extends InstanceKey, C extends Context> extends PointsToStatement<IK, C> {

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
            ReferenceVariable right, IMethod m, boolean filterBasedOnType,
            boolean isFromMethodSummaryVariable) {
        super(m);
        assert !left.isSingleton() : left + " is static";
        this.left = left;
        this.right = right;
        filter = filterBasedOnType;
        this.isFromMethodSummaryVariable = isFromMethodSummaryVariable;
    }

    @Override
    public GraphDelta<IK, C> process(C context, HeapAbstractionFactory<IK,C> haf,
                                     PointsToGraph<IK,C> g, GraphDelta<IK, C> delta, StatementRegistrar<IK, C> registrar, StmtAndContext<IK, C> originator) {
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

    @Override
    public Collection<?> getReadDependencies(C ctxt, HeapAbstractionFactory<IK,C> haf) {
        ReferenceVariableReplica r = new ReferenceVariableReplica(ctxt, right, haf);

        if (!isFromMethodSummaryVariable) {
            return Collections.singleton(r);
        }
        List<Object> uses = new ArrayList<>(3);
        uses.add(r);
        // the assignment is from a method summary node, e.g., for an argument.
        // Add the IMethod so that we can get an appropriate dependency
        // for the callers.
        IMethod m = getMethod();
        uses.add(m);

        if (!m.isStatic() && !m.isPrivate()) {
            // add the possible Selector that VirtualCalls may use
            // to dispatch to us.
            uses.add(getMethod().getSelector());
        }

        return uses;
    }

    @Override
    public Collection<?> getWriteDependencies(C ctxt, HeapAbstractionFactory<IK,C> haf) {
        return Collections.singleton(new ReferenceVariableReplica(ctxt, left, haf));
    }
}
