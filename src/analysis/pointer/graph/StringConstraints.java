package analysis.pointer.graph;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import util.Logger;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

public class StringConstraints {

    private final ConcurrentMap<StringVariableReplica, AtomicReference<AString>> map;
    private final ReflectiveHAF haf;
    private final AString initialString;
    private final StringDependencies stringDependencies;

    /* Factory Methods */

    public static StringConstraints make(ReflectiveHAF haf) {
        return new StringConstraints(haf);
    }

    /* Constructors */

    private StringConstraints(ReflectiveHAF haf) {
        this.haf = haf;
        this.initialString = haf.getAStringSet(Collections.singleton(""));
        this.map = AnalysisUtil.createConcurrentHashMap();
        this.stringDependencies = StringDependencies.make();
    }

    /* Logic */

    public AString getAStringFor(StringVariableReplica svr) {
        AtomicReference<AString> ref = this.map.get(svr);
        if (ref == null) {
            return haf.getAStringBottom();
        }
        else {
            return ref.get();
        }
    }

    public StringConstraintDelta joinAt(StringVariableReplica svr, AString shat) {
        if (this.stringDependencies.isActive(svr)) {
            // Logger.println("[joinAt] isActive " + svr + " joining in " + shat);
            AtomicReference<AString> ref = this.map.get(svr);
            if (ref == null) {
                ref = new AtomicReference<>(shat); // make the initial value in the atomic reference shat.
                AtomicReference<AString> existing = this.map.putIfAbsent(svr, ref);
                if (existing != null) {
                    // someone beat us to it.
                    ref = existing;
                }
            }
            // here, ref is not null, and points to the appropriate AtomicReference for svr.
            // Also, the contents of ref is non-null.
            AString current, result;
            do {
                current = ref.get();
                result = current.join(shat);
                if (result.equals(current)) {
                    // no change!
                    break;
                }
            } while (!ref.compareAndSet(current, result));

            if (result.equals(current)) {
                // We didn't change the AString for svr.
                return StringConstraintDelta.makeEmpty(this);
            }
            else {
                return StringConstraintDelta.makeWithNeedUses(this, svr);
            }
        }
        else {
            Logger.println("[joinAt] Inactive variable: " + svr);
            return StringConstraintDelta.makeEmpty(this);
        }
    }

    public StringConstraintDelta upperBounds(StringVariableReplica svr1, StringVariableReplica svr2) {
        if (this.stringDependencies.isActive(svr1)) {
            if (this.map.containsKey(svr2)) {
                return this.joinAt(svr1, this.map.get(svr2));
            }
            else {
                return StringConstraintDelta.makeEmpty(this);
            }
        }
        else {
            return StringConstraintDelta.makeEmpty(this);
        }
    }

    public StringConstraintDelta recordDependency(StringVariableReplica x, StringVariableReplica y) {
        return StringConstraintDelta.makeWithNeedDefs(this, this.stringDependencies.recordDependency(x, y));
    }

    public StringConstraintDelta activate(StringVariableReplica x) {
        Set<StringVariableReplica> activatedSVRs = this.stringDependencies.activate(x);
        if (activatedSVRs.isEmpty()) {
            return StringConstraintDelta.makeEmpty(this);
        }
        else {
            return StringConstraintDelta.makeWithNeedDefs(this, activatedSVRs);
        }
    }

    public boolean isActive(StringVariableReplica x) {
        return this.stringDependencies.isActive(x);
    }

    public void recordStringStatementUseDependency(StringVariableReplica v, StmtAndContext sac) {
        this.stringDependencies.recordStatementUseDependency(v, sac);
    }

    public void recordStringStatementDefineDependency(StringVariableReplica v, StmtAndContext sac) {
        this.stringDependencies.recordStatementDefineDependency(v, sac);
    }

    public Set<StmtAndContext> getDefinedBy(StringVariableReplica v) {
        return this.stringDependencies.getDefinedBy(v);
    }

    public Set<StmtAndContext> getUsedBy(StringVariableReplica v) {
        return this.stringDependencies.getUsedBy(v);
    }

    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("StringConstraints Statistics:\n");
        sb.append("    total variables: " + this.map.size() + "\n");
        sb.append("    active variables: " + this.stringDependencies.getActiveSet().size() + "\n");
        return sb.toString();
    }

    /*
     * AUTOGENERATED STUFF
     *
     * Be sure to regenerate these (using Eclipse) if you change the number of fields in this class
     *
     * DEFINTIELY DONT CHANGE ANYTHING
     */

    @Override
    public String toString() {
        return "StringConstraints [map=" + map + "]";
    }

    public void printSVRDependencyTree(StringVariableReplica svr) {
        this.stringDependencies.printSVRDependencyTree(svr, this);
    }

}
