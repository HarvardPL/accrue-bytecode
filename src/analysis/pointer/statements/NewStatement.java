package analysis.pointer.statements;

import analysis.dataflow.interprocedural.ExitType;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;

/**
 * Points-to graph node for a "new" statement, e.g. Object o = new Object()
 */
public class NewStatement extends PointsToStatement {

    /**
     * Points-to graph node for the assignee of the new
     */
    private final ReferenceVariable result;
    /**
     * Class being created
     */
    private final IClass newClass;
    /**
     * Reference variable for this allocation site
     */
    private final AllocSiteNode alloc;

    /**
     * Points-to graph statement for a "new" instruction, e.g. Object o = new
     * Object()
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
    public NewStatement(ReferenceVariable result, IClass newClass, IR ir, SSANewInstruction i) {
        super(ir, i);
        this.result = result;
        this.newClass = newClass;
        alloc = AllocSiteNodeFactory.getAllocationNode(newClass, ir.getMethod().getDeclaringClass(), i);
    }

    /**
     * Points-to graph statement for an allocation that does not result from a
     * new instruction
     * 
     * @param result
     *            Points-to graph node for the assignee of the new allocation
     * @param newClass
     *            Class being created
     * @param cha
     *            class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    private NewStatement(ReferenceVariable result, IClass newClass, IR ir, SSAInstruction i) {
        super(ir, i);
        this.result = result;
        this.newClass = newClass;
        alloc = AllocSiteNodeFactory.getGeneratedAllocationNode(newClass, ir.getMethod().getDeclaringClass(), i);
    }

    /**
     * Points-to graph statement for an allocation synthesized for the exit to a
     * native method
     * 
     * @param result
     *            Points-to graph node for the assignee of the new allocation
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
    private NewStatement(ReferenceVariable result, IClass newClass, IR ir, SSAInvokeInstruction i, ExitType exitType) {
        super(ir, i);
        this.result = result;
        this.newClass = newClass;
        alloc = AllocSiteNodeFactory.getAllocationNodeForNative(newClass, ir.getMethod().getDeclaringClass(), i,
                                        exitType);
    }

    /**
     * Get a points-to statement representing the allocation of a JVM generated
     * exception (e.g. NullPointerException), and the assignment of this new
     * exception to a local variable
     * 
     * @param exceptionAssignee
     *            Reference variable for the local variable the exception is
     *            assigned to after being created
     * @param exceptionClass
     *            Class for the exception
     * @param ir
     *            code containing the instruction throwing the exception
     * @param i
     *            exception throwing the exception
     * @return a statement representing the allocation of a JVM generated
     *         exception to a local variable
     */
    public static NewStatement newStatementForGeneratedException(ReferenceVariable exceptionAssignee,
                                    IClass exceptionClass, IR ir, SSAInstruction i) {
        return new NewStatement(exceptionAssignee, exceptionClass, ir, i);
    }

    /**
     * Get a points-to statement representing the allocation of a String literal
     * 
     * @param local
     *            Reference variable for the local variable for the string at
     *            the allocation site
     * @param ir
     *            code containing the instruction throwing the exception
     * @param i
     *            exception throwing the exception
     * @param stringClass
     *            WALA representation of the java.lang.String class
     * @return a statement representing the allocation of a new string literal
     */
    public static NewStatement newStatementForStringLiteral(ReferenceVariable local, IR ir, SSAInstruction i,
                                    IClass stringClass) {
        return new NewStatement(local, stringClass, ir, i);
    }

    /**
     * Get a points-to statement representing the exit from a native method
     * 
     * @param summaryNode
     *            Reference variable for the method exit summary node
     * @param ir
     *            code containing the native method invocation
     * @param i
     *            native method invocation
     * @param exitClass
     *            WALA representation of the the return type class
     * @param exitType
     *            whether this node is for exceptional or normal exit
     * @return a statement representing the allocation of a new
     */
    public static NewStatement newStatementForNativeExit(ReferenceVariable summaryNode, IR ir, SSAInvokeInstruction i,
                                    IClass exitClass, ExitType exitType) {
        return new NewStatement(summaryNode, exitClass, ir, i, exitType);
    }

    /**
     * Get a points-to statement representing the allocation of the value field
     * of a string
     * 
     * @param local
     *            Reference variable for the local variable for the string at
     *            the allocation site
     * @param ir
     *            code containing the instruction throwing the exception
     * @param i
     *            exception throwing the exception
     * @param charArrayClass
     *            WALA representation of a char[]
     * @return a statement representing the allocation of a new string literal's
     *         value field
     */
    public static NewStatement newStatementForStringField(ReferenceVariable local, IR ir, SSAInstruction i,
                                    IClass charArrayClass) {
        return new NewStatement(local, charArrayClass, ir, i);
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

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((alloc == null) ? 0 : alloc.hashCode());
        result = prime * result + ((newClass == null) ? 0 : newClass.hashCode());
        result = prime * result + ((this.result == null) ? 0 : this.result.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        NewStatement other = (NewStatement) obj;
        if (alloc == null) {
            if (other.alloc != null)
                return false;
        } else if (!alloc.equals(other.alloc))
            return false;
        if (newClass == null) {
            if (other.newClass != null)
                return false;
        } else if (!newClass.equals(other.newClass))
            return false;
        if (result == null) {
            if (other.result != null)
                return false;
        } else if (!result.equals(other.result))
            return false;
        return true;
    }
}
