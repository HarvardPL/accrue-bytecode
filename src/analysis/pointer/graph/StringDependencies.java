package analysis.pointer.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

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

    public void recordDependency(StringVariableReplica x, StringVariableReplica y) {
        setMapPut(this.dependsOn, x, y);

        if (this.active.contains(y)) {
            this.active.add(x);
        }
    }

    public boolean isActive(StringVariableReplica x) {
        return this.active.contains(x);
    }

    public Set<StringVariableReplica> activate(StringVariableReplica x) {
        if (this.active.contains(x)) {
            return new HashSet<>();
        }
        else {
            this.active.add(x);

            System.err.println("[StringDependencies.activate] Activating: " + x);

            if (this.dependsOn.containsKey(x) && !this.dependsOn.get(x).isEmpty()) {
                Set<StringVariableReplica> sources = new HashSet<>();
                for (StringVariableReplica y : this.dependsOn.get(x)) {
                    sources.addAll(activate(y));
                }
                return sources;
            }
            else {
                return Collections.singleton(x);
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

    private static <K, V> void setMapPut(ConcurrentMap<K, Set<V>> m, K k, V v) {
        if (m.containsKey(k)) {
            m.get(k).add(v);
        }
        else {
            m.put(k, AnalysisUtil.createConcurrentSingletonSet(v));
        }
    }
}
