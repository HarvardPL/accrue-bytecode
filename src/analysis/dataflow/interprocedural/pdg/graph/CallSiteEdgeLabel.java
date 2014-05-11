package analysis.dataflow.interprocedural.pdg.graph;

import java.util.HashMap;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;

/**
 * Label for an edge from a call site or to a return site.
 */
public class CallSiteEdgeLabel {

    /**
     * Unique ID for call site
     */
    private final Integer callSiteID;

    /**
     * Map from the call site to ID for the edge, used to correlate call and
     * return sites
     */
    private static final HashMap<CallSiteKey, Integer> idMap = new HashMap<>();

    /**
     * Counter for unique IDs
     */
    private static int idCounter;

    /**
     * Create a new label for this entry or exit edge
     * 
     * @param site
     *            Call or return site
     * @param caller
     *            method and context for the caller
     * @param type
     *            Indication of whether this is an entry or exit
     */
    public CallSiteEdgeLabel(CallSiteReference site, CGNode caller, SiteType type) {
        this.callSiteID = getID(site, caller);
        this.type = type;
    }

    /**
     * Get the ID for a given call site
     * 
     * @param site
     *            call site
     * @param n
     *            method and context for the caller
     * 
     * @return Unique ID for the call site
     */
    private static Integer getID(CallSiteReference site, CGNode n) {
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
     * Whether this edge is into an entry node or out of an exit node for the
     * procedure
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

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof CallSiteEdgeLabel)) {
            return false;
        }
        CallSiteEdgeLabel other = (CallSiteEdgeLabel) obj;
        return this.callSiteID == other.callSiteID && this.getType().equals(other.getType());
    }

    @Override
    public String toString() {
        return getType().toString() + "_" + callSiteID;
    }

    @Override
    public int hashCode() {
        return callSiteID.hashCode() * 17 + 19 * getType().hashCode();
    }

    /**
     * Call site and call graph node for the caller
     */
    private static class CallSiteKey {

        /**
         * call or return site
         */
        private final CallSiteReference site;
        /**
         * method and context for caller
         */
        private final CGNode caller;

        /**
         * Create a key to the ID map for call sites
         * 
         * @param site
         *            call or return site
         * @param caller
         *            method and context for caller
         */
        public CallSiteKey(CallSiteReference site, CGNode caller) {
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
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            CallSiteKey other = (CallSiteKey) obj;
            if (caller == null) {
                if (other.caller != null)
                    return false;
            } else if (!caller.equals(other.caller))
                return false;
            if (site == null) {
                if (other.site != null)
                    return false;
            } else if (!site.equals(other.site))
                return false;
            return true;
        }
    }
}
