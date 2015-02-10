package analysis.dataflow.interprocedural.interval;

import analysis.dataflow.util.AbstractValue;

public class IntervalAbsVal implements AbstractValue<IntervalAbsVal> {

    protected double min;
    protected double max;
    protected static final IntervalAbsVal TOP_ELEMENT = new IntervalAbsVal(Double.NEGATIVE_INFINITY,
                                                                           Double.POSITIVE_INFINITY);
    protected static final IntervalAbsVal BOTTOM_ELEMENT = new IntervalAbsVal(Double.NaN, Double.NaN);
    protected static final IntervalAbsVal POSITIVE = new IntervalAbsVal(0., Double.POSITIVE_INFINITY);
    protected static final IntervalAbsVal NEGATIVE = new IntervalAbsVal(Double.NEGATIVE_INFINITY, 0.);

    public IntervalAbsVal(double min, double max) {
        assert min <= max;
        this.min = min;
        this.max = max;
    }


    @Override
    public boolean leq(IntervalAbsVal that) {
        if (this == BOTTOM_ELEMENT || that == TOP_ELEMENT) {
            return true;
        }
        return (this.min > that.min) && (this.max < that.max);
    }

    @Override
    public boolean isBottom() {
        return this == BOTTOM_ELEMENT;
    }

    @Override
    public IntervalAbsVal join(IntervalAbsVal that) {
        if (this == TOP_ELEMENT || that == TOP_ELEMENT) {
            return TOP_ELEMENT;
        }
        else if (this == BOTTOM_ELEMENT) {
            return that;
        }
        else if (that == BOTTOM_ELEMENT || that == null) {
            return this;
        }

        double minBoundary = this.min < that.min ? this.min : that.min;
        double maxBoundary = this.max > that.max ? this.max : that.max;
        return new IntervalAbsVal(minBoundary, maxBoundary);
    }

    public boolean containsZero() {
        return min <= 0. && max >= 0.;
    }

}
