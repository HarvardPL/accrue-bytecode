package analysis.dataflow.interprocedural.nonnull;

import analysis.dataflow.AbstractValue;


public class NonNullAbsVal implements AbstractValue<NonNullAbsVal> {
    static NonNullAbsVal NOT_NULL = new NonNullAbsVal(true);
    static NonNullAbsVal MAYBE_NULL = new NonNullAbsVal(false);

    private final boolean notnull;

    private NonNullAbsVal(boolean notnull) {
        this.notnull = notnull;
    }

    @Override
    public boolean leq(NonNullAbsVal that) {
        return this.notnull || !that.notnull;
    }

    @Override
    public boolean isBottom() {
        return notnull;
    }

    @Override
    public NonNullAbsVal join(NonNullAbsVal that) {
        if (this.notnull) return that;
        return this;
    }
}
