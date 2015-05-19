package analysis.pointer.graph.strings;

import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.analyses.ReflectiveHAF;
import analysis.pointer.engine.DependencyRecorder;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

public class StringSolution {
    /**
     * The set of active StringSolutionVariable.
     */
    private final Set<StringLikeLocationReplica> active;

    /**
     * Map from PointsToGraphNode to abstract string values. The only acceptable PointsToGraphNodes are
     * StringSolutionVariables and ObjectFields. StringSolutionVariables are used to represent local String-valued
     * variables, local StringBuilder (i.e., StringBuilders that have not escaped local scope) and static
     * fields.ObjectFields are used to represent fields of objects of type String or StringBuilder.
     */
    private final ConcurrentMap<StringLikeLocationReplica, AString> map;

    /**
     * If s1 ∈ upperBounds.get(s2), then s1 must always be an upper bound of s2. So if s2 changes, then we may need to
     * update s1.
     */
    private final ConcurrentMap<StringLikeLocationReplica, Set<StringLikeLocationReplica>> upperBounds;

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
        this.upperBounds = AnalysisUtil.createConcurrentHashMap();
    }

    /* Logic */

    public AString getAStringFor(StringLikeLocationReplica svr) {
        AString shat = this.map.get(svr);
        if (shat == null) {
            return haf.getAStringBottom();
        }
        else {
            return shat;
        }
    }

    public StringSolutionDelta joinAt(StringLikeLocationReplica svr, AString shat) {
        StringSolutionDelta delta = StringSolutionDelta.makeEmpty(this);
        joinToVarAndBoundingVars(svr, shat, delta);
        return delta;
    }

    /**
     * Increase svr to shat, and, if that was a change, propagate that to the variables that must be upper bounds
     *
     * @param svr
     * @param shat
     * @return
     */
    private void joinToVarAndBoundingVars(StringLikeLocationReplica svr, AString shat, StringSolutionDelta delta) {
        assert this.isActive(svr) : svr + "\n" + this.active;
        // Logger.println("[joinAt] isActive " + svr + " joining in " + shat);
        AString current = this.map.get(svr);
        AString newval = null;
        boolean updated = false;
        if (current == null) {
            AString existing = this.map.putIfAbsent(svr, shat);
            if (existing == null) {
                // we definitely updated.
                updated = true;
                newval = shat;
            }
            else {
                // someone beat us to it
                current = existing;
            }
        }
        if (!updated) {
            while (true) {
                newval = current.join(shat);
                if (newval.equals(current)) {
                    // We didn't change the AString for svr.
                    return;
                }
                // try to change it
                if (this.map.replace(svr, current, newval)) {
                    // We successfully changed it!
                    updated = true;
                    break;
                }

                // someone else snuck in. Try again.
                current = this.map.get(svr);
            }
        }

        assert newval != null;
        // if we get to here then we successfully changed it to newval.
        delta.addUpdated(svr);

        // Here, we need to propagate newval to variables that are upper bounds.
        Set<StringLikeLocationReplica> ub = this.upperBounds.get(svr);
        if (ub != null) {
            for (StringLikeLocationReplica u : ub) {
                this.joinToVarAndBoundingVars(u, newval, delta);
            }
        }
    }

    /**
     * Add the fact that svr1 should (always) be an upper bound of svr2.
     *
     * @param svr1
     * @param svr2
     * @return
     */
    public StringSolutionDelta upperBounds(StringLikeLocationReplica svr1, StringLikeLocationReplica svr2) {
        assert this.isActive(svr1) && this.isActive(svr2) : "svr1 : " + svr1 + " active? " + this.isActive(svr1)
                + "; svr2 : " + svr2 + " active? " + this.isActive(svr2);
        Set<StringLikeLocationReplica> ub = this.upperBounds.get(svr2);
        if (ub == null) {
            ub = AnalysisUtil.createConcurrentSet();
            Set<StringLikeLocationReplica> existing = this.upperBounds.putIfAbsent(svr2, ub);
            if (existing != null) {
                ub = existing;
            }
        }
        if (ub.add(svr1)) {
            // This is a new upper bound, so make sure it is satisfied by raising the level of svr1
            AString a = this.map.get(svr2);
            if (a != null) {
                return this.joinAt(svr1, a);
            }
        }
        return StringSolutionDelta.makeEmpty(this);
    }

    public StringSolutionDelta activate(StringLikeLocationReplica x) {
        StringSolutionDelta d = StringSolutionDelta.makeEmpty(this);
        if (this.active.add(x)) {
            d.addNewlyActivated(x);
        }
        return d;
    }

    public boolean isActive(StringLikeLocationReplica x) {
        return this.active.contains(x);
    }

    public void recordStringStatementUseDependency(StringLikeLocationReplica v, StmtAndContext sac) {
        this.depRecorder.recordRead(v, sac);
    }

    public void recordStringStatementDefineDependency(StringLikeLocationReplica v, StmtAndContext sac) {
        this.depRecorder.recordWrite(v, sac);
    }

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

    public void printStringConstraints() {
        System.err.println("StringSolution:");
        for (Entry<StringLikeLocationReplica, AString> kv : this.map.entrySet()) {
            StringLikeLocationReplica k = kv.getKey();
            AString v = kv.getValue();
            if (this.isActive(k)) {
                System.err.println("  " + k + " ↦ " + v);
            }
            else {
                System.err.println("  " + k + " ↦ %inactive%");
            }
        }
    }

}
