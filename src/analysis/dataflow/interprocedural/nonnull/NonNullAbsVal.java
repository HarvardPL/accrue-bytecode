package analysis.dataflow.interprocedural.nonnull;

import analysis.dataflow.util.TwoElementSemiLattice;

public class NonNullAbsVal extends TwoElementSemiLattice<NonNullAbsVal> {
    protected static final NonNullAbsVal NON_NULL = new NonNullAbsVal(true);
    protected static final NonNullAbsVal MAY_BE_NULL = new NonNullAbsVal(false);

    private final boolean notnull;

    private NonNullAbsVal(boolean notnull) {
        this.notnull = notnull;
    }

    /**
     * True if this abstract value represents an object that is definitely not
     * null
     * 
     * @return true if definitely not null, false if may be null
     */
    public boolean isNonnull() {
        return notnull;
    }

    @Override
    public NonNullAbsVal getBottom() {
        return NON_NULL;
    }

    @Override
    public String toString() {
        return this == NON_NULL ? "NON_NULL" : "MAY_BE_NULL";
    }

    @Override
    protected NonNullAbsVal getTop() {
        return MAY_BE_NULL;
    }
}
