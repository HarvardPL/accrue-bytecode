package analysis.pointer.analyses;

import java.util.Iterator;

import analysis.StringAndReflectiveUtil;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.Pair;

public class ConstructorInstanceKey implements WrapperInstanceKey {

    private static final IClass JavaLangConstructorIClass = StringAndReflectiveUtil.getIClass("Ljava/lang/reflect/Constructor");
    private final InstanceKey innerIK;

    /* you'll want to enrich this with some way to refer to a particular constructor.
     * Unfortunately, WALA doesn't expose something like an `IConstructor` so you'll
     * have to invent your own representation.
     */

    /* factories */

    public static ConstructorInstanceKey make(InstanceKey innerIK) {
        return new ConstructorInstanceKey(innerIK);
    }

    /* constructors */
    public ConstructorInstanceKey(InstanceKey innerIK) {
        this.innerIK = innerIK;
    }

    /* accessors */

    @Override
    public InstanceKey getInnerIK() {
        return this.innerIK;
    }

    @Override
    public IClass getConcreteType() {
        return JavaLangConstructorIClass;
    }

    @Override
    public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
        throw new RuntimeException("Unimplemented: what the heck is this?");
    }

    @Override
    public String toString() {
        return "";
    }

}
