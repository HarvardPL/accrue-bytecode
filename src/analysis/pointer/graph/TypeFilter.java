package analysis.pointer.graph;

import java.util.LinkedHashSet;
import java.util.Set;

import types.TypeRepository;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.TypeReference;

public class TypeFilter {
    /*
     * Only one of isType and isTypes should be non-null.
     */
    public final IClass isType;
    public final Set<IClass> isTypes;
    public final Set<IClass> notTypes;


    public TypeFilter(IClass isType, Set<IClass> notTypes) {
        this.isType = isType;
        this.isTypes = null;
        this.notTypes = notTypes;
    }

    public TypeFilter(Set<IClass> isTypes, Set<IClass> notTypes) {
        this.isType = null;
        this.isTypes = isTypes;
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
        if ((isType != null && isAssignableFrom(isType, concreteType))
                                        || (isTypes != null && allAssignableFrom(isTypes, concreteType))) {
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
        if (notTypes == null) {
            return AnalysisUtil.getClassHierarchy().isAssignableFrom(c1, c2);
        }
        // use caching version instead, since notTypes are
        // used for exceptions, and it's worth caching them.
        return TypeRepository.isAssignableFrom(c1, c2);
    }

    private boolean allAssignableFrom(Set<IClass> c1, IClass c2) {
        for (IClass ic : c1) {
            if (!isAssignableFrom(ic, c2)) {
                return false;
            }
        }
        return true;
    }


    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((isType == null) ? 0 : isType.hashCode());
        result = prime * result + ((isTypes == null) ? 0 : isTypes.hashCode());
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
        if (isTypes == null) {
            if (other.isTypes != null) {
                return false;
            }
        }
        else if (!isTypes.equals(other.isTypes)) {
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

    public static TypeFilter compose(TypeFilter f1, TypeFilter f2) {
        if (f1 == null) {
            return f2;
        }
        if (f2 == null) {
            return f1;
        }
        // two non null filters
        Set<IClass> notTypes = null;
        if (f1.notTypes != null || f2.notTypes != null) {
            notTypes = new LinkedHashSet<>();
            if (f1.notTypes != null) {
                notTypes.addAll(f1.notTypes);
            }
            if (f2.notTypes != null) {
                notTypes.addAll(f2.notTypes);
            }
        }

        if (f1.isType == null || f2.isType == null) {
            // at least one of them is a set...
            Set<IClass> isTypes = new LinkedHashSet<>();
            // XXX We should do a better job and make it a minimal set based on subtyping relations...
            if (f1.isType == null) {
                isTypes.addAll(f1.isTypes);
            }
            else {
                isTypes.add(f1.isType);
            }
            if (f2.isType == null) {
                isTypes.addAll(f2.isTypes);
            }
            else {
                isTypes.add(f2.isType);
            }
            return new TypeFilter(isTypes, notTypes);
        }
        if (TypeRepository.isAssignableFrom(f1.isType, f2.isType)) {
            return new TypeFilter(f2.isType, notTypes);
        }
        if (TypeRepository.isAssignableFrom(f2.isType, f1.isType)) {
            return new TypeFilter(f1.isType, notTypes);
        }
        Set<IClass> isTypes = new LinkedHashSet<>();
        isTypes.add(f1.isType);
        isTypes.add(f2.isType);
        return new TypeFilter(isTypes, notTypes);

    }
}
