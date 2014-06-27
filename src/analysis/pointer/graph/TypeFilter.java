package analysis.pointer.graph;

import java.util.Set;

import types.TypeRepository;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.TypeReference;

public class TypeFilter {
    public final IClass isType;
    public final Set<IClass> notTypes;


    /**
     * Create a filter which matches one type and does not match a set of types
     * 
     * @param isType
     *            the filte matches only subtypes of this class, if this is null then no filtering will be done and
     *            everything matches
     * @param notTypes
     *            types to filter out
     */
    public TypeFilter(IClass isType, Set<IClass> notTypes) {
        this.isType = isType;
        this.notTypes = notTypes;
    }

    public TypeFilter(IClass isType) {
        this(isType, null);
    }

    public TypeFilter(TypeReference isType, Set<IClass> notTypes) {
        this(AnalysisUtil.getClassHierarchy().lookupClass(isType), notTypes);
    }

    public TypeFilter(TypeReference isType) {
        this(AnalysisUtil.getClassHierarchy().lookupClass(isType));
    }

    public boolean satisfies(IClass concreteType) {
        if (isAssignableFrom(isType, concreteType)) {
            if (notTypes != null) {
                for (IClass nt : notTypes) {
                    if (isAssignableFrom(nt, concreteType)) {
                        // it's assignable to a not type...
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    private boolean isAssignableFrom(IClass c1, IClass c2) {
        if (c1 == null) {
            // Null indicates no filter
            return true;
        }

        if (notTypes == null) {
            return AnalysisUtil.getClassHierarchy().isAssignableFrom(c1, c2);
        }
        // use caching version instead, since notTypes are
        // used for exceptions, and it's worth caching them.
        return TypeRepository.isAssignableFrom(c1, c2);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((isType == null) ? 0 : isType.hashCode());
        result = prime * result + ((notTypes == null) ? 0 : notTypes.hashCode());
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
        if (!(obj instanceof TypeFilter)) {
            return false;
        }
        TypeFilter other = (TypeFilter) obj;
        if (isType == null) {
            if (other.isType != null) {
                return false;
            }
        }
        else if (!isType.equals(other.isType)) {
            return false;
        }
        if (notTypes == null) {
            if (other.notTypes != null) {
                return false;
            }
        }
        else if (!notTypes.equals(other.notTypes)) {
            return false;
        }
        return true;
    }
}
