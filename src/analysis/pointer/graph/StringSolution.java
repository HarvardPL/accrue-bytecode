package analysis.pointer.graph;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import util.Logger;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.DependencyRecorder;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

public class StringSolution {
    /**
     * The set of active StringSolutionVariable.
     */
    private final Set<StringSolutionVariable> active;

    /**
     * Map from PointsToGraphNode to abstract string values. The only acceptable PointsToGraphNodes are
     * StringSolutionVariables and ObjectFields. StringSolutionVariables are used to represent local String-valued
     * variables, local StringBuilder (i.e., StringBuilders that have not escaped local scope) and static
     * fields.ObjectFields are used to represent fields of objects of type String or StringBuilder.
     */
    private final ConcurrentMap<StringSolutionVariable, AString> map;

    private final ReflectiveHAF haf;
    private final DependencyRecorder depRecorder;

    /* Factory Methods */

    public static StringSolution make(ReflectiveHAF haf, DependencyRecorder depRecorder) {
        return new StringSolution(haf, depRecorder);
    }

    /* Constructors */

    private StringSolution(ReflectiveHAF haf, DependencyRecorder depRecorder) {
        this.active = AnalysisUtil.createConcurrentSet();
        this.haf = haf;
        this.depRecorder = depRecorder;
        this.map = AnalysisUtil.createConcurrentHashMap();
    }

    /* Logic */

    public AString getAStringFor(StringSolutionVariable svr) {
        AString shat = this.map.get(svr);
        if (shat == null) {
            return haf.getAStringBottom();
        }
        else {
            return shat;
        }
    }

    public StringSolutionDelta joinAt(StringSolutionVariable svr, AString shat) {
        if (this.isActive(svr)) {
            // Logger.println("[joinAt] isActive " + svr + " joining in " + shat);
            AString current = this.map.get(svr);
            if (current == null) {
                AString existing = this.map.putIfAbsent(svr, shat);
                if (existing == null) {
                    // we definitely updated.
                    return StringSolutionDelta.makeWithNeedUses(this, svr);
                }
                // someone beat us to it
                current = existing;
            }

            while (true) {
                AString newval = current.join(shat);
                if (newval.equals(current)) {
                    // We didn't change the AString for svr.
                    return StringSolutionDelta.makeEmpty(this);
                }
                // try to change it
                if (this.map.replace(svr, current, newval)) {
                    // We successfully changed it!
                    return StringSolutionDelta.makeWithNeedUses(this, svr);
                }

                // someone else snuck in. Try again.
                current = this.map.get(svr);
            }
        }
        else {
            Logger.println("[joinAt] Inactive variable: " + svr);
            return StringSolutionDelta.makeEmpty(this);
        }
    }

    public StringSolutionDelta upperBounds(StringSolutionVariable svr1, StringSolutionVariable svr2) {
        if (this.isActive(svr1)) {
            if (this.map.containsKey(svr2)) {
                return this.joinAt(svr1, this.map.get(svr2));
            }
            else {
                return StringSolutionDelta.makeEmpty(this);
            }
        }
        else {
            return StringSolutionDelta.makeEmpty(this);
        }
    }

    public StringSolutionDelta recordDependency(StringSolutionVariable x, StringSolutionVariable y) {
        return StringSolutionDelta.makeWithNeedDefs(this, this.depRecorder.recordDependency(x, y));
    }

    public StringSolutionDelta activate(StringSolutionVariable x) {
        if (this.active.add(x)) {
            return StringSolutionDelta.newlyActivated(this, x);
        }
        return StringSolutionDelta.makeEmpty(this);
    }

    public boolean isActive(StringSolutionVariable x) {
        return this.active.contains(x);
    }

    public void recordStringStatementUseDependency(StringSolutionVariable v, StmtAndContext sac) {
        this.depRecorder.recordRead(v, sac);
    }

    public void recordStringStatementDefineDependency(StringSolutionVariable v, StmtAndContext sac) {
        this.depRecorder.recordWrite(v, sac);
    }

    //    public Set<StmtAndContext> getDefinedBy(StringSolutionVariable v) {
    //        return this.stringDependencies.getWrittenBy(v);
    //    }
    //
    //    public Set<StmtAndContext> getUsedBy(StringSolutionVariable v) {
    //        return this.stringDependencies.getUsedBy(v);
    //    }

    public String getStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("StringConstraints Statistics:\n");
        sb.append("    total variables: " + this.map.size() + "\n");
        sb.append("    active variables: " + this.active.size() + "\n");
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

    //    public void printSVRDependencyTree(StringSolutionVariable svr) {
    //        this.stringDependencies.printSVRDependencyTree(svr, this);
    //    }

}
