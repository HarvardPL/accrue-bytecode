package analysis.dataflow.interprocedural.pdg.graph.node;

import java.util.LinkedHashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.dataflow.util.AbstractLocation;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

public class PDGNodeFactory {
    private static final Map<AbstractLocation, AbstractLocationPDGNode> locationNodes = new LinkedHashMap<>();
    private static final Map<ExpressionNodeKey, ProcedurePDGNode> expressionNodes = new LinkedHashMap<>();;

    public static AbstractLocationPDGNode findOrCreateAbstractLocation(AbstractLocation loc) {
        AbstractLocationPDGNode node = locationNodes.get(loc);
        if (node == null) {
            node = new AbstractLocationPDGNode(loc);
            locationNodes.put(loc, node);
        }
        return node;
    }

    /**
     * Find the unique node for the local variable or constant used by
     * instruction, <code>i</code>, in position <code>useNumber</code>. Create
     * if necessary.
     * 
     * @param i
     *            instruction with at least <code>useNumber</code> + 1 uses
     * @param useNumber
     *            valid use index (uses are 0 indexed)
     * @param cgNode
     *            call graph node containing the code and context for the
     *            instruction
     * @return PDG node for the use with the given use number in <code>i</code>
     */
    public static PDGNode findOrCreateUse(SSAInstruction i, int useNumber, CGNode cgNode) {
        assert i.getNumberOfUses() > useNumber;
        int valueNumber = i.getUse(useNumber);
        return findOrCreateLocal(valueNumber, cgNode);
    }

    /**
     * Find the unique node for the local variable or constant given by the
     * value number. Create if necessary.
     * 
     * @param valueNumber
     *            value number for the local variable
     * @param cgNode
     *            call graph node containing the code and context for the local
     *            variable
     * @return PDG node for the local variable
     */
    public static PDGNode findOrCreateLocal(int valueNumber, CGNode cgNode) {
        assert valueNumber >= 0;
        IR ir = cgNode.getIR();
        PDGNode n;
        if (ir.getSymbolTable().isConstant(valueNumber)) {
            n = PDGNodeFactory.findOrCreateOther(PrettyPrinter.valString(valueNumber, ir), PDGNodeType.BASE_VALUE,
                                            cgNode, valueNumber);
        } else {
            n = PDGNodeFactory.findOrCreateOther(PrettyPrinter.valString(valueNumber, ir), PDGNodeType.LOCAL, cgNode,
                                            valueNumber);
        }

        return n;
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
     *            call graph node containing the code and context for the
     *            expression
     * @param disambuationKey
     *            key used to distinguish nodes (in addition to the call graph
     *            node and type)
     * @return unique PDG node of the given type created in the given call graph
     *         node with the given disambiguation key
     */
    public static ProcedurePDGNode findOrCreateOther(String description, PDGNodeType type, CGNode n, Object disambuationKey) {
        ExpressionNodeKey key = new ExpressionNodeKey(type, n, disambuationKey);
        ProcedurePDGNode node = expressionNodes.get(key);
        if (node == null) {
            node = new ProcedurePDGNode(description, type, n);
            expressionNodes.put(key, node);
        }
        return node;
    }

    /**
     * Find the unique node for the generated exception of the given type thrown
     * by <code>i</code> in the code and context given by the call graph node.
     * 
     * @param type
     *            type of the generated exception
     * @param n
     *            call graph node containing the code and context the exception
     *            is generated in
     * @param i
     *            instruction the exception is generated for
     * @return PDG node for the generated exception
     */
    public static PDGNode findOrCreateGeneratedException(TypeReference type, CGNode n, SSAInstruction i) {
        return findOrCreateOther("Gen-" + PrettyPrinter.parseType(type), PDGNodeType.BASE_VALUE, n, i);
    }

    /**
     * Find a the unique node corresponding to the (first) local variable
     * defined by the instruction, <code>i</code>. Create this node if it does
     * not already exist.
     * 
     * @param i
     *            instruction that defines a local variable
     * @param n
     *            call graph node containing the code and context the local is
     *            defined in
     * @return unique node for the (first) local variable defined by
     *         <code>i</code>
     */
    public static ProcedurePDGNode findOrCreateLocalDef(SSAInstruction i, CGNode n) {
        assert i.hasDef();
        ExpressionNodeKey key = new ExpressionNodeKey(PDGNodeType.LOCAL, n, i.getDef());
        ProcedurePDGNode node = expressionNodes.get(key);
        if (node == null) {
            node = new ProcedurePDGNode(PrettyPrinter.instructionString(i, n.getIR()), PDGNodeType.LOCAL, n);
            expressionNodes.put(key, node);
        } else {
            node.setDescription(PrettyPrinter.instructionString(i, n.getIR()));
        }
        return node;
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
