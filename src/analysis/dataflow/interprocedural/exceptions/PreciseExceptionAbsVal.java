package analysis.dataflow.interprocedural.exceptions;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.dataflow.util.AbstractValue;

import com.ibm.wala.types.TypeReference;

/**
 * Abstract value for the precise exception analysis. This consists of a set of
 * exception types.
 * <p>
 * It is sound to use this for a data-flow analysis since the types of
 * exceptions mentioned by a program is finite, so the lattice of abstract
 * values is of finite height.
 */
public class PreciseExceptionAbsVal implements AbstractValue<PreciseExceptionAbsVal> {

    /**
     * Empty set of exceptions
     */
    public static final PreciseExceptionAbsVal EMPTY = new PreciseExceptionAbsVal(
            Collections.<TypeReference> emptySet());
    /**
     * Set of exceptions this abstract value represents
     */
    private final Set<TypeReference> throwables;

    public PreciseExceptionAbsVal(Set<TypeReference> throwables) {
        this.throwables = Collections.unmodifiableSet(throwables);
    }

    @Override
    public boolean leq(PreciseExceptionAbsVal that) {
        return that.throwables.containsAll(throwables);
    }

    @Override
    public boolean isBottom() {
        return throwables.isEmpty();
    }

    @Override
    public PreciseExceptionAbsVal join(PreciseExceptionAbsVal that) {
        if (that == null || that.isBottom()) {
            return this;
        }
        
        if (this.isBottom()) {
            return that;
        }
        
        Set<TypeReference> union = new LinkedHashSet<>(this.getThrowables());
        union.addAll(that.getThrowables());
        return new PreciseExceptionAbsVal(union);
    }

    /**
     * Get the set of {@link Throwable}s represented by this abstract value
     * 
     * @return set of type references
     */
    public Set<TypeReference> getThrowables() {
        return throwables;
    }
    
    @Override
    public String toString() {
        if (throwables.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        Iterator<TypeReference> iter = throwables.iterator();
        TypeReference t;
        if (iter.hasNext()) {
            t = iter.next();
            sb.append(PrettyPrinter.parseType(t));
        }
        while (iter.hasNext()) {
            t = iter.next();
            sb.append(", " + PrettyPrinter.parseType(t));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * True if the set of exceptions represented by this value is empty
     * 
     * @return true if this represents the empty set of exceptions
     */
    public boolean isEmpty() {
        return throwables.isEmpty();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((throwables == null) ? 0 : throwables.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PreciseExceptionAbsVal other = (PreciseExceptionAbsVal) obj;
        if (throwables == null) {
            if (other.throwables != null)
                return false;
        } else if (!throwables.equals(other.throwables))
            return false;
        return true;
    }
}
