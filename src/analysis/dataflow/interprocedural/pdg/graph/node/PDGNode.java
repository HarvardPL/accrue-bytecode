package analysis.dataflow.interprocedural.pdg.graph.node;

import analysis.dataflow.util.AbstractValue;

public abstract class PDGNode implements AbstractValue<PDGNode> {

    @Override
    public boolean leq(PDGNode that) {
        return this.equals(that);
    }

    @Override
    public boolean isBottom() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public PDGNode join(PDGNode that) {
        if (that == null || that == this || this.equals(that)) {
            return this;
        }
        assert false : "Cannot join two unequal PDG nodes " + this + " and " + that;
        throw new RuntimeException("Cannot join two unequal PDG nodes " + this + " and " + that);
    }
    
    public boolean isMergeNode() {
        // TODO Auto-generated method stub
        return false;
    }

}
