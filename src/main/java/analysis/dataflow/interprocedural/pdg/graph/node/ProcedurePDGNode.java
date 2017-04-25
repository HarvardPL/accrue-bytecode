package analysis.dataflow.interprocedural.pdg.graph.node;

import org.json.JSONObject;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.pdg.serialization.JSONUtil;
import analysis.dataflow.interprocedural.pdg.serialization.PDGNodeClassName;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.types.TypeReference;

/**
 * Node created for a specific call graph node which represents a procedure and
 * context
 */
public class ProcedurePDGNode extends PDGNode {

    /**
     * method and context this node was created for
     */
    private final CGNode n;

    /**
     * Create the node with the given type in the code and context given by the call graph node, <code>n</code>.
     *
     * @param description human readable description of the node, may later be changed
     *
     * @param type type of expression node being created
     * @param n call graph node containing the code and context the node is created in
     * @param javaType the type of the expresssion represented by this node or null if there is none
     * @return PDG node of the given type created in the given call graph node
     */
    protected ProcedurePDGNode(String description, PDGNodeType type, CGNode n, TypeReference javaType) {
        super(description, type, javaType);
        this.n = n;
    }

    /**
     * call graph node containing the code and context the node is created in
     *
     * @return call graph node
     */
    public CGNode getCGNode() {
        return n;
    }

    @Override
    public String groupingName() {
        // XXX Need to separate the grouping name from the procedure name to allow searches on just the procedure name
        return PrettyPrinter.cgNodeString(n);
    }

    @Override
    public PDGNodeClassName getClassName() {
        return PDGNodeClassName.EXPR;
    }

    @Override
    public JSONObject toJSON() {
        // TODO "paramName" for function parameters
        // TODO "exception" for generated exception type
        // TODO "exitkey" for exit nodes
        JSONObject json = JSONUtil.toJSON(this);
        JSONUtil.addJSON(json, "code", PrettyPrinter.methodString(n.getMethod()));
        JSONUtil.addJSON(json, "isShortCircuit", false);
        if (getJavaType() != null) {
            JSONUtil.addJSON(json, "javatype", getJavaType().getName().toString());
        }
        return json;
    }

    @Override
    public String contextString() {
        return n.getContext().toString();
    }
}
