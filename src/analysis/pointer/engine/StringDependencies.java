package analysis.pointer.engine;

import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import analysis.AnalysisUtil;
import analysis.dataflow.flowsensitizer.Relation;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.strings.StringLikeLocationReplica;

/**
 * Tracks the dependencies between StringSolutionVariables.
 */
public class StringDependencies {
    /**
     * definedBy.get(svr) is the set of StmtAndContexts that define svr.
     */
    private final ConcurrentMap<StringLikeLocationReplica, Set<StmtAndContext>> writtenBy;

    /**
     * usedBy.get(svr) is the set of StmtAndContexts that use svr.
     */
    private final ConcurrentMap<StringLikeLocationReplica, Set<StmtAndContext>> readBy;

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
    public void recordWrite(StringLikeLocationReplica v, StmtAndContext sac) {
        setMapPut(this.writtenBy, v, sac);
    }

    public void recordRead(StringLikeLocationReplica v, StmtAndContext sac) {
        setMapPut(this.readBy, v, sac);
    }

    public Set<StmtAndContext> getWriteTo(StringLikeLocationReplica v) {
        //        Logger.println("[getDefinedBy] " + v + " is defined by " + setMapGet(this.writtenBy, v));
        return setMapGet(this.writtenBy, v);
    }

    public Set<StmtAndContext> getReadFrom(StringLikeLocationReplica v) {
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

    public void printStringDependencyTree(StringLikeLocationReplica v, String indent) {
        Relation<StmtAndContext, StringLikeLocationReplica> reads = Relation.makeBottom();
        for (Entry<StringLikeLocationReplica, Set<StmtAndContext>> kv : this.readBy.entrySet()) {
            StringLikeLocationReplica loc = kv.getKey();
            Set<StmtAndContext> sacs = kv.getValue();
            for (StmtAndContext sac : sacs) {
                reads.add(sac, loc);
            }
        }
        printStringDependencyTree(v, indent, reads);
    }

    private void printStringDependencyTree(StringLikeLocationReplica v, String indent,
                                           Relation<StmtAndContext, StringLikeLocationReplica> reads) {
        System.err.println(indent + v);

        Set<StmtAndContext> sacs = this.writtenBy.get(v);

        if (sacs == null || sacs.isEmpty()) {
            System.err.println(indent + "  is not written by anyone");
        }
        else {
            for (StmtAndContext sac : sacs) {
                for (StringLikeLocationReplica dependency : reads.get(sac)) {
                    printStringDependencyTree(dependency, indent + "  ", reads);
                }
                if (reads.get(sac).isEmpty()) {
                    System.err.println(indent + "  is written by a statement with no reads: " + sac);
                }
            }
        }
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
