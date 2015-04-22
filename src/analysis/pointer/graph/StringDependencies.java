package analysis.pointer.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.Logger;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

/**
 * Tracks the dependencies between StringVariableReplicas.
 */
public class StringDependencies {
    private final Set<StringVariableReplica> active;

    /**
     * If svr1 ∈ dependsOn.get(svr2) then if the value of svr2 changes, the value of svr1 may need to change
     *
     * XXX is this equivalent to { (sac.stmt.getDef(), sac.context)| sac ∈ this.usedBy.get(svr2)} ??
     *
     */
    private final ConcurrentMap<StringVariableReplica, Set<StringVariableReplica>> dependsOn;

    /**
     * definedBy.get(svr) is the set of StmtAndContexts that define svr.
     */
    private final ConcurrentMap<StringVariableReplica, Set<StmtAndContext>> definedBy;

    /**
     * usedBy.get(svr) is the set of StmtAndContexts that use svr.
     */
    private final ConcurrentMap<StringVariableReplica, Set<StmtAndContext>> usedBy;

    public static StringDependencies make() {
        return new StringDependencies();
    }

    private StringDependencies() {
        this.active = AnalysisUtil.createConcurrentSet();
        this.dependsOn = AnalysisUtil.createConcurrentHashMap();
        this.definedBy = AnalysisUtil.createConcurrentHashMap();
        this.usedBy = AnalysisUtil.createConcurrentHashMap();
    }

    /**
     * Record that x depends on y, and return the set of StringVariableReplicas that are newly active. (i.e., if x was
     * active, then y needs to be active, and transitively, any SVR that y depends on).
     *
     * @param x
     * @param y
     * @return
     */
    public Set<StringVariableReplica> recordDependency(StringVariableReplica x, StringVariableReplica y) {
        setMapPut(this.dependsOn, x, y);

        if (isActive(x)) {
            return activate(y);
        }
        else {
            return Collections.emptySet();
        }
    }

    public boolean isActive(StringVariableReplica x) {
        // return this.active.contains(x);
        return true;
    }

    public Set<StringVariableReplica> activate(StringVariableReplica x) {
        Set<StringVariableReplica> newlyActive = new HashSet<>();
        activate(x, newlyActive);
        return newlyActive;
    }

    private void activate(StringVariableReplica x, Set<StringVariableReplica> newlyActive) {
        if (this.active.add(x)) {
            newlyActive.add(x);
            if (this.dependsOn.containsKey(x)) {
                for (StringVariableReplica y : this.dependsOn.get(x)) {
                    activate(y, newlyActive);
                }
            }
        }
    }

    public Set<StringVariableReplica> getActiveSet() {
        return this.active;
    }

    public void recordStatementDefineDependency(StringVariableReplica v, StmtAndContext sac) {
        setMapPut(this.definedBy, v, sac);
    }

    public void recordStatementUseDependency(StringVariableReplica v, StmtAndContext sac) {
        setMapPut(this.usedBy, v, sac);
    }

    public Set<StmtAndContext> getDefinedBy(StringVariableReplica v) {
        Logger.println("[getDefinedBy] " + v + " is defined by " + setMapGet(this.definedBy, v));
        return setMapGet(this.definedBy, v);
    }

    public Set<StmtAndContext> getUsedBy(StringVariableReplica v) {
        return setMapGet(this.usedBy, v);
    }

    private static <K, V> void setMapPut(ConcurrentMap<K, Set<V>> m, K k, V v) {
        if (m.containsKey(k)) {
            m.get(k).add(v);
        }
        else {
            m.put(k, AnalysisUtil.createConcurrentSingletonSet(v));
        }
    }

    private <K, V> Set<V> setMapGet(ConcurrentMap<K, Set<V>> m, K k) {
        if (m.containsKey(k)) {
            return m.get(k);
        }
        else {
            return AnalysisUtil.createConcurrentSet();
        }
    }

    public void printSVRDependencyTree(StringVariableReplica svr, StringSolution sc) {
        System.err.println("Dependency Tree for : " + svr + " = " + sc.getAStringFor(svr));
        printDependencies("  ", svr, sc);
    }

    private void printDependencies(String prefix, StringVariableReplica svr, StringSolution sc) {
        for (StringVariableReplica dep : nullElim(this.dependsOn.get(svr), new HashSet<StringVariableReplica>())) {
            System.err.println(prefix + dep + " = " + sc.getAStringFor(dep));
            printDependencies(prefix + "  ", dep, sc);
        }
        if (nullElim(this.dependsOn.get(svr), new HashSet<StringVariableReplica>()).isEmpty()) {
            for (StmtAndContext sac : nullElim(this.definedBy.get(svr), new HashSet<StmtAndContext>())) {
                System.err.println(prefix + "definedBy: " + sac);
            }
            if (nullElim(this.definedBy.get(svr), new HashSet<StmtAndContext>()).isEmpty()) {
                System.err.println(prefix + "not defined anywhere");
            }
        }
    }

    private <A> A nullElim(A a, A def) {
        if (a != null) {
            return a;
        }
        else {
            return def;
        }
    }

}
