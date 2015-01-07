package analysis.pointer.registrar;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.WorkQueue;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.ProgramPoint;

/**
 * Algorithm used to compute the ordering relationship for call-sites within a method
 */
class CallSiteOrdering {

    /**
     * Map from program points to call sites that occur after that program point on some path to the exit
     */
    private Map<ProgramPoint, Set<CallSiteProgramPoint>> callSitesAfter = new HashMap<>();
    /**
     * Map from program point to direct predecessors in the program point graph
     */
    private Map<ProgramPoint, Set<ProgramPoint>> preds = new HashMap<>();

    /**
     * Compute the may-happens-after relationship between call-sites within a given method.
     *
     * @param entryPP program point for the entry to the method
     * @return Map from call-sites to call-sites that may occur after that call-site
     */
    Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> getForMethod(ProgramPoint entryPP) {

        WorkQueue<ProgramPoint> q = new WorkQueue<>();
        this.preds = new HashMap<>();
        this.callSitesAfter = new HashMap<>();

        q.add(entryPP);

        // Compute predecessors
        Set<ProgramPoint> visited = new HashSet<>();
        Set<ProgramPoint> exits = new HashSet<>();
        while (!q.isEmpty()) {
            ProgramPoint current = q.poll();
            if (current.isNormalExitSummaryNode() || current.isExceptionExitSummaryNode()) {
                exits.add(current);
            }
            for (ProgramPoint succ : current.succs()) {
                recordPredecessor(current, succ);
                if (!visited.add(succ)) {
                    q.add(current);
                }
            }
        }

        assert q.isEmpty();
        // Record all call-sites seen by searching backward from the exit program points
        q.addAll(exits);
        while (q.isEmpty()) {
            ProgramPoint current = q.poll();
            Set<CallSiteProgramPoint> result;
            if (!current.succs().isEmpty()) {
                assert current.isExceptionExitSummaryNode() || current.isNormalExitSummaryNode();
                // initial results for the exit nodes
                result = Collections.emptySet();
            }
            else {
                result = new HashSet<>();
                for (ProgramPoint succ : current.succs()) {
                    result.addAll(getResults(succ));
                }
            }

            if (recordResult(current, result)) {
                // The result changed add all predecessors to the queue to be run/rerun
                if (!preds.containsKey(current)) {
                    assert current.isEntrySummaryNode() : "non entry without preds: " + current;
                    continue;
                }
                q.addAll(preds.get(current));
            }
        }

        // Finished computing return the results for call-sites
        Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> results = new LinkedHashMap<>();
        for (ProgramPoint pp : callSitesAfter.keySet()) {
            if (pp instanceof CallSiteProgramPoint) {
                results.put((CallSiteProgramPoint) pp, callSitesAfter.get(pp));
            }
        }
        return results;
    }

    /**
     * Record that "pred" is a predecessor of "succ"
     *
     * @param pred predecessor
     * @param succ successor of the predecessor
     */
    private void recordPredecessor(ProgramPoint pred, ProgramPoint succ) {
        Set<ProgramPoint> s = this.preds.get(succ);
        if (s == null) {
            s = new LinkedHashSet<>();
            this.preds.put(succ, s);
        }
        s.add(pred);
    }

    /**
     * Record the results for the given program point
     *
     * @param pp program point
     * @param result result to record
     *
     * @return true if the new result was different than the previously cached result
     */
    private boolean recordResult(ProgramPoint pp, Set<CallSiteProgramPoint> result) {
        Set<CallSiteProgramPoint> existing = this.callSitesAfter.get(pp);
        assert existing == null || result.containsAll(existing) : "Removing elements " + existing + "  NEW " + result;
        if (existing != null && existing.containsAll(result)) {
            return false;
        }

        // new result
        this.callSitesAfter.put(pp, result);
        return true;
    }

    /**
     * Get the results for a given program point
     *
     * @param pp program point to get the results for
     * @return the set of call site program points that are after the given program point on some path to the exit
     */
    private Set<CallSiteProgramPoint> getResults(ProgramPoint pp) {
        Set<CallSiteProgramPoint> results = this.callSitesAfter.get(pp);
        if (results == null) {
            results = Collections.emptySet();
            this.callSitesAfter.put(pp, results);
        }
        return results;
    }
}
