package analysis.dataflow.interprocedural.pdg.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ssa.SSAInvokeInstruction;

/**
 * Label for an edge from a call site or to a return site.
 */
public class CallSiteEdgeLabel {

    /**
     * Unique ID for call site
     */
    private final Integer callSiteID;

    /**
     * Identifiers for the receivers at this call site (if any)
     */
    private Set<Integer> receiverIDs;

    /**
     * Map from the call site to ID for the edge, used to correlate call and return sites
     */
    private static final HashMap<CallSiteKey, Integer> idMap = new HashMap<>();

    /**
     * Map from the receiver object to the unique ID for that receiver
     */
    private static final HashMap<InstanceKey, Integer> receiverIdMap = new HashMap<>();

    /**
     * Counter for unique IDs
     */
    private static int idCounter;

    /**
     * Counter for unique IDs for objects in Java's collections framework
     */
    private static int receiverCounter;

    /**
     * Create a new label for this entry or exit edge
     *
     * @param site Call or return site
     * @param caller method and context for the caller
     * @param type Indication of whether this is an entry or exit
     */
    public CallSiteEdgeLabel(SSAInvokeInstruction site, CGNode caller, SiteType type) {
        this(getID(site, caller), type);
        assert this.callSiteID != null;
    }

    /**
     * Create a new label
     * 
     * @param id unique call-site ID
     * @param type Indication of whether this is an entry or exit
     */
    private CallSiteEdgeLabel(Integer id, SiteType type) {
        assert type != null;
        this.callSiteID = id;
        this.type = type;
    }

    /**
     * Duplicate this edge label, but set the receivers for the call-site to the given set
     *
     * @param receivers set of receiver abstract objects for this call-site edge
     * @return new label identical to "this" except for the receivers
     */
    public CallSiteEdgeLabel getLabelForReceivers(Set<InstanceKey> receivers) {
        CallSiteEdgeLabel newLabel = new CallSiteEdgeLabel(this.callSiteID, this.type);
        newLabel.receiverIDs = getUniqueReceiverIDs(receivers);
        return newLabel;
    }

    /**
     * Get the unique receiver identifiers associated with the given receivers. If the receiver iterator is null return
     * null.
     *
     * @param receivers receivers to get ids for
     * @return set of unique IDs or null if the receiver is null
     */
    private static Set<Integer> getUniqueReceiverIDs(Set<InstanceKey> receivers) {
        if (receivers == null) {
            return null;
        }
        Set<Integer> s = new HashSet<>();
        for (InstanceKey i : receivers) {
            Integer id = receiverIdMap.get(i);
            if (id == null) {
                id = ++receiverCounter;
                receiverIdMap.put(i, id);
            }
            s.add(id);
        }
        return s;
    }

    /**
     * Get the ID for a given call site
     *
     * @param site call site
     * @param n method and context for the caller
     *
     * @return Unique ID for the call site
     */
    private static Integer getID(SSAInvokeInstruction site, CGNode n) {
        CallSiteKey key = new CallSiteKey(site, n);
        Integer id = idMap.get(key);
        if (id == null) {
            id = ++idCounter;
            idMap.put(key, id);
        }
        return id;
    }

    /**
     * Indication of whether this is an entry or exit
     */
    private final SiteType type;

    /**
     * Whether this edge is into an entry node or out of an exit node for the procedure
     */
    public enum SiteType {
        /**
         * This is a procedure entry edge
         */
        ENTRY,
        /**
         * This is a procedure exit edge
         */
        EXIT;
    }

    /**
     * Indication of whether this is an entry or exit
     *
     * @return Indication of whether this is an entry or exit
     */
    public SiteType getType() {
        return type;
    }

    /**
     * Get the unique ID for the procedure call site
     *
     * @return call site ID
     */
    public Integer getCallSiteID() {
        return callSiteID;
    }

    /**
     * Identifiers for the possible receivers of this call
     *
     * @return the possible receivers or null if we are not tracking the receiver for this call
     */
    public Set<Integer> getReceiverIDs() {
        return receiverIDs;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        CallSiteEdgeLabel other = (CallSiteEdgeLabel) obj;

        if (callSiteID != other.callSiteID) {
            return false;
        }

        if (type != other.type) {
            return false;
        }

        if (receiverIDs == null) {
            if (other.receiverIDs != null) {
                return false;
            }
        }
        else if (!receiverIDs.equals(other.receiverIDs)) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return getType().toString() + "_" + callSiteID;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + callSiteID.hashCode();
        result = prime * result + ((receiverIDs == null) ? 0 : receiverIDs.hashCode());
        result = prime * result + type.hashCode();
        return result;
    }

    /**
     * Call site and call graph node for the caller
     */
    private static class CallSiteKey {

        /**
         * call or return site
         */
        private final SSAInvokeInstruction site;
        /**
         * method and context for caller
         */
        private final CGNode caller;

        /**
         * Create a key to the ID map for call sites
         *
         * @param site call or return site
         * @param caller method and context for caller
         */
        public CallSiteKey(SSAInvokeInstruction site, CGNode caller) {
            this.site = site;
            this.caller = caller;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((caller == null) ? 0 : caller.hashCode());
            result = prime * result + ((site == null) ? 0 : site.hashCode());
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
            if (getClass() != obj.getClass()) {
                return false;
            }
            CallSiteKey other = (CallSiteKey) obj;
            if (caller == null) {
                if (other.caller != null) {
                    return false;
                }
            }
            else if (!caller.equals(other.caller)) {
                return false;
            }
            if (site == null) {
                if (other.site != null) {
                    return false;
                }
            }
            else if (!site.equals(other.site)) {
                return false;
            }
            return true;
        }
    }
}
