package analysis.pointer.graph;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;


public class ReferenceVariableReplica implements PointsToGraphNode {

    private final Context context;
    private final ReferenceVariable r;
    
    
    public ReferenceVariableReplica(Context context, ReferenceVariable r) {
        if (r == null) {
            System.out.println("");
        }
        assert (r != null);
        assert (context != null);
    	this.r = r;
    	this.context = context;
	}
    
    @Override
    public TypeReference getExpectedType() {
    	return r.getExpectedType();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((context == null) ? 0 : context.hashCode());
        result = prime * result + ((r == null) ? 0 : r.hashCode());
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
        ReferenceVariableReplica other = (ReferenceVariableReplica) obj;
        if (context == null) {
            if (other.context != null)
                return false;
        } else if (!context.equals(other.context))
            return false;
        if (r == null) {
            if (other.r != null)
                return false;
        } else if (!r.equals(other.r))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return  r + " in " + context;
    }
}
