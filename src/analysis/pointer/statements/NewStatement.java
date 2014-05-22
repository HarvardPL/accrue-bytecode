package analysis.pointer.statements;

import analysis.dataflow.interprocedural.ExitType;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;

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
     * Points-to graph statement for a "new" instruction, e.g. Object o = new Object()
     * 
     * @param result
     *            Points-to graph node for the assignee of the new
     * @param newClass
     *            Class being created
     * @param cha
     *            class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected NewStatement(ReferenceVariable result, IClass newClass, IR ir, SSANewInstruction i) {
        super(ir, i);
        this.result = result;
        alloc = AllocSiteNodeFactory.getAllocationNode(newClass, ir.getMethod().getDeclaringClass(), i,
                                        "Normal Allocation");
    }

    /**
     * Points-to graph statement for an allocation of a generated exception that does not result from a new instruction
     * 
     * @param result
     *            the assignee of the new allocation
     * @param allocatedClass
     *            Class being created
     * @param cha
     *            class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected NewStatement(ReferenceVariable result, IClass allocatedClass, IR ir, SSAInstruction i) {
        super(ir, i);
        this.result = result;
        alloc = AllocSiteNodeFactory.getGeneratedExceptionNode(allocatedClass, ir.getMethod().getDeclaringClass(), i,
                                        result);
    }

    /**
     * Points-to graph statement for an allocation that does not result from a new instruction
     * 
     * @param name
     *            debug name to be put into the allocation node
     * @param result
     *            the assignee of the new allocation
     * @param allocatedClass
     *            Class being created
     * @param cha
     *            class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    protected NewStatement(String name, ReferenceVariable result, IClass allocatedClass, IR ir, SSAInstruction i) {
        super(ir, i);
        this.result = result;
        alloc = AllocSiteNodeFactory.getGeneratedAllocationNode(name, allocatedClass, ir.getMethod()
                                        .getDeclaringClass(), i, result);
    }

    /**
     * Points-to graph statement for an allocation synthesized for the exit to a native method
     * 
     * @param result
     *            the assignee of the new allocation
     * @param newClass
     *            Class being created
     * @param cha
     *            class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @param exitType
     *            Type this node is for normal return or exceptional
     */
    protected NewStatement(ReferenceVariable result, IClass newClass, IR ir, SSAInvokeInstruction i, ExitType exitType,
                                    IMethod m) {
        super(ir, i);
        this.result = result;
        alloc = AllocSiteNodeFactory.getAllocationNodeForNative(newClass, ir.getMethod().getDeclaringClass(), i,
                                        exitType, m);
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        InstanceKey k = haf.record(context, alloc, getCode());
        assert k != null;
        ReferenceVariableReplica r = new ReferenceVariableReplica(context, result);
        return g.addEdge(r, k);
    }

    @Override
    public String toString() {
        return result + " = " + alloc;
    }
}
