package analysis.dataflow.interprocedural.pdg.graph;

/**
 * Label for the program dependence graph edges with the type of the dependency
 * between the two nodes.
 */
public enum PDGEdgeType {
    /**
     * Explicit dependence, this represents the fact that the value of a
     * variable or expression (represented by a node in the PDG) depends
     * explicitly on those of another variable or expressions (represented by
     * another node in the PDG). An example of this kind of flow is the values
     * of <code>x</code> and <code>y</code> into the value of <code>x + y</code>
     * ; this would result in explicit dependencies from <code>x</code> and
     * <code>y</code> into <code>x + y</code>.
     */
    EXP,
    /**
     * Copy dependence, this represents an edge where the values of the nodes on
     * the edge is the same. An example is the dependence of a variable,
     * <code>x</code>, on the last node in an expression <code>x</code> is
     * assigned to.
     */
    COPY,
    /**
     * Mark this edge as an input edge. This is for the first flow into a secret
     * input, often due to a security cast.
     */
    INPUT,
    /**
     * Mark this edge as an output edge. This is for marking where information
     * leaves the system.
     */
    OUTPUT,
    /**
     * Edge that results from the merging of control flow, this is a type of
     * explicit flow
     */
    MERGE,
    /**
     * Edge that indicates a control dependency, the target is executed if the
     * source is satisfied.
     */
    IMPLICIT,
    /**
     * Edge that indicates that the source node represents a reference and the
     * value at the target depends on the object that reference points to.
     */
    POINTER,
    /**
     * The source of this type of edge represents a boolean, the destination
     * represents the condition where that boolean is true.
     */
    TRUE,
    /**
     * The source of this type of edge represents a boolean, the destination
     * represents the condition where the sources of all incoming edges are true
     */
    CONJUNCTION,
    /**
     * The source of this type of edge represents a boolean, the destination
     * represents the condition where that boolean is false.
     */
    FALSE,
    /**
     * This is used to connect to a summary node for an unknown value computed
     * from the arguments to a procedure with missing source code. This has
     * similar semantics to EXP, but there is no soundness guarantee here (there
     * may be side effects that are not accounted for)
     */
    MISSING;
    
    /**
     * Short name for display
     * 
     * @return unique but short type name
     */
    public String shortName() {
        switch(this) {
        case INPUT : return "IN";
        case MISSING : return "MS";
        case CONJUNCTION : return "&";
        default : return this.toString().substring(0, 1);
        }
    }
}