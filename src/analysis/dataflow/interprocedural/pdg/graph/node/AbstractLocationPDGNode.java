package analysis.dataflow.interprocedural.pdg.graph.node;

import analysis.dataflow.util.AbstractLocation;

public class AbstractLocationPDGNode extends PDGNode {

    private final AbstractLocation loc;

    protected AbstractLocationPDGNode(AbstractLocation loc) {
        super("LOC " + loc.toString(), PDGNodeType.ABSTRACT_LOCATION);
        this.loc = loc;
    }
    
    public AbstractLocation getLocation() {
        return loc;
    }
}
