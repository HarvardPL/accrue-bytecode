package analysis.pointer.duplicates;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.InstructionType;

import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;

public class FindDuplicates {

    private final Map<Integer, Set<Integer>> duplicates = new LinkedHashMap<>();

    public Map<Integer, Set<Integer>> findDuplicates(IR ir) {

        Iterator<SSAInstruction> ins1 = ir.iterateNormalInstructions();
        Iterator<SSAInstruction> ins2 = ir.iterateNormalInstructions();

        // Set of instructions that have been tested against all others or already have a duplicate
        Set<SSAInstruction> handled = new HashSet<>();
        
        while (ins1.hasNext()) {
            SSAInstruction i1 = ins1.next();
            if (handled.contains(i1)) {
                continue;
            }
            handled.add(i1);
            while (ins2.hasNext()) {
                SSAInstruction i2 = ins2.next();
                if (!handled.contains(i2) && differentButEquivalent(i1, i2)) {
                    handled.add(i2);
                    Set<Integer> dups = duplicates.get(i1);
                    if (dups == null) {
                        dups = new LinkedHashSet<>();
                        duplicates.put(i1.getDef(), dups);
                    }
                    dups.add(i2.getDef());
                }
            }
        }

        return duplicates;
    }

    /**
     * Are the instructions different and the right-hand sides of the instructions produce the same points-to sets?
     * 
     * @param i1
     *            first instruction
     * @param i2
     *            second instruction
     * @return true if the instructions are different, but produce the same points to set for the variable defined by
     *         the instructions
     */
    private boolean differentButEquivalent(SSAInstruction i1, SSAInstruction i2) {
        if (i1.getClass() != i2.getClass()) {
            // Not the same type
            return false;
        }

        if (i1 == i2) {
            // Same instruction
            return false;
        }

        switch (InstructionType.forInstruction(i1)) {
        case ARRAY_LOAD:
            return equivalentArrayLoad((SSAArrayLoadInstruction) i1, (SSAArrayLoadInstruction) i1);
        case CHECK_CAST:
            return equivalentCheckCast((SSACheckCastInstruction) i1, (SSACheckCastInstruction) i1);
            // TODO if flow sensitive then check that there are not "puts" in between "gets"
        case GET_FIELD:
            return equivalentGetField((SSAGetInstruction) i1, (SSAGetInstruction) i1);
        case GET_STATIC:
            return equivalentGetStatic((SSAGetInstruction) i1, (SSAGetInstruction) i1);
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            return false;
            // TODO should invocations be copies?
            // return equivalentInvoke((SSAInvokeInstruction) i1, (SSAInvokeInstruction) i1);
        case PHI:
            return equivalentPhi((SSAPhiInstruction) i1, (SSAPhiInstruction) i1);
            // Different allocations may have different points to sets (depends on the heap abstraction)
            // Load metadata is loadClass so is like an allocation
        case LOAD_METADATA:
        case NEW_ARRAY:
        case NEW_OBJECT:
            return false;
            // result in primitives
        case ARRAY_LENGTH: // primitive op with generated exception
        case BINARY_OP: // primitive op
        case BINARY_OP_EX: // primitive op with generated exception
        case COMPARISON: // primitive op
        case CONVERSION: // primitive op
        case INSTANCE_OF: // results in a primitive
        case UNARY_NEG_OP: // primitive op
            return false;
            // Instructions with no assignment
        case ARRAY_STORE:
        case CONDITIONAL_BRANCH:
        case GET_CAUGHT_EXCEPTION:
        case GOTO:
        case MONITOR:
        case PUT_FIELD:
        case PUT_STATIC:
        case RETURN:
        case SWITCH:
        case THROW:
            return false;
        }
        throw new RuntimeException("Unknown instruction type: " + i1.getClass().getCanonicalName());
    }

    private boolean equivalentPhi(SSAPhiInstruction i1, SSAPhiInstruction i2) {
        // TODO could be expensive if there are a lot of uses
        boolean[] i2Found = new boolean[i2.getNumberOfUses()];
        for (int j = 0; j < i1.getNumberOfUses(); j++) {
            int use1 = i1.getUse(j);
            boolean hasEquiv = false;
            for (int k = 0; k < i2.getNumberOfUses(); k++) {
                int use2 = i2.getUse(k);
                if (equivalentVariables(use1, use2)) {
                    hasEquiv = true;
                    i2Found[k] = true;
                    continue;
                }
            }

            if (!hasEquiv) {
                return false;
            }
        }

        for (int k = 0; k < i2.getNumberOfUses(); k++) {
            if (i2Found[k]) {
                // We already found an equivalent use when going through uses in i1
                continue;
            }

            int use2 = i2.getUse(k);
            boolean hasEquiv = false;
            for (int j = 0; j < i1.getNumberOfUses(); j++) {
                int use1 = i1.getUse(j);
                if (equivalentVariables(use1, use2)) {
                    hasEquiv = true;
                    continue;
                }
            }

            if (!hasEquiv) {
                return false;
            }
        }

        // If we reach this point then every element of i1 has an equivalent element in i2 and vice-versa, thus the
        // points-to sets for the defs will be identical
        return true;
    }

    @SuppressWarnings("unused")
    private boolean equivalentInvoke(SSAInvokeInstruction i1, SSAInvokeInstruction i2) {
        // Same method
        if (!i1.getDeclaredTarget().equals(i2.getDeclaredTarget())) {
            return false;
        }

        // Same receiver and same arguments
        for (int j = 0; j < i1.getNumberOfUses(); j++) {
            if (!equivalentVariables(i1.getUse(j), i2.getUse(j))) {
                return false;
            }
        }

        // Same method with the same arguments
        return true;
    }

    private static boolean equivalentGetStatic(SSAGetInstruction i1, SSAGetInstruction i2) {
        return i1.getDeclaredField().equals(i2.getDeclaredField());
    }

    private boolean equivalentGetField(SSAGetInstruction i1, SSAGetInstruction i2) {
        if (!equivalentVariables(i1.getRef(), i2.getRef())) {
            return false;
        }
        return i1.getDeclaredField().equals(i2.getDeclaredField());
    }

    private boolean equivalentCheckCast(SSACheckCastInstruction i1, SSACheckCastInstruction i2) {
        if (!equivalentVariables(i1.getVal(), i2.getVal())) {
            return false;
        }
        return Arrays.equals(i1.getDeclaredResultTypes(), i2.getDeclaredResultTypes());
    }

    private boolean equivalentArrayLoad(SSAArrayLoadInstruction i1, SSAArrayLoadInstruction i2) {
        if (!equivalentVariables(i1.getArrayRef(), i2.getArrayRef())) {
            return false;
        }
        // We do not track array indices so all loads from the same array are equivalent
        return true;
    }

    @SuppressWarnings("static-method")
    private boolean equivalentVariables(int i1, int i2) {
        if (i1 == i2) {
            return true;
        }
        return false;
        // return duplicates.get(i1).contains(i2) || duplicates.get(i2).contains(i1);
    }
}
