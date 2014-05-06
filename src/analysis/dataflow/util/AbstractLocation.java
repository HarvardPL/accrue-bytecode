package analysis.dataflow.util;

import util.print.PrettyPrinter;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.FieldReference;

/**
 * Represents an abstract location, i.e., zero or more concrete locations.
 */
public class AbstractLocation {

    /**
     * receiver heap context (null for static fields)
     */
    private final InstanceKey receiverContext;
    /**
     * field this location represents
     */
    private final FieldReference field;
    /**
     * true if this location represents the contents of an array
     */
    private final boolean isArrayContents;

    /**
     * Create an abstract location for a field
     * 
     * @param receiverContext
     *            receiver heap context (null for static fields)
     * @param field
     *            field this location represents
     */
    private AbstractLocation(InstanceKey receiverContext, FieldReference field, boolean isArrayContents) {
        this.receiverContext = receiverContext;
        this.field = field;
        this.isArrayContents = isArrayContents;
    }

    /**
     * Create an abstract location for a non-static field
     * 
     * @param receiverContext
     *            receiver heap context
     * @param field
     *            field this location represents
     */
    public static AbstractLocation createNonStatic(InstanceKey receiverContext, FieldReference field) {
        return new AbstractLocation(receiverContext, field, false);
    }

    /**
     * Create an abstract location representing the contents of an array
     * 
     * @param array
     *            array heap context
     */
    public static AbstractLocation createArrayContents(InstanceKey array) {
        return new AbstractLocation(array, null, true);
    }

    /**
     * Create an abstract location for a non-static field
     * 
     * @param field
     *            field this location represents
     */
    public static AbstractLocation createStatic(FieldReference field) {
        return new AbstractLocation(null, field, false);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((field == null) ? 0 : field.hashCode());
        result = prime * result + (isArrayContents ? 1231 : 1237);
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
        if (isArrayContents != other.isArrayContents)
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
        if (isArrayContents) {
            return receiverContext + "." + PointsToGraph.ARRAY_CONTENTS;
        }
        return PrettyPrinter.parseType(field.getDeclaringClass()) + "." + field.getName()
                                        + (receiverContext == null ? " (static)" : " in " + receiverContext);
    }
}
