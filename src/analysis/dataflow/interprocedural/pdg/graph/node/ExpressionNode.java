package analysis.dataflow.interprocedural.pdg.graph.node;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ibm.wala.ipa.callgraph.CGNode;

public class ExpressionNode extends PDGNode {

    private static final Map<ExpressionNodeKey, ExpressionNode> nodes = new LinkedHashMap<>();;

    private final String description;
    private final PDGNodeType type;
    private final CGNode n;

    private ExpressionNode(String description, PDGNodeType type, CGNode n, Object disambuationKey) {
        this.description = description;
        this.type = type;
        this.n = n;
    }

    public static ExpressionNode create(String description, PDGNodeType type, CGNode n, Object disambuationKey) {
        ExpressionNodeKey key = new ExpressionNodeKey(type, n, disambuationKey);
        ExpressionNode node = nodes.get(key);
        if (node == null) {
            node = new ExpressionNode(description, type, n, disambuationKey);
            nodes.put(key, node);
        }
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
