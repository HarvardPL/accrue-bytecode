package analysis.pointer.analyses;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

import util.FiniteSet;
import util.optional.Optional;
import analysis.AnalysisUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.Pair;

public class ClassInstanceKey implements InstanceKey {

    private static final IClass JavaLangClassIClass = AnalysisUtil.getClassHierarchy()
                                                                  .lookupClass(TypeReference.JavaLangClass);
    private final FiniteSet<IClass> reflectedTypes;
    private final InstanceKey innerIK;

    /* factories */

    public static ClassInstanceKey makeTop(int maxSize, InstanceKey innerIK) {
        return new ClassInstanceKey(FiniteSet.<IClass> makeTop(maxSize), innerIK);
    }

    public static ClassInstanceKey makeBottom(int maxSize, InstanceKey innerIK) {
        return new ClassInstanceKey(FiniteSet.<IClass> makeBottom(maxSize), innerIK);
    }

    public static ClassInstanceKey makeSet(int maxSize, Collection<IClass> c, InstanceKey innerIK) {
        return new ClassInstanceKey(FiniteSet.makeFiniteSet(maxSize, c), innerIK);
    }

    public static ClassInstanceKey make(int maxSize, Optional<? extends Collection<IClass>> c, InstanceKey innerIK) {
        return new ClassInstanceKey(FiniteSet.make(maxSize, c), innerIK);
    }

    public static ClassInstanceKey make(FiniteSet<IClass> c, InstanceKey innerIK) {
        return new ClassInstanceKey(c, innerIK);
    }

    /* constructors */

    public ClassInstanceKey(FiniteSet<IClass> reflectedTypes, InstanceKey innerIK) {
        Optional<Set<IClass>> maybeTypes = reflectedTypes.maybeIterable();
        if (maybeTypes.isSome()) {
            for (IClass rType : maybeTypes.get()) {
                assert rType != null;
            }
        }
        this.reflectedTypes = reflectedTypes;
        this.innerIK = innerIK;
    }

    /* accessors */

    public FiniteSet<IClass> getReflectedType() {
        return this.reflectedTypes;
    }

    public InstanceKey getInnerIK() {
        return this.innerIK;
    }

    @Override
    public IClass getConcreteType() {
        return JavaLangClassIClass;
    }

    @Override
    public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
        throw new RuntimeException("Unimplemented: what the heck is this?");
    }

    @Override
    public String toString() {
        return "RIK(" + this.reflectedTypes.toString() + ")";
    }

}
