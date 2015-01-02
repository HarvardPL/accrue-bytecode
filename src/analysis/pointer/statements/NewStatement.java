package analysis.pointer.statements;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

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
     * @param lineNumber line number from source code if one can be found, -1 otherwise
     */
    protected NewStatement(ReferenceVariable result, IClass newClass, IMethod m, int pc, int lineNumber) {
        super(m);
        this.result = result;
        alloc = AllocSiteNodeFactory.createNormal(newClass, m, result, pc, lineNumber);
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
    protected NewStatement(String name, ReferenceVariable result, IClass allocatedClass, IMethod m,
                           boolean isStringLiteral) {
        super(m);
        this.result = result;
        alloc = AllocSiteNodeFactory.createGenerated(name, allocatedClass, m, result, isStringLiteral);
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf,
            PointsToGraph g, GraphDelta delta, StatementRegistrar registrar, StmtAndContext originator) {
        InstanceKey newHeapContext = haf.record(alloc, context);
        assert newHeapContext != null;

        ReferenceVariableReplica r = new ReferenceVariableReplica(context, result, haf);
        return g.addEdge(r, newHeapContext);
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
    public Collection<?> getReadDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        return Collections.emptySet();
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt,
            HeapAbstractionFactory haf) {
        return Collections.singleton(new ReferenceVariableReplica(ctxt, result, haf));
    }
}
