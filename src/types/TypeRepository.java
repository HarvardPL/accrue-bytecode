package types;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.analysis.typeInference.TypeAbstraction;
import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Computes on-demand and stores type information
 */
public class TypeRepository {

    private static final Map<IR, TypeInference> types = new LinkedHashMap<>();
    private static final Map<IR, Set<Integer>> exceptions = new LinkedHashMap<>();

    private static TypeInference getTypeInformation(IR ir) {
        TypeInference ti = types.get(ir);
        if (ti == null) {
            ti = TypeInference.make(ir, true);
            types.put(ir, ti);
//            System.err.println("Types for " + ir.getMethod().getSignature());
//            printTypes(ir, ti);
        }
        return ti;
    }
    
    public static void printTypes(IR ir, TypeInference ti) {
        for (int valNum = 0; valNum <= ir.getSymbolTable().getMaxValueNumber(); valNum++) {
            String type;
            try {
                type =  ti.getType(valNum).toString(); 
            } catch (NullPointerException|AssertionError e) {
                type = "null";
            }
            
            if (getExceptions(ir).contains(valNum)){
                type = "<Primordial,Ljava/lang/Throwable>";
            }
            
            System.err.println(ir.getSymbolTable().getValueString(valNum) + " :: " + type);
        }      
    }

    public static TypeReference getType(int valNum, IR ir) {
        if (ir.getSymbolTable().isNullConstant(valNum)) {
            return TypeReference.Null;
        }
        TypeInference ti = getTypeInformation(ir);
        TypeReference tr = ti.getType(valNum).getTypeReference();
        if (tr == null) {
            if (ti.getType(valNum) == TypeAbstraction.TOP && getExceptions(ir).contains(valNum)) {
                // This is an unknown exception/error type
                return TypeReference.JavaLangThrowable;
            }
            System.err.println("No type for " + ir.getSymbolTable().getValueString(valNum) + " in " + ir.getMethod().getSignature());
        }
        return tr;
    }

    private static Set<Integer> getExceptions(IR ir) {
        Set<Integer> exs = exceptions.get(ir);
        if (exs == null) {
            exs = new LinkedHashSet<>();
            for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                for (SSAInstruction ins : bb) {
                    if (ins instanceof SSAInvokeInstruction) {
                        exs.add(((SSAInvokeInstruction) ins).getException());
                    }
                }
            }
            exceptions.put(ir, exs);
        }
        return exs;
    }
}
