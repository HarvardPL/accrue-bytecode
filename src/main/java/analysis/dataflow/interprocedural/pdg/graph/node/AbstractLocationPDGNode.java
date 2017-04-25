package analysis.dataflow.interprocedural.pdg.graph.node;

import org.json.JSONObject;

import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.pdg.serialization.JSONUtil;
import analysis.dataflow.interprocedural.pdg.serialization.PDGNodeClassName;
import analysis.dataflow.util.AbstractLocation;

/**
 * The representation in a program dependence graph of an abstract heap location.
 */
public class AbstractLocationPDGNode extends PDGNode {

    private final AbstractLocation loc;

    protected AbstractLocationPDGNode(AbstractLocation loc) {
        super("LOC " + loc.toString(), PDGNodeType.ABSTRACT_LOCATION, loc.getJavaType());
        this.loc = loc;
    }

    public AbstractLocation getLocation() {
        return loc;
    }

    @Override
    public PDGNodeClassName getClassName() {
        return PDGNodeClassName.ABSTRACT_LOCATION;
    }

    @Override
    public JSONObject toJSON() {
        JSONObject json = JSONUtil.toJSON(this);
        JSONUtil.addJSON(json, "location", getLocation().toString());
        JSONUtil.addJSON(json, "javatype", getJavaType().getName().toString());
        return json;
    }

    @Override
    public String groupingName() {
        return PrettyPrinter.getCanonical("HEAP");
    }

    @Override
    public String contextString() {
        return String.valueOf(getLocation().getReceiverContext());
    }
}
