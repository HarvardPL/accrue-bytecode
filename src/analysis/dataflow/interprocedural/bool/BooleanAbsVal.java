package analysis.dataflow.interprocedural.bool;

import analysis.dataflow.util.AbstractValue;

/**
 * Complete boolean lattice
 * <p>
 * 
 * <pre>
 *  UNKNOWN
 *  |     |
 * TRUE  FALSE
 *   \   /
 *    NONE
 * </pre>
 */
public class BooleanAbsVal implements AbstractValue<BooleanAbsVal> {

    public static final BooleanAbsVal TRUE = new BooleanAbsVal(true);
    public static final BooleanAbsVal FALSE = new BooleanAbsVal(false);
    // Just here for completeness never needed in the analysis
    private static final BooleanAbsVal NONE = new BooleanAbsVal();
    public static final BooleanAbsVal UNKNOWN = new BooleanAbsVal();

    private final Boolean value;

    private BooleanAbsVal(Boolean value) {
        this.value = value;
    }

    /**
     * Get the abstract value for the concrete boolean value, <code>b</code>
     * 
     * @param b
     *            boolean value
     * @return Abstract value
     */
    public static BooleanAbsVal forBoolean(boolean b) {
        if (b) {
            return TRUE;
        }
        return FALSE;
    }

    public BooleanAbsVal() {
        this.value = null;
    }

    @Override
    public boolean leq(BooleanAbsVal that) {
        if (this == that) {
            // FF TT UU NN
            return true;
        }
        if (this == NONE) {
            // NF NT NU
            return true;
        }
        if (that == UNKNOWN) {
            // FU TU
            return true;
        }

        // FT FN
        // TF TN
        // UF UT UN
        return false;
    }

    @Override
    public boolean isBottom() {
        return this == NONE;
    }

    @Override
    public BooleanAbsVal join(BooleanAbsVal that) {
        if (this == that) {
            // FF TT UU NN
            return this;
        }

        if (this == NONE) {
            // NF NT
            return that;
        }
        if (that == NONE) {
            // FN TN
            return this;
        }

        // FU TU NU
        // UF UT UN
        // FT TF
        return UNKNOWN;
    }

    /**
     * If this boolean is the constant <code>true</code> or <code>false</code> then return that value, otherwise null
     * 
     * @return boolean constant or null if this abstract value is not constant
     */
    public Boolean getValue() {
        return value;
    }

    public static BooleanAbsVal and(BooleanAbsVal left, BooleanAbsVal right) {
        assert left != null;
        assert right != null;
        if (left == UNKNOWN || right == UNKNOWN) {
            return UNKNOWN;
        }

        return forBoolean(left.getValue() && right.getValue());
    }

    public static BooleanAbsVal or(BooleanAbsVal left, BooleanAbsVal right) {
        assert left != null;
        assert right != null;
        if (left == UNKNOWN || right == UNKNOWN) {
            return UNKNOWN;
        }

        return forBoolean(left.getValue() || right.getValue());
    }

    public static BooleanAbsVal xor(BooleanAbsVal left, BooleanAbsVal right) {
        assert left != null;
        assert right != null;
        if (left == UNKNOWN || right == UNKNOWN) {
            return UNKNOWN;
        }

        return forBoolean(left.getValue() ^ right.getValue());
    }
}
