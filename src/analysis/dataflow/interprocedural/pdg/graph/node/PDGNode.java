package analysis.dataflow.interprocedural.pdg.graph.node;

import analysis.dataflow.util.AbstractValue;

public class PDGNode implements AbstractValue<PDGNode> {

    @Override
    public boolean leq(PDGNode that) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean isBottom() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public PDGNode join(PDGNode that) {
        // TODO Auto-generated method stub
        return null;
    }

}
