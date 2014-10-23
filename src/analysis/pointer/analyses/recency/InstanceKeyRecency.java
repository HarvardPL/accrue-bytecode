package analysis.pointer.analyses.recency;

import java.util.Iterator;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.util.collections.Pair;

public class InstanceKeyRecency implements InstanceKey {
    private final InstanceKey ik;
    private final boolean recent;
    private final boolean isTrackingMostRecent;

    public InstanceKeyRecency(InstanceKey ik, boolean recent, boolean isTrackingMostRecent) {
        this.ik = ik;
        this.recent = recent;
        this.isTrackingMostRecent = isTrackingMostRecent;
        assert (!recent || isTrackingMostRecent) : "If this is the most recent, then we are definitely tracking the most recent";
    }

    public boolean isRecent() {
        return recent;
    }

    public InstanceKey baseInstanceKey() {
        return ik;
    }

    public boolean isTrackingMostRecent() {
        return this.isTrackingMostRecent;
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

    public InstanceKeyRecency recent(boolean recent) {
        assert isTrackingMostRecent : "Should only be calling this on instance keys that we are tracking the most recent.";
        if (this.recent == recent) {
            return this;
        }
        return RecencyHeapAbstractionFactory.create(this.ik, recent, isTrackingMostRecent);
    }

}