package analysis.dataflow.util;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.classLoader.IField;
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
    private final IField field;
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
    private AbstractLocation(InstanceKey receiverContext, IField field, boolean isArrayContents) {
        assert receiverContext == null || receiverContext.getConcreteType() != null : "Cannot create a field on a null receiver";
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
        assert receiverContext.getConcreteType() != null : "Cannot create a field on a null receiver";
        IField f = AnalysisUtil.getClassHierarchy().resolveField(receiverContext.getConcreteType(), field);
        AbstractLocation a = new AbstractLocation(receiverContext, f, false);
        return a;
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
        IField f = AnalysisUtil.getClassHierarchy().resolveField(field);
        return new AbstractLocation(null, f, false);
    }

    /**
     * Get the heap context for the receiver. Will be null if this represents a static field.
     *
     * @return Heap context (null for static)
     */
    public InstanceKey getReceiverContext() {
        return receiverContext;
    }

    /**
     * Get the field (null for array contents)
     *
     * @return the field this location is for
     */
    public IField getField() {
        return this.field;
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
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AbstractLocation other = (AbstractLocation) obj;
        if (field == null) {
            if (other.field != null) {
                return false;
            }
        } else if (!field.equals(other.field)) {
            return false;
        }
        if (isArrayContents != other.isArrayContents) {
            return false;
        }
        if (receiverContext == null) {
            if (other.receiverContext != null) {
                return false;
            }
        } else if (!receiverContext.equals(other.receiverContext)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        if (isArrayContents) {
            return PrettyPrinter.typeString(receiverContext.getConcreteType()) + "." + PointsToGraph.ARRAY_CONTENTS
                   /* + " in " + receiverContext*/;
        }
        if (field == null && PointsToAnalysis.outputLevel >= 1) {
            System.err.println("WARNING: null field in AbstractLocation in  " + receiverContext);
        }
        return (field != null ? PrettyPrinter.typeString(field.getDeclaringClass()) + "." + field.getName()
                : "NULL FIELD") + (receiverContext == null ? " (static)" : "" /*in " + receiverContext*/);
    }
}
