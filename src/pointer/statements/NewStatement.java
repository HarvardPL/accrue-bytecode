package pointer.statements;

import pointer.analyses.HeapAbstractionFactory;
import pointer.graph.AllocSiteNode;
import pointer.graph.LocalNode;
import pointer.graph.PointsToGraph;
import pointer.graph.ReferenceVariableReplica;

import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

/**
 * Points-to graph node for a "new" statement, e.g. Object o = new Object() TODO
 * I believe this is the allocation not the constructor call
 */
public class NewStatement implements PointsToStatement {

    /**
     * Points-to graph node for the assignee of the new
     */
    private final LocalNode result;
    /**
     * Constructor call site
     */
    private final NewSiteReference newSite;
    /**
     * Code containing the new statement
     */
    private final IR ir;
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
     * @param ir
     *            Code containing the new statement
     */
    public NewStatement(LocalNode result, NewSiteReference newSite, IR ir) {
        this.result = result;
        this.newSite = newSite;
        this.ir = ir;
        alloc = new AllocSiteNode(newSite.toString(), newSite.getDeclaredType(), ir.getMethod().getDeclaringClass());
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        InstanceKey k = haf.record(context, alloc, ir);
        ReferenceVariableReplica r = new ReferenceVariableReplica(context, result);

        // Add an edge from the assignee to the newly allocated object
        return g.addEdge(r, k);
    }

    @Override
    public TypeReference getExpectedType() {
        return result.getExpectedType();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((ir == null) ? 0 : ir.hashCode());
        result = prime * result + ((newSite == null) ? 0 : newSite.hashCode());
        result = prime * result + ((this.result == null) ? 0 : this.result.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        NewStatement other = (NewStatement) obj;
        if (ir == null) {
            if (other.ir != null)
                return false;
        } else if (!ir.equals(other.ir))
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
    
    @Override
    public IR getCode() {
        return ir;
    }
}
