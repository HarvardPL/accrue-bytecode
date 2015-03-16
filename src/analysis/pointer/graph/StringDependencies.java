package analysis.pointer.graph;

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
        return this.activate(x, AnalysisUtil.<StringVariableReplica> createConcurrentSet());
    }

    private Set<StringVariableReplica> activate(StringVariableReplica x, Set<StringVariableReplica> alreadyActivated) {
        if (alreadyActivated.contains(x)) {
            return AnalysisUtil.createConcurrentSet();
        }
        else {
            this.active.add(x);
            alreadyActivated.add(x);

            System.err.println("[StringDependencies.activate] Activating: " + x);

            Set<StringVariableReplica> sources = new HashSet<>();
            sources.add(x);
            if (this.dependsOn.containsKey(x)) {
                for (StringVariableReplica y : this.dependsOn.get(x)) {
                    sources.addAll(activate(y, alreadyActivated));
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

}
