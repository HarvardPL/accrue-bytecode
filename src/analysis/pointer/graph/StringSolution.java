package analysis.pointer.graph;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.Logger;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;

public class StringSolution {

    class AbstractLocation {
        InstanceKey receiver;
        IField field;

    }

    /**
     * Map from PointsToGraphNode to abstract string values. The only acceptable PointsToGraphNodes are
     * StringVariableReplicas and ObjectFields. StringVariableReplicas are used to represent local String-valued
     * variables, local StringBuilder (i.e., StringBuilders that have not escaped local scope) and static
     * fields.ObjectFields are used to represent fields of objects of type String or StringBuilder.
     */
    private final ConcurrentMap<PointsToGraphNode, AString> map;

    private final ReflectiveHAF haf;
    private final StringDependencies stringDependencies;

    /* Factory Methods */

    public static StringSolution make(ReflectiveHAF haf) {
        return new StringSolution(haf);
    }

    /* Constructors */

    private StringSolution(ReflectiveHAF haf) {
        this.haf = haf;
        this.map = AnalysisUtil.createConcurrentHashMap();
        this.stringDependencies = StringDependencies.make();
    }

    /* Logic */

    public AString getAStringFor(StringVariableReplica svr) {
        AString shat = this.map.get(svr);
        if (shat == null) {
            return haf.getAStringBottom();
        }
        else {
            return shat;
        }
    }

    public StringConstraintDelta joinAt(StringVariableReplica svr, AString shat) {
        if (this.stringDependencies.isActive(svr)) {
            // Logger.println("[joinAt] isActive " + svr + " joining in " + shat);
            AString current = this.map.get(svr);
            if (current == null) {
                AString existing = this.map.putIfAbsent(svr, shat);
                if (existing == null) {
                    // we definitely updated.
                    return StringConstraintDelta.makeWithNeedUses(this, svr);
                }
                // someone beat us to it
                current = existing;
            }

            while (true) {
                AString newval = current.join(shat);
                if (newval.equals(current)) {
                    // We didn't change the AString for svr.
                    return StringConstraintDelta.makeEmpty(this);
                }
                // try to change it
                if (this.map.replace(svr, current, newval)) {
                    // We successfully changed it!
                    return StringConstraintDelta.makeWithNeedUses(this, svr);
                }

                // someone else snuck in. Try again.
                current = this.map.get(svr);
            }
        }
        else {
            Logger.println("[joinAt] Inactive variable: " + svr);
            return StringConstraintDelta.makeEmpty(this);
        }
    }

    private AString getOrInitialize(StringVariableReplica svr, AString shat) {
        AString current = this.map.get(svr);
        if (current == null) {
            current = shat;
            AString existing = this.map.putIfAbsent(svr, current);
            if (existing != null) {
                // someone beat us to it
                current = existing;
            }
        }
        return current;
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
