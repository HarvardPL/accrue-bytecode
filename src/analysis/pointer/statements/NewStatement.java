package analysis.pointer.statements;

import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.AllocSiteNode;
import analysis.pointer.graph.LocalNode;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSANewInstruction;

/**
 * Points-to graph node for a "new" statement, e.g. Object o = new Object()
 */
public class NewStatement extends PointsToStatement {

    /**
     * Points-to graph node for the assignee of the new
     */
    private final LocalNode result;
    /**
     * Constructor call site
     */
    private final NewSiteReference newSite;
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
     * @param newSite
     *            Constructor call site
     * @param cha
     *            class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     */
    public NewStatement(LocalNode result, NewSiteReference newSite, IClassHierarchy cha, IR ir,
            SSANewInstruction i) {
        super(ir, i);
        this.result = result;
        this.newSite = newSite;
        IClass instantiated = cha.lookupClass(newSite.getDeclaredType());
        assert (instantiated != null) : "No class found for " + PrettyPrinter.parseType(newSite.getDeclaredType());
        alloc = new AllocSiteNode("new " + PrettyPrinter.parseType(newSite.getDeclaredType()), instantiated, ir
                .getMethod().getDeclaringClass());
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        InstanceKey k = haf.record(context, alloc, getCode());
        ReferenceVariableReplica r = new ReferenceVariableReplica(context, result);

        boolean changed = false;
        if (WalaAnalysisUtil.INCLUDE_IMPLICIT_ERRORS) {
            // During resolution of the symbolic reference to the class, array,
            // or interface type, any of the exceptions documented in 5.4.3.1
            // can be thrown.

            // Otherwise, if the symbolic reference to the class, array, or
            // interface type resolves to an interface or is an abstract class,
            // new throws an InstantiationError.
            // TODO implicit errors from new
        }
        
        // TODO Handle array dimensions for new array

        // Add an edge from the assignee to the newly allocated object
        return changed || g.addEdge(r, k);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(result.toString() + " = new ");
        s.append(PrettyPrinter.parseType(newSite.getDeclaredType()));
        return s.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((alloc == null) ? 0 : alloc.hashCode());
        result = prime * result + ((newSite == null) ? 0 : newSite.hashCode());
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
        if (newSite == null) {
            if (other.newSite != null)
                return false;
        } else if (!newSite.equals(other.newSite))
            return false;
        if (result == null) {
            if (other.result != null)
                return false;
        } else if (!result.equals(other.result))
            return false;
        return true;
    }
}
