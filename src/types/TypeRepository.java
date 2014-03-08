package types;

import java.util.LinkedHashMap;
import java.util.Map;

import com.ibm.wala.analysis.typeInference.TypeInference;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

/**
 * Computes on-demand and stores type information
 */
public class TypeRepository {

    private static final Map<IR, TypeInference> types = new LinkedHashMap<>();

    private static TypeInference getTypeInformation(IR ir) {
        TypeInference ti = types.get(ir);
        if (ti == null) {
            TypeInference.make(ir, true);
            types.put(ir, ti);
        }
        return ti;
    }

    public static TypeReference getType(int valNum, IR ir) {
        TypeInference ti = getTypeInformation(ir);
        return ti.getType(valNum).getTypeReference();
    }

}
