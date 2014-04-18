package analysis.dataflow;

import analysis.dataflow.util.AbstractValue;
import util.print.IRWriter;

/**
 * Singleton data-flow fact used for an analysis that does not need to compute a
 * result on each edge, but still ensures that a fact is computed for each node
 * of the data-flow graph (a still node may be computed on more than once if
 * there are back edges). For an example @see {@link IRWriter} which accumulates
 * a global String.
 */
public class Unit implements AbstractValue<Unit> {

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

    @Override
    public String toString() {
        return "UNIT";
    }

    @Override
    public boolean leq(Unit that) {
        return true;
    }

    @Override
    public boolean isBottom() {
        return true;
    }

    @Override
    public Unit join(Unit that) {
        return VALUE;
    }
}
