package analysis.dataflow.interprocedural;

import util.print.PrettyPrinter;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

/**
 * Represents an abstract location, i.e., zero or more concrete locations.
 */
public class AbstractLocation {
    
    private final InstanceKey receiverContext;
    private final FieldReference field;

    public AbstractLocation(InstanceKey receiverContext, FieldReference field) {
        this.receiverContext = receiverContext;
        this.field = field;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((field == null) ? 0 : field.hashCode());
        result = prime * result + ((receiverContext == null) ? 0 : receiverContext.hashCode());
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
        AbstractLocation other = (AbstractLocation) obj;
        if (field == null) {
            if (other.field != null)
                return false;
        } else if (!field.equals(other.field))
            return false;
        if (receiverContext == null) {
            if (other.receiverContext != null)
                return false;
        } else if (!receiverContext.equals(other.receiverContext))
            return false;
        return true;
    }
    
    @Override
    public String toString() {
        return PrettyPrinter.parseType(field.getDeclaringClass()) + "." + field.getName() + " in " + receiverContext;
    }
}
