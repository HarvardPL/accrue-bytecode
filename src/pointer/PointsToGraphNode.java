package pointer;

import com.ibm.wala.types.TypeReference;

public interface PointsToGraphNode {
    TypeReference getExpectedType();
}
