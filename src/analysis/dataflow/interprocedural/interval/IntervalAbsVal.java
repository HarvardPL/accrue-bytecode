package analysis.dataflow.interprocedural.interval;

import analysis.dataflow.util.AbstractValue;

public class IntervalAbsVal implements AbstractValue<IntervalAbsVal> {

    final Double min;
    final Double max;
    protected final boolean containsNaN;
    protected final boolean containsZero;

    private static final double BOUND = 10.0;

    static final IntervalAbsVal TOP_ELEMENT = new IntervalAbsVal(Double.NEGATIVE_INFINITY,
                                                                 Double.POSITIVE_INFINITY,
                                                                 true,
                                                                 true);

    static final IntervalAbsVal BOTTOM_ELEMENT = new IntervalAbsVal(false, false);

    public IntervalAbsVal(double min, double max, boolean containsNaN, boolean containsZero) {
        if (Double.isNaN(min) || Double.isNaN(max)) {
            throw new RuntimeException("NaN is not allowed as a max or min. " + max + " " + min);
        }
        this.min = min > BOUND || min < (-1) * BOUND ? Double.NEGATIVE_INFINITY : min;
        this.max = max > BOUND || max < (-1) * BOUND ? Double.POSITIVE_INFINITY : max;
        this.containsNaN = containsNaN;
        this.containsZero = containsZero;
    }

    /**
     * Create the bottom element
     */
    private IntervalAbsVal(boolean containsNaN, boolean containsZero) {
        this.min = null;
        this.max = null;
        this.containsNaN = containsNaN;
        this.containsZero = containsZero;
    }

    @Override
    public boolean leq(IntervalAbsVal that) {
        if (this == BOTTOM_ELEMENT || that == TOP_ELEMENT || (that != null && that.equals(TOP_ELEMENT))) {
            return true;
        }
        if (that == null || that == BOTTOM_ELEMENT) {
            return false;
        }
        return (this.min >= that.min) && (this.max <= that.max);
    }

    @Override
    public boolean isBottom() {
        // null is bottom
        return false;
    }

    @Override
    public IntervalAbsVal join(IntervalAbsVal that) {
        if (that == null || that == BOTTOM_ELEMENT) {
            return this;
        }
        if (this == BOTTOM_ELEMENT) {
            return that;
        }
        if (this == TOP_ELEMENT || that == TOP_ELEMENT || this.equals(TOP_ELEMENT) || that.equals(TOP_ELEMENT)) {
            return TOP_ELEMENT;
        }

        double minBoundary = Math.min(this.min, that.min);
        double maxBoundary = Math.max(this.max, that.max);
        return new IntervalAbsVal(minBoundary, maxBoundary, this.containsNaN || that.containsNaN, this.containsZero
                || that.containsZero);
    }

    public boolean containsZero() {
        return this.containsZero;
    }

    /**
     * Negation of the interval.
     */
    public IntervalAbsVal neg() {
        if (this == TOP_ELEMENT || this.equals(TOP_ELEMENT)) {
            return TOP_ELEMENT;
        }
        if (this == BOTTOM_ELEMENT) {
            return BOTTOM_ELEMENT;
        }
        return new IntervalAbsVal(-this.max, -this.min, this.containsNaN, this.containsZero);
    }

    /**
     * Return same interval but doesn't contain zero.
     */
    public IntervalAbsVal excludeZero() {
        return new IntervalAbsVal(this.min, this.max, this.containsNaN, false);
    }

    /**
     * Constrain to be less than a number.
     */
    public IntervalAbsVal lt(double n) {
        if (this == BOTTOM_ELEMENT) {
            // We didn't know anything, now we know that it is less than n
            return new IntervalAbsVal(Double.NEGATIVE_INFINITY, n, false, n > 0);
        }
        if (n <= this.min) {
            // Impossible constraint
            return BOTTOM_ELEMENT;
        }
        return new IntervalAbsVal(this.min, Math.min(this.max, n), this.containsNaN, (n > 0) && this.containsZero);
    }

    /**
     * Constrain to be less than or equal to a number.
     */
    public IntervalAbsVal lte(double n) {
        if (this == BOTTOM_ELEMENT) {
            // We didn't know anything, now we know that it is less than n
            return new IntervalAbsVal(Double.NEGATIVE_INFINITY, n, false, n >= 0);
        }
        if (n < this.min) {
            // Impossible constraint
            return BOTTOM_ELEMENT;
        }
        return new IntervalAbsVal(this.min, Math.min(this.max, n), this.containsNaN, (n >= 0) && this.containsZero);
    }

    /**
     * Constrain to be greater than a number.
     */
    public IntervalAbsVal gt(double n) {
        if (this == BOTTOM_ELEMENT) {
            // We didn't know anything, now we know that it is bigger than n
            return new IntervalAbsVal(n, Double.POSITIVE_INFINITY, false, n < 0);
        }
        if (n >= this.max) {
            // Impossible constraint
            return BOTTOM_ELEMENT;
        }
        return new IntervalAbsVal(Math.max(this.min, n), this.max, this.containsNaN, (n < 0) && this.containsZero);
    }

    /**
     * Constrain to be greater than a number.
     */
    public IntervalAbsVal gte(double n) {
        if (this == BOTTOM_ELEMENT) {
            // We didn't know anything, now we know that it is bigger than n
            return new IntervalAbsVal(n, Double.POSITIVE_INFINITY, false, n <= 0);
        }
        if (n > this.max) {
            // Impossible constraint
            return BOTTOM_ELEMENT;
        }
        return new IntervalAbsVal(Math.max(this.min, n), this.max, this.containsNaN, (n <= 0) && this.containsZero);
    }

    @Override
    public String toString() {
        return this == BOTTOM_ELEMENT ? "BOTTOM" : "[" + min + "," + max + "]" + " containsNaN? " + containsNaN;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.containsNaN ? 1231 : 1237);
        result = prime * result + ((this.max == null) ? 0 : this.max.hashCode());
        result = prime * result + ((this.min == null) ? 0 : this.min.hashCode());
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
        if (this.containsNaN != other.containsNaN) {
            return false;
        }
        if (this.max == null) {
            if (other.max != null) {
                return false;
            }
        }
        else if (!this.max.equals(other.max)) {
            return false;
        }
        if (this.min == null) {
            if (other.min != null) {
                return false;
            }
        }
        else if (!this.min.equals(other.min)) {
            return false;
        }
        return true;
    }

}
