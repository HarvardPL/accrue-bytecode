package analysis.dataflow.interprocedural.nonnull;

import analysis.dataflow.AbstractValue;


public class NonNullAbsVal implements AbstractValue<NonNullAbsVal> {
    static NonNullAbsVal NOT_NULL = new NonNullAbsVal(true);
    static NonNullAbsVal MAY_BE_NULL = new NonNullAbsVal(false);

    private final boolean notnull;

    private NonNullAbsVal(boolean notnull) {
        this.notnull = notnull;
    }
    
    /**
     * True if this abstract value represents an object that is definitely not null
     * 
     * @return true if definitely not null, false if may be null
     */
    public boolean isNonnull() {
        return notnull;
    }

    @Override
    public boolean leq(NonNullAbsVal that) {
        return this.isNonnull() || !that.isNonnull();
    }

    @Override
    public boolean isBottom() {
        return this == NOT_NULL;
    }

    @Override
    public NonNullAbsVal join(NonNullAbsVal that) {
        if (this.isNonnull()) return that;
        return this;
    }
}
