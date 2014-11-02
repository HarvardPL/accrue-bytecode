package analysis.pointer.graph;

import com.ibm.wala.types.TypeReference;

/**
 * Local variable or static field in a particular code context or an object field
 */
public interface PointsToGraphNode {
    /**
     * Get the type this points to graph node represents
     *
     * @return type
     */
    TypeReference getExpectedType();

    /**
     * Should the points to set of this PointsToGraphNode flow sensitive or not?
     */
    boolean isFlowSensitive();
}
