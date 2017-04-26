package analysis.dataflow.interprocedural.pdg.graph.node;

import java.io.Writer;

import org.json.JSONException;

import analysis.dataflow.interprocedural.pdg.serialization.JSONSerializable;
import analysis.dataflow.interprocedural.pdg.serialization.PDGNodeClassName;

import com.ibm.wala.types.TypeReference;

/**
 * Node in a program dependence graph
 */
public abstract class PDGNode implements JSONSerializable {

    /**
     * human readable description of this node
     */
    private String description;
    /**
     * Type of this node
     */
    private final PDGNodeType type;

    /**
     * Java type of the expression represented by this node (or null if there is none)
     */
    private final TypeReference javaType;

    /**
     * Create a new PDG Node
     *
     * @param description
     *            human readable description of this node
     * @param type
     *            Type of node to create
     */
    protected PDGNode(String description, PDGNodeType type, TypeReference javaType) {
        this.description = description;
        this.type = type;
        this.javaType = javaType;
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

    /**
     * Class name used for serialization
     *
     * @return name of the concrete subclass
     */
    public abstract PDGNodeClassName getClassName();

    /**
     * String representation of the analysis context, used for serialization
     *
     * @return
     */
    public abstract String contextString();

    /**
     * Get the java type of this pdg node if there is one, null otherwise
     * 
     * @return type reference or null
     */
    public TypeReference getJavaType() {
        return this.javaType;
    }

    @Override
    public final void writeJSON(Writer out) throws JSONException {
        toJSON().write(out);
    }
}
