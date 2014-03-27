package analysis.dataflow;

import util.print.IRWriter;

/**
 * Unit data-flow fact used for analyses that do not need to compute a result on
 * each edge. For an example @see {@link IRWriter} which accumulates
 * a global String.
 */
public class Unit {

    /**
     * The one and only value for this dataflow item
     */
    public static final Unit VALUE = new Unit();

    /**
     * Constructor that is private to prevent external invocation
     */
    private Unit() {
        // Intentionally empty
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
