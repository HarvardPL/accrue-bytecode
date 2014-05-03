package analysis.dataflow.interprocedural.pdg.graph.node;

/**
 * Node in a program dependence graph
 */
public abstract class PDGNode {

    private String description;
    private final PDGNodeType type;

    protected PDGNode(String description, PDGNodeType type) {
        this.description = description;
        this.type = type;
    }

    @Override
    public String toString() {
        return description;
    }

    public PDGNodeType getType() {
        return type;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Pointer equality
     * <p>
     * {@inheritDoc}
     */
    @Override
    public final boolean equals(Object obj) {
        return this == obj;
    }

    /**
     * Object identity hash code
     * <p>
     * {@inheritDoc}
     */
    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }
}
