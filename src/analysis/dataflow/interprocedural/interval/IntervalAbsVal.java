package analysis.dataflow.interprocedural.interval;

import analysis.dataflow.util.AbstractValue;

public class IntervalAbsVal implements AbstractValue<IntervalAbsVal> {

    protected double min;
    protected double max;

    private static final double BOUND = 10.0;

    protected static final IntervalAbsVal ZERO = new IntervalAbsVal(0., 0.);
    protected static final IntervalAbsVal TOP_ELEMENT = new IntervalAbsVal(Double.NEGATIVE_INFINITY,
                                                                           Double.POSITIVE_INFINITY);
    protected static final IntervalAbsVal BOTTOM_ELEMENT = new IntervalAbsVal(Double.NaN, Double.NaN);
    protected static final IntervalAbsVal POSITIVE = new IntervalAbsVal(0., Double.POSITIVE_INFINITY);
    protected static final IntervalAbsVal NEGATIVE = new IntervalAbsVal(Double.NEGATIVE_INFINITY, 0.);

    public IntervalAbsVal(double min, double max) {
        if (!Double.isNaN(min) && !Double.isNaN(max)) {
            assert min <= max : "min is " + min + " max is " + max;
        }
        this.min = min < (-1) * BOUND ? Double.NEGATIVE_INFINITY : min;
        this.max = max > BOUND ? Double.POSITIVE_INFINITY : max;
    }


    @Override
    public boolean leq(IntervalAbsVal that) {
        if (this == BOTTOM_ELEMENT || that == TOP_ELEMENT) {
            return true;
        }
        return (this.min >= that.min) && (this.max <= that.max);
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

    /**
     * Negation of the interval.
     */
    public IntervalAbsVal neg() {
        if (this == TOP_ELEMENT) {
            return TOP_ELEMENT;
        }
        return new IntervalAbsVal(-this.max, -this.min);
    }

    /**
     * Constrain to be less than a number.
     */
    public IntervalAbsVal le(double n) {
        // No prior information, add max constraint
        if (this == BOTTOM_ELEMENT) {
            return new IntervalAbsVal(Double.NEGATIVE_INFINITY, n);
        }
        if (n < this.min) {
            return BOTTOM_ELEMENT;
        }
        return new IntervalAbsVal(this.min, Math.min(this.max, n));
    }

    /**
     * Constrain to be greater than a number.
     */
    public IntervalAbsVal ge(double n) {
        // No prior information, add min constraint
        if (this == BOTTOM_ELEMENT) {
            return new IntervalAbsVal(n, Double.POSITIVE_INFINITY);
        }
        if (n > this.max) {
            return BOTTOM_ELEMENT;
        }
        return new IntervalAbsVal(Math.max(this.min, n), this.max);
    }

    @Override
    public String toString() {
        return "[" + min + "," + max + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        long temp;
        temp = Double.doubleToLongBits(max);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(min);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        IntervalAbsVal other = (IntervalAbsVal) obj;
        if (Double.doubleToLongBits(max) != Double.doubleToLongBits(other.max)) {
            return false;
        }
        if (Double.doubleToLongBits(min) != Double.doubleToLongBits(other.min)) {
            return false;
        }
        return true;
    }

}
