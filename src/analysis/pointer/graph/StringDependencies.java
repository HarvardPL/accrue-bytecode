package analysis.pointer.graph;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.Logger;
import analysis.AnalysisUtil;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

public class StringDependencies {
    private final Set<StringVariableReplica> active;
    private final ConcurrentMap<StringVariableReplica, Set<StringVariableReplica>> dependsOn;
    private final ConcurrentMap<StringVariableReplica, Set<StmtAndContext>> definedBy;
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

    public Set<StringVariableReplica> recordDependency(StringVariableReplica x, StringVariableReplica y) {
        setMapPut(this.dependsOn, x, y);

        if (isActive(x)) {
            return activate(y);
        }
        else {
            return AnalysisUtil.createConcurrentSet();
        }
    }

    public boolean isActive(StringVariableReplica x) {
        // return this.active.contains(x);
        return true;
    }

    public Set<StringVariableReplica> activate(StringVariableReplica x) {
        if (isActive(x)) {
            return AnalysisUtil.createConcurrentSet();
        }
        else {
            this.active.add(x);

            Set<StringVariableReplica> sources = new HashSet<>();
            sources.add(x);
            if (this.dependsOn.containsKey(x)) {
                for (StringVariableReplica y : this.dependsOn.get(x)) {
                    sources.addAll(activate(y));
                }
            }
            return sources;
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

    public void printSVRDependencyTree(StringVariableReplica svr, StringConstraints sc) {
        System.err.println("Dependency Tree for : " + svr + " = " + sc.getAStringFor(svr));
        printDependencies("  ", svr, sc);
    }

    private void printDependencies(String prefix, StringVariableReplica svr, StringConstraints sc) {
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
