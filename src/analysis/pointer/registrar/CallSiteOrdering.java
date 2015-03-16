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
     * Map from program points to call sites that occur before that program point on some path from the entry
     */
    private Map<ProgramPoint, Set<CallSiteProgramPoint>> callSitesBefore;
    /**
     * Map from program point to direct predecessors in the program point graph
     */
    private Map<ProgramPoint, Set<ProgramPoint>> preds;

    /**
     * Compute the may-happens-before relationship between call-sites within a given method.
     *
     * @param entryPP program point for the entry to the method
     * @return Map from call-sites to call-sites that may occur after that call-site
     */
    Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> getForMethod(ProgramPoint entryPP) {
        WorkQueue<ProgramPoint> q = new WorkQueue<>();
        this.callSitesBefore = new HashMap<>();

        // clear the preds and callSitesBefore hashmap
        this.preds = new HashMap<>();
        this.callSitesBefore = new HashMap<>();

        q.add(entryPP);

        // Compute predecessors
        Set<ProgramPoint> visited = new HashSet<>();
        while (!q.isEmpty()) {
            ProgramPoint current = q.poll();
            for (ProgramPoint succ : current.succs()) {
                recordPredecessor(current, succ);
                if (visited.add(succ)) {
                    q.add(succ);
                }
            }
        }

        assert q.isEmpty();

        // Record all call-sites seen by searching forward from the entry program point
        q.add(entryPP);

        visited = new HashSet<>();
        while (!q.isEmpty()) {
            ProgramPoint current = q.poll();
            Set<CallSiteProgramPoint> result;
            if (current == entryPP) {
                // initial results for the entry node
                result = Collections.emptySet();
            }
            else {
                result = new HashSet<>();
                for (ProgramPoint pred : getPreds(current)) {
                    result.addAll(getResults(pred));
                    if (pred instanceof CallSiteProgramPoint) {
                        result.add((CallSiteProgramPoint) pred);
                    }
                }
            }

            boolean changed = recordResult(current, result);
            for (ProgramPoint succ : current.succs()) {
                // If the result changed add all successors to the queue to be run/rerun
                if (changed | visited.add(succ)) {
                    q.add(succ);
                }
            }
        }

        // Finished computing return the results for call-sites
        Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> results = new LinkedHashMap<>();
        for (ProgramPoint pp : callSitesBefore.keySet()) {
            if (pp instanceof CallSiteProgramPoint) {
                results.put((CallSiteProgramPoint) pp, callSitesBefore.get(pp));
            }
        }

        return results;
    }

    private Set<ProgramPoint> getPreds(ProgramPoint pp) {
        Set<ProgramPoint> s = this.preds.get(pp);
        if (s == null) {
            return Collections.EMPTY_SET;
        }
        return s;
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
        Set<CallSiteProgramPoint> existing = this.callSitesBefore.get(pp);
        assert existing == null || result.containsAll(existing) : "Removing elements " + existing + "  NEW " + result;
        if (existing != null && existing.containsAll(result)) {
            return false;
        }

        // new result
        this.callSitesBefore.put(pp, result);
        return true;
    }

    /**
     * Get the results for a given program point
     *
     * @param pp program point to get the results for
     * @return the set of call site program points that are after the given program point on some path to the exit
     */
    private Set<CallSiteProgramPoint> getResults(ProgramPoint pp) {
        Set<CallSiteProgramPoint> results = this.callSitesBefore.get(pp);
        if (results == null) {
            results = Collections.emptySet();
            this.callSitesBefore.put(pp, results);
        }
        return results;
    }
}
