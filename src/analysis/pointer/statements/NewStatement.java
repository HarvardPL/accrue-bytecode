package analysis.pointer.statements;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.recency.InstanceKeyRecency;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.PointsToGraphNode;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.ProgramPoint.ProgramPointReplica;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to graph statement for a "new" statement, e.g. Object o = new Object()
 */
public class NewStatement extends PointsToStatement {

    /**
     * Points-to graph node for the assignee of the new
     */
    private final ReferenceVariable result;
    /**
     * Reference variable for this allocation site
     */
    private final AllocSiteNode alloc;

    /**
     * Points-to graph statement for an allocation resulting from a new instruction, e.g. o = new Object
     *
     * @param result Points-to graph node for the assignee of the new
     * @param newClass Class being created
     * @param m method the points-to statement came from
     * @param pc program counter of the allocation
     */
    protected NewStatement(ReferenceVariable result, IClass newClass, ProgramPoint pp, int pc) {
        super(pp);
        this.result = result;
        alloc = AllocSiteNodeFactory.createNormal(newClass,
                                                  pp.containingProcedure().getDeclaringClass(),
                                                  result,
                                                  pc,
                                                  -1);
    }

    /**
     * Points-to graph statement for an allocation that does not result from a new instruction
     *
     * @param name debug name to be put into the allocation node
     * @param result the assignee of the new allocation
     * @param allocatedClass Class being created
     * @param m method the points-to statement came from
     * @param isStringLiteral true if this allocation is for a string literal, if this is true then
     *            <code>name</name> should be the literal string being allocated
     */
    protected NewStatement(String name, ReferenceVariable result, IClass allocatedClass, ProgramPoint pp,
                           boolean isStringLiteral) {
        super(pp);
        this.result = result;
        alloc = AllocSiteNodeFactory.createGenerated(name,
                                                     allocatedClass,
                                                     pp.containingProcedure().getDeclaringClass(),
                                                     result,
                                                     isStringLiteral);
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        InstanceKeyRecency newHeapContext = haf.record(alloc, context);
        assert newHeapContext != null;

        ReferenceVariableReplica r = new ReferenceVariableReplica(context, result, haf);
        ProgramPointReplica ppr = ProgramPointReplica.create(context, this.programPoint());

        // record the allocation
        g.recordAllocation(newHeapContext, ppr);

        // add the edge
        GraphDelta d = g.addEdge(r, newHeapContext, ppr.post(), originator);

        d = d.combine(g.copyEdgesForAllFields(newHeapContext, ppr, originator));

        // add the allocation site to delta
        d.addAllocationSite(newHeapContext, ppr);

        return d;
    }

    @Override
    public InstanceKeyRecency justAllocated(Context context, PointsToGraph g) {
        return g.getHaf().record(alloc, context);
    }

    @Override
    public PointsToGraphNode killed(Context context, PointsToGraph g) {
        return result.isFlowSensitive() ? new ReferenceVariableReplica(context, result, g.getHaf()) : null;
    }

    @Override
    public String toString() {
        return result + " = " + alloc;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        throw new UnsupportedOperationException("NewStatement has no uses that can be reassigned");
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.emptyList();
    }

    @Override
    public ReferenceVariable getDef() {
        return result;
    }

    @Override
    public Collection<?> getReadDependencies(Context ctxt, HeapAbstractionFactory haf) {
        return Collections.emptySet();
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt, HeapAbstractionFactory haf) {
        return Collections.singleton(new ReferenceVariableReplica(ctxt, result, haf));
    }

    @Override
    public boolean mayChangeFlowSensPointsToGraph() {
        // the allocation may affect the points to graph, even though result
        // is flow insensitive
        assert !result.isFlowSensitive();

        // NOTE: if we end up not being flow sensitive for some allocations,
        // we could improve this.
        return true;
    }

}
