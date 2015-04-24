package analysis.pointer.engine;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.Logger;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.StringSolutionVariable;

/**
 * Tracks the dependencies between StringSolutionVariables.
 */
public class StringDependencies {
    /**
     * definedBy.get(svr) is the set of StmtAndContexts that define svr.
     */
    private final ConcurrentMap<StringSolutionVariable, Set<StmtAndContext>> writtenBy;

    /**
     * usedBy.get(svr) is the set of StmtAndContexts that use svr.
     */
    private final ConcurrentMap<StringSolutionVariable, Set<StmtAndContext>> readBy;

    StringDependencies() {
        this.writtenBy = AnalysisUtil.createConcurrentHashMap();
        this.readBy = AnalysisUtil.createConcurrentHashMap();
    }

    //    public Set<StringSolutionVariable> activate(StringSolutionVariable x) {
    //        Set<StringSolutionVariable> newlyActive = new HashSet<>();
    //        activate(x, newlyActive);
    //        return newlyActive;
    //    }

    //    private void activate(StringSolutionVariable x, Set<StringSolutionVariable> newlyActive) {
    //        if (this.active.add(x)) {
    //            newlyActive.add(x);
    //            if (this.dependsOn.containsKey(x)) {
    //                for (StringSolutionVariable y : this.dependsOn.get(x)) {
    //                    activate(y, newlyActive);
    //                }
    //            }
    //        }
    //    }

    //    public Set<StringSolutionVariable> getActiveSet() {
    //        return this.active;
    //    }
    //
    public void recordWrite(StringSolutionVariable v, StmtAndContext sac) {
        setMapPut(this.writtenBy, v, sac);
    }

    public void recordRead(StringSolutionVariable v, StmtAndContext sac) {
        setMapPut(this.readBy, v, sac);
    }

    public Set<StmtAndContext> getWrittenBy(StringSolutionVariable v) {
        Logger.println("[getDefinedBy] " + v + " is defined by " + setMapGet(this.writtenBy, v));
        return setMapGet(this.writtenBy, v);
    }

    public Set<StmtAndContext> getReadBy(StringSolutionVariable v) {
        return setMapGet(this.readBy, v);
    }

    private static <K, V> void setMapPut(ConcurrentMap<K, Set<V>> m, K k, V v) {
        Set<V> s = m.get(k);
        if (s == null) {
            s = AnalysisUtil.createConcurrentSet();
            Set<V> existing = m.putIfAbsent(k, s);
            if (existing != null) {
                // someone else put the set in first
                s = existing;
            }
        }
        s.add(v);
    }

    private <K, V> Set<V> setMapGet(ConcurrentMap<K, Set<V>> m, K k) {
        Set<V> s = m.get(k);
        if (s != null) {
            return s;
        }
        return Collections.emptySet();
    }

    //    public void printSVRDependencyTree(StringSolutionVariable svr, StringSolution sc) {
    //        System.err.println("Dependency Tree for : " + svr + " = " + sc.getAStringFor(svr));
    //        printDependencies("  ", svr, sc);
    //    }

    //    private void printDependencies(String prefix, StringSolutionVariable svr, StringSolution sc) {
    //        for (StringSolutionVariable dep : nullElim(this.dependsOn.get(svr), new HashSet<StringSolutionVariable>())) {
    //            System.err.println(prefix + dep + " = " + sc.getAStringFor(dep));
    //            printDependencies(prefix + "  ", dep, sc);
    //        }
    //        if (nullElim(this.dependsOn.get(svr), new HashSet<StringSolutionVariable>()).isEmpty()) {
    //            for (StmtAndContext sac : nullElim(this.definedBy.get(svr), new HashSet<StmtAndContext>())) {
    //                System.err.println(prefix + "definedBy: " + sac);
    //            }
    //            if (nullElim(this.definedBy.get(svr), new HashSet<StmtAndContext>()).isEmpty()) {
    //                System.err.println(prefix + "not defined anywhere");
    //            }
    //        }
    //    }

    private <A> A nullElim(A a, A def) {
        if (a != null) {
            return a;
        }
        else {
            return def;
        }
    }

}
