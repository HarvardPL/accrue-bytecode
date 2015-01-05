package analysis.pointer.graph;

import java.util.Set;

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

    /**
     * Create a new sub query from source to destination
     *
     * @param source program point to search from
     * @param destination program point to find
     * @param noKill points-to graph nodes that must not be killed on a valid path from source to destination
     * @param noAlloc instance key that must not be allocated on a valid path from source to destination
     * @param forbidden program points that must not be traversed on a valid path from source to destination
     */
    ProgramPointSubQuery(InterProgramPointReplica source, InterProgramPointReplica destination, /*Set<PointsToGraphNode>*/
             IntSet noKill, final/*Set<InstanceKeyRecency>*/IntSet noAlloc, Set<InterProgramPointReplica> forbidden) {
        this.source = source;
        this.destination = destination;
        this.noKill = noKill;
        this.noAlloc = noAlloc;
        this.forbidden = forbidden;
    }

    @Override
    public int hashCode() {
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
        return true;
    }

    @Override
    public String toString() {
        return source + " => " + destination + ", noKill=" + noKill + ", noAlloc=" + noAlloc + ", forbidden="
                + forbidden;
    }
}
