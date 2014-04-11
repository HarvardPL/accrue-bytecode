package analysis.dataflow.interprocedural.exceptions;

import java.util.LinkedHashSet;
import java.util.Set;

import com.ibm.wala.types.TypeReference;

import analysis.dataflow.AbstractValue;

public class PreciseExceptionAbsVal implements AbstractValue<PreciseExceptionAbsVal> {
    
    private final Set<TypeReference> throwables = new LinkedHashSet<>();

    @Override
    public boolean leq(PreciseExceptionAbsVal that) {
        // TODO Auto-generated method stub
        return that.throwables.contains(throwables);
    }

    @Override
    public boolean isBottom() {
        return throwables.isEmpty();
    }

    @Override
    public PreciseExceptionAbsVal join(PreciseExceptionAbsVal that) {
        // TODO Auto-generated method stub
        // union
        return null;
    }

}
