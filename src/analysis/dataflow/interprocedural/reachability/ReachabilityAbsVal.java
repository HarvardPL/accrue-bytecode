package analysis.dataflow.interprocedural.reachability;

import analysis.dataflow.util.TwoElementSemiLattice;

/**
 * Whether a basic block or procedure is reachable on a particular edge
 */
public class ReachabilityAbsVal extends TwoElementSemiLattice<ReachabilityAbsVal> {

    protected static final ReachabilityAbsVal REACHABLE = new ReachabilityAbsVal();
    protected static final ReachabilityAbsVal UNREACHABLE = new ReachabilityAbsVal();

    private ReachabilityAbsVal() {
    }

    @Override
    public ReachabilityAbsVal getBottom() {
        return UNREACHABLE;
    }

    @Override
    protected ReachabilityAbsVal getTop() {
        return REACHABLE;
    }

    /**
     * Whether this edge is unreachable
     * 
     * @return true if this edge is unreachable
     */
    public boolean isUnreachable() {
        return this == UNREACHABLE;
    }

    @Override
    public String toString() {
        return this == REACHABLE ? "REACHABLE" : "UNREACHABLE";
    }
}
