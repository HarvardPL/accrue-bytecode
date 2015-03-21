package analysis.pointer.graph;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import util.intmap.ConcurrentIntMap;
import analysis.AnalysisUtil;
import analysis.pointer.statements.ProgramPoint.InterProgramPointReplica;

import com.ibm.wala.util.intset.IntSet;

/**
 * Description of a single source program point reachability query.
 */
public final class ProgramPointSubQuery {
    final InterProgramPointReplica source;
    final InterProgramPointReplica destination;
    final/*Set<PointsToGraphNode>*/IntSet noKill;
    final/*Set<InstanceKeyRecency>*/IntSet noAlloc;
    final Set<InterProgramPointReplica> forbidden;
    final ReachabilityQueryOrigin origin;
    private volatile boolean expired;
    private final int hashcode;
    private final QueryCacheKey cacheKey;
    private static AtomicInteger counter = new AtomicInteger(0);
    private static final ConcurrentIntMap<ProgramPointSubQuery> dictionary = AnalysisUtil.createConcurrentIntMap();
    private static final ConcurrentMap<ProgramPointSubQuery, Integer> reverseDictionary = AnalysisUtil.createConcurrentHashMap();

    /**
     * Create a new sub query from source to destination
     *
     * @param source program point to search from
     * @param destination program point to find
     * @param noKill points-to graph nodes that must not be killed on a valid path from source to destination
     * @param noAlloc instance key that must not be allocated on a valid path from source to destination
     * @param forbidden program points that must not be traversed on a valid path from source to destination
     * @param origin Reachability query origin that triggered this subquery
     */
    private ProgramPointSubQuery(InterProgramPointReplica source, InterProgramPointReplica destination, /*Set<PointsToGraphNode>*/
                                 IntSet noKill, final/*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                 Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        assert source != null;
        assert destination != null;
        assert origin != null;
        this.source = source;
        this.destination = destination;
        this.noKill = noKill;
        this.noAlloc = noAlloc;
        this.forbidden = forbidden;
        this.origin = origin;
        this.hashcode = computeHashCode();
        this.expired = false;
        this.cacheKey = new QueryCacheKey();
    }

    /**
     * Lookup the unique integer for the given query
     *
     * @param key query to get the integer for
     *
     * @return integer
     */
    static int lookupDictionary(InterProgramPointReplica source, InterProgramPointReplica destination, /*Set<PointsToGraphNode>*/
                                IntSet noKill, final/*Set<InstanceKeyRecency>*/IntSet noAlloc,
                                Set<InterProgramPointReplica> forbidden, ReachabilityQueryOrigin origin) {
        ProgramPointSubQuery key = new ProgramPointSubQuery(source, destination, noKill, noAlloc, forbidden, origin);
        Integer n = reverseDictionary.get(key);
        if (n == null) {
            // not in the dictionary yet
            n = counter.getAndIncrement();

            // Note that it is important to do this before putting it into reverseDictionary
            // to avoid a race (i.e., someone looking up in reverseDictionary, getting
            // int n, yet getting null when trying dictionary.get(n).)
            // Note that we can do a put instead of a putIfAbsent, since n is guaranteed unique.
            dictionary.put(n, key);
            Integer existing = reverseDictionary.putIfAbsent(key, n);
            if (existing != null) {
                // someone beat us. n will never be used.
                reverseDictionary.remove(n);
                n = existing;
            }
        }
        return n;
    }

    /**
     * Get the query corresponding to the given integer
     *
     * @param query query to look up
     * @return query for the integer
     */
    static ProgramPointSubQuery lookupDictionary(int query) {
        return dictionary.get(query);
    }

    @Override
    public int hashCode() {
        return this.hashcode;
    }

    /**
     * compute memoized hashcode
     *
     * @return hashcode
     */
    private int computeHashCode() {
        final int prime = 31;
        int result = destination.hashCode();
        result = prime * result + source.hashCode();
        result = prime * result + noAlloc.size();
        result = prime * result + noKill.size();
        result = prime * result + forbidden.hashCode();
        result = prime * result + origin.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ProgramPointSubQuery)) {
            return false;
        }
        ProgramPointSubQuery other = (ProgramPointSubQuery) obj;

        if (!source.equals(other.source)) {
            return false;
        }
        if (!destination.equals(other.destination)) {
            return false;
        }
        if (noAlloc.isEmpty() != other.noAlloc.isEmpty()) {
            return false;
        }
        if (!noAlloc.sameValue(other.noAlloc)) {
            return false;
        }
        if (noKill.isEmpty() != other.noKill.isEmpty()) {
            return false;
        }
        if (!noKill.sameValue(other.noKill)) {
            return false;
        }
        if (!forbidden.equals(other.forbidden)) {
            return false;
        }
        if (!origin.equals(other.origin)) {
            return false;
        }
        return true;
    }

    public boolean isExpired() {
        return this.expired;
    }

    public void setExpired() {
        this.expired = true;
    }

    @Override
    public String toString() {
        return source + " => " + destination + ", noKill=" + noKill + ", noAlloc=" + noAlloc + ", forbidden="
                + forbidden + ", origin=" + origin + ", expired=" + expired;
    }

    /**
     * Key used to cache the results of a query. This key does not use the origin since the result pf the query does not
     * depend on the origin.
     */
    @SuppressWarnings("synthetic-access")
    public QueryCacheKey getCacheKey() {
        return cacheKey;
    }

    /**
     * Key used to cache the results of a query. This key does not use the origin since the result of the query does not
     * depend on the origin.
     */
    public class QueryCacheKey {
        private final int hashcode;

        /**
         * Cache key for the enclosing ProgramPointSubQuery
         *
         */
        private QueryCacheKey() {
            this.hashcode = computeHashCode();
        }

        /**
         * compute memoized hashcode
         *
         * @return hashcode
         */
        private int computeHashCode() {
            final int prime = 31;
            int result = destination.hashCode();
            result = prime * result + source.hashCode();
            result = prime * result + noAlloc.size();
            result = prime * result + noKill.size();
            result = prime * result + forbidden.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof QueryCacheKey)) {
                return false;
            }
            QueryCacheKey other = (QueryCacheKey) obj;
            if (this.hashcode != other.hashcode) {
                return false;
            }

            ProgramPointSubQuery otherPPSQ = other.ppsq();

            if (!source.equals(otherPPSQ.source)) {
                return false;
            }
            if (!destination.equals(otherPPSQ.destination)) {
                return false;
            }
            if (noAlloc.isEmpty() != otherPPSQ.noAlloc.isEmpty()) {
                return false;
            }
            if (!noAlloc.sameValue(otherPPSQ.noAlloc)) {
                return false;
            }
            if (noKill.isEmpty() != otherPPSQ.noKill.isEmpty()) {
                return false;
            }
            if (!noKill.sameValue(otherPPSQ.noKill)) {
                return false;
            }
            if (!forbidden.equals(otherPPSQ.forbidden)) {
                return false;
            }
            return true;
        }

        private ProgramPointSubQuery ppsq() {
            return ProgramPointSubQuery.this;
        }

        @Override
        public int hashCode() {
            return hashcode;
        }

        @Override
        public String toString() {
            return source + " => " + destination + ", noKill=" + noKill + ", noAlloc=" + noAlloc + ", forbidden="
                    + forbidden;
        }
    }
}
