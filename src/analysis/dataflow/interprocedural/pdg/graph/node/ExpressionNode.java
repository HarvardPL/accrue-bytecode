package analysis.dataflow.interprocedural.pdg.graph.node;

import java.util.LinkedHashMap;
import java.util.Map;

import util.print.PrettyPrinter;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.SSAInstruction;

public class ExpressionNode extends PDGNode {

    private static final Map<ExpressionNodeKey, ExpressionNode> nodes = new LinkedHashMap<>();;

    private String description;
    private final PDGNodeType type;
    private final CGNode n;

    /**
     * Create the node with the given type in the code and context given by the
     * call graph node, <code>n</code>.
     * 
     * @param type
     *            type of expression node being created
     * @param n
     *            call graph node containing the code and context the node is
     *            created in
     * @return PDG node of the given type created in the given call graph node
     */
    private ExpressionNode(PDGNodeType type, CGNode n) {
        this.type = type;
        this.n = n;
    }

    /**
     * Find the unique node with the given type in the code and context given by
     * the call graph node, <code>n</code>. In order to ensure that this node is
     * unique a disambiguation key must be specified to distinguish different
     * nodes created with the same type in the same call graph node. If no such
     * node exists one will be created.
     * 
     * @param description
     *            human readable description of the node, will not be used to
     *            disambiguate nodes and may later be changed
     * @param type
     *            type of expression node being created
     * @param n
     *            call graph node containing the code and context the node is
     *            created in
     * @param disambuationKey
     *            key used to distinguish nodes
     * @return unique PDG node of the given type created in the given call graph
     *         node with the given disambiguation key
     */
    public static ExpressionNode findOrCreate(String description, PDGNodeType type, CGNode n, Object disambuationKey) {
        ExpressionNodeKey key = new ExpressionNodeKey(type, n, disambuationKey);
        ExpressionNode node = nodes.get(key);
        if (node == null) {
            node = new ExpressionNode(type, n);
            nodes.put(key, node);
        }
        node.setDescription(description);
        return node;
    }

    /**
     * Find a the unique node corresponding to the local variable defined by the
     * instruction, <code>i</code>. Create this node if it does not already
     * exist.
     * 
     * @param i
     *            instruction that defines a local variable
     * @param n
     *            call graph node containing the code and context the local is
     *            defined in
     * @return unique node for the local variable defined by <code>i</code>
     */
    public static ExpressionNode findOrCreateLocalDef(SSAInstruction i, CGNode n) {
        assert i.hasDef();
        ExpressionNodeKey key = new ExpressionNodeKey(PDGNodeType.LOCAL, n, i.getDef());
        ExpressionNode node = nodes.get(key);
        if (node == null) {
            node = new ExpressionNode(PDGNodeType.LOCAL, n);
            nodes.put(key, node);
        }
        node.setDescription(PrettyPrinter.instructionString(i, n.getIR()));
        return node;
    }

    @Override
    public String toString() {
        return description;
    }

    public CGNode getCGNode() {
        return n;
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
    public boolean equals(Object obj) {
        return this == obj;
    }

    /**
     * Object identity hash code
     * <p>
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    private static class ExpressionNodeKey {
        private final PDGNodeType type;
        private final CGNode n;
        private final Object disambuationKey;

        public ExpressionNodeKey(PDGNodeType type, CGNode n, Object disambuationKey) {
            this.type = type;
            this.n = n;
            this.disambuationKey = disambuationKey;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((disambuationKey == null) ? 0 : disambuationKey.hashCode());
            result = prime * result + ((n == null) ? 0 : n.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
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
            ExpressionNodeKey other = (ExpressionNodeKey) obj;
            if (disambuationKey == null) {
                if (other.disambuationKey != null)
                    return false;
            } else if (!disambuationKey.equals(other.disambuationKey))
                return false;
            if (n == null) {
                if (other.n != null)
                    return false;
            } else if (!n.equals(other.n))
                return false;
            if (type != other.type)
                return false;
            return true;
        }
    }
}
