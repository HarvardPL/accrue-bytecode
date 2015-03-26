package analysis.pointer.analyses;

import java.util.Iterator;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.Pair;

public class StringInstanceKey implements InstanceKey {

    private final AString shat;
    private final InstanceKey innerIK;

    /* factories */

    public static StringInstanceKey make(AString shat, InstanceKey innerIK) {
        return new StringInstanceKey(shat, innerIK);
    }

    /* constructors */

    public StringInstanceKey(AString shat, InstanceKey innerIK) {
        this.shat = shat;
        this.innerIK = innerIK;
    }

    /* accessors */

    public InstanceKey getInnerIK() {
        return this.innerIK;
    }

    public AString getAString() {
        return this.shat;
    }

    @Override
    public IClass getConcreteType() {
        return innerIK.getConcreteType();
    }

    @Override
    public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
        throw new RuntimeException("Unimplemented: what the heck is this?");
    }

    @Override
    public String toString() {
        return "SIK(" + this.shat + ")";
    }

}
