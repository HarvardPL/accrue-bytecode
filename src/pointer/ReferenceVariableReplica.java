package pointer;

import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.types.TypeReference;


public class ReferenceVariableReplica implements PointsToGraphNode {

    private final Context context;
    private final TypeReference expectedType;
    private final ReferenceVariable r;
    
    
    public ReferenceVariableReplica(Context context, ReferenceVariable r) {
    	this.r = r;
    	this.context = context;
    	this.expectedType = r.expectedType();
	}
    
    @Override
    public TypeReference getExpectedType() {
    	return expectedType;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((context == null) ? 0 : context.hashCode());
        result = prime * result + ((expectedType == null) ? 0 : expectedType.hashCode());
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
        if (expectedType == null) {
            if (other.expectedType != null)
                return false;
        } else if (!expectedType.equals(other.expectedType))
            return false;
        if (r == null) {
            if (other.r != null)
                return false;
        } else if (!r.equals(other.r))
            return false;
        return true;
    }

    
}
