package analysis.pointer.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.TypeReference;

public class TypeFilter {

    public static final TypeFilter IMPOSSIBLE =
            new TypeFilter(AnalysisUtil.getErrorClass(),
                           Collections.singleton(AnalysisUtil.getErrorClass())) {
                @Override
                public boolean satisfies(IClass concreteType) {
                    return false;
                }
            };

    /*
     * Only one of isType and isTypes should be non-null.
     */
    public final IClass isType;
    public final Set<IClass> isTypes;
    public final Set<IClass> notTypes;

    private TypeFilter(IClass isType, Set<IClass> notTypes) {
        this.isType = isType;
        isTypes = null;
        this.notTypes = simplifyNotTypes(notTypes);
    }

    private TypeFilter(Set<IClass> isTypes, Set<IClass> notTypes) {
        isType = null;
        this.isTypes = isTypes;
        this.notTypes = simplifyNotTypes(notTypes);
    }

    private static Set<IClass> simplifyNotTypes(Set<IClass> notTypes) {
        if (notTypes == null || notTypes.isEmpty()) {
            return notTypes;
        }
        Set<IClass> toRemove = new HashSet<>();
        for (IClass t1 : notTypes) {
            for (IClass t2 : notTypes) {
                if (t1 == t2) continue;
                if (TypeRepository.isAssignableFrom(t1, t2)) {
                    // t1 is a supertype of t2, so we can drop t2
                    toRemove.add(t2);
                }
                else if (TypeRepository.isAssignableFrom(t2, t1)) {
                    // t2 is a supertype of t1, so we can drop t1
                    toRemove.add(t1);
                }
            }
        }
        if (toRemove.isEmpty()) {
            return notTypes;
        }
        Set<IClass> newNotTypes = new LinkedHashSet<>(notTypes);
        newNotTypes.removeAll(toRemove);
        return newNotTypes;
    }

    private Set<IClass> isTypesAsSet() {
        if (isTypes != null) {
            return isTypes;
        }
        return Collections.singleton(isType);

    }

    public boolean satisfies(IClass concreteType) {
        if (isType != null && isAssignableFrom(isType, concreteType)
                || isTypes != null && allAssignableFrom(isTypes, concreteType)) {
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
    public final int hashCode() {
        return super.hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public String toString() {
        return "TypeFilter [isType=" + (isType == null ? isTypes : isType)
                + ", notTypes=" + notTypes + "]";
    }

    private static Map<Set<TypeFilter>, TypeFilter> cachedCompose =
            new HashMap<>();

    public static TypeFilter compose(TypeFilter f1, TypeFilter f2) {
        if (f1 == null) {
            return f2;
        }
        if (f2 == null) {
            return f1;
        }

        if (f1.equals(f2)) {
            return f1;
        }
        if (f1.equals(IMPOSSIBLE) || f2.equals(IMPOSSIBLE)) {
            return IMPOSSIBLE;
        }

        // two non null filters
        // cache the results
        Set<TypeFilter> key = new HashSet<>();
        key.add(f1);
        key.add(f2);
        TypeFilter c = cachedCompose.get(key);
        if (c == null) {
            c = composeImpl(f1, f2);
//            if (c != IMPOSSIBLE) {
//                System.err.println("\nGot " + c + "\n        " + f1
//                        + "\n        " + f2);
//            }
            cachedCompose.put(key, c);
        }
        return c;
    }

    private static TypeFilter composeImpl(TypeFilter f1, TypeFilter f2) {
        Set<IClass> notTypes = null;
        if (f1.notTypes != null || f2.notTypes != null) {
            if (f1.notTypes != null && f2.notTypes != null) {
                notTypes = new LinkedHashSet<>();

                notTypes.addAll(f1.notTypes);
                notTypes.addAll(f2.notTypes);
            }
            else {
                notTypes = f1.notTypes != null ? f1.notTypes : f2.notTypes;
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
            TypeFilter tf = TypeFilter.create(isTypes, notTypes);
            return tf;
        }
        if (TypeRepository.isAssignableFrom(f1.isType, f2.isType)) {
            return TypeFilter.create(f2.isType, notTypes);
        }
        if (TypeRepository.isAssignableFrom(f2.isType, f1.isType)) {
            return TypeFilter.create(f1.isType, notTypes);
        }
        Set<IClass> isTypes = new LinkedHashSet<>();
        isTypes.add(f1.isType);
        isTypes.add(f2.isType);
        return TypeFilter.create(isTypes, notTypes);

    }

    public static TypeFilter create(IClass isType, Set<IClass> notTypes) {
        return memoize(new TypeFilter(isType, notTypes));
    }

    public static TypeFilter create(Set<IClass> isTypes, Set<IClass> notTypes) {
        return memoize(new TypeFilter(isTypes, notTypes));
    }

    public static TypeFilter create(IClass isType) {
        return create(isType, null);
    }

    public static TypeFilter create(TypeReference isType, Set<IClass> notTypes) {
        return create(AnalysisUtil.getClassHierarchy().lookupClass(isType),
                      notTypes);
    }

    public static TypeFilter create(TypeReference isType) {
        return create(AnalysisUtil.getClassHierarchy().lookupClass(isType));
    }

    private static final Map<TypeFilterWrapper, TypeFilter> memoized =
            new HashMap<>();

    static {
        // make sure we memoize IMPOSSIBLE
        memoize(IMPOSSIBLE);
    }

    private static TypeFilter memoize(TypeFilter filter) {
        TypeFilterWrapper w = new TypeFilterWrapper(filter);
        TypeFilter tf = memoized.get(w);
        if (tf == null) {
            tf = filter;
            if (isImpossible(filter)) {
                // the filter won't admit any instanceKeys...
                tf = IMPOSSIBLE;
            }
            memoized.put(w, tf);
        }
        return tf;
    }

    public static boolean isImpossible(TypeFilter filter) {
        // see if the isTypes contain incompatible classes (not interfaces)
        if (filter.isTypes != null) {
            for (IClass t1 : filter.isTypesAsSet()) {
                for (IClass t2 : filter.isTypesAsSet()) {
                    if (t1 == t2) continue;

                    if (!t1.isInterface()
                            && !t2.isInterface()
                            && !(TypeRepository.isAssignableFrom(t1, t2) || TypeRepository.isAssignableFrom(t2,
                                                                                                            t1))) {
                        // t1 and t2 are both non-interfaces (classes or arrays), and t1 is not a superclass of t2, nor vice versa.
                        return true;
                    }

                    if (t1.isInterface()
                            && t2.isArrayClass()
                            && !(t1.equals(AnalysisUtil.getCloneableInterface()) || t1.equals(AnalysisUtil.getSerializableInterface()))) {
                        // t2 is an array, and t1 is an interface that an array can't implement
                        return true;
                    }
                    if (t2.isInterface()
                            && t1.isArrayClass()
                            && !(t2.equals(AnalysisUtil.getCloneableInterface()) || t2.equals(AnalysisUtil.getSerializableInterface()))) {
                        // t1 is an array, and t2 is an interface that an array can't implement
                        return true;
                    }
                }
            }
        }

        // see if the notTypes contains one of the isTypes (or a superclass thereof)
        if (filter.notTypes != null) {
            for (IClass notT : filter.notTypes) {
                for (IClass isT : filter.isTypesAsSet()) {
                    if (TypeRepository.isAssignableFrom(notT, isT)) {
                        // notT is a supertype of isT, meaning that no type can be both
                        // a subtype of isT and not a subtype of notT.
                        // The filter is impossible!
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static class TypeFilterWrapper {
        private final TypeFilter filter;

        TypeFilterWrapper(TypeFilter filter) {
            this.filter = filter;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result =
                    prime
                            * result
                            + (filter.isType == null
                                    ? 0 : filter.isType.hashCode());
            result =
                    prime
                            * result
                            + (filter.isTypes == null
                                    ? 0 : filter.isTypes.hashCode());
            result =
                    prime
                            * result
                            + (filter.notTypes == null
                                    ? 0 : filter.notTypes.hashCode());
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
            if (!(obj instanceof TypeFilterWrapper)) {
                return false;
            }
            TypeFilter other = ((TypeFilterWrapper) obj).filter;
            if (filter.isType == null) {
                if (other.isType != null) {
                    return false;
                }
            }
            else if (!filter.isType.equals(other.isType)) {
                return false;
            }
            if (filter.isTypes == null) {
                if (other.isTypes != null) {
                    return false;
                }
            }
            else if (!filter.isTypes.equals(other.isTypes)) {
                return false;
            }
            if (filter.notTypes == null) {
                if (other.notTypes != null) {
                    return false;
                }
            }
            else if (!filter.notTypes.equals(other.notTypes)) {
                return false;
            }
            return true;
        }

    }
}
