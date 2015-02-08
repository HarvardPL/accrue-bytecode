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
        assert recent ? isTrackingMostRecent : true : "If this is the most recent, then we are definitely tracking the most recent";
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
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof InstanceKeyRecency)) {
            return false;
        }
        InstanceKeyRecency other = (InstanceKeyRecency) obj;
        if (ik == null) {
            if (other.ik != null) {
                return false;
            }
        }
        else if (!ik.equals(other.ik)) {
            return false;
        }
        if (isTrackingMostRecent != other.isTrackingMostRecent) {
            return false;
        }
        if (recent != other.recent) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((ik == null) ? 0 : ik.hashCode());
        result = prime * result + (isTrackingMostRecent ? 1231 : 1237);
        result = prime * result + (recent ? 1231 : 1237);
        return result;
    }

    @Override
    public String toString() {
        if (ik == null) {
            return "<null,true>";
        }
        return "<" + ik.toString() + "," + recent + ">";
    }

    public String toStringWithoutRecency() {
        if (ik == null) {
            return "null";
        }
        return ik.toString();
    }

    @Override
    public IClass getConcreteType() {
        if (ik == null) {
            // Give back null for the type when this is the instance key for "null"
            return null;
        }
        return ik.getConcreteType();
    }

    @Override
    public Iterator<Pair<CGNode, NewSiteReference>> getCreationSites(CallGraph CG) {
        assert ik != null;
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
