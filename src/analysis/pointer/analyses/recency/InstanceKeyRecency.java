package analysis.pointer.analyses.recency;

import java.util.Iterator;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.Pair;

public class InstanceKeyRecency implements InstanceKey {
    private InstanceKey ik;
    private boolean recent;

    public InstanceKeyRecency(InstanceKey ik, boolean recent) {
        this.ik = ik;
        this.recent = recent;
    }

    public boolean isRecent() {
        return recent;
    }

    public InstanceKey baseInstanceKey() {
        return ik;
    }

    @Override
    public boolean equals(Object o) {
        InstanceKeyRecency that = (InstanceKeyRecency) o;
        return (this.ik.equals(that.ik) && this.recent == that.recent);
    }

    @Override
    public int hashCode() {
        return ik.hashCode() ^ (recent ? 7867 : -56);
    }

    @Override
    public String toString() {
        return "<" + ik.toString() + "," + recent + ">";
    }

    @Override
    public IClass getConcreteType() {
        return ik.getConcreteType();
    }

    @Override
    public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
        return ik.getCreationSites(CG);
    }

}
