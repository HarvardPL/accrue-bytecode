package analysis.dataflow.interprocedural.pdg.graph.node;

/**
 * Node in a program dependence graph
 */
public abstract class PDGNode {

    /**
     * human readable description of this node
     */
    private String description;
    /**
     * Type of this node
     */
    private final PDGNodeType type;

    /**
     * Create a new PDG Node
     * 
     * @param description
     *            human readable description of this node
     * @param type
     *            Type of node to create
     */
    protected PDGNode(String description, PDGNodeType type) {
        this.description = description;
        this.type = type;
    }

    @Override
    public String toString() {
        return description;
    }

    /**
     * Type of this node
     * 
     * @return type of this node
     */
    public PDGNodeType getNodeType() {
        return type;
    }

    /**
     * Set the human readable description of this node
     * 
     * @param description
     *            string description of this node
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * String representation of the group this should be printed to when
     * printing a dot graph with clusters. This is also used in PIDGIN for
     * debugging queries.
     * 
     * @return name of to group this node under
     */
    public abstract String groupingName();

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
