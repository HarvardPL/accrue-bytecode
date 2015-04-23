package analysis.pointer.graph;

import java.util.HashSet;
import java.util.Set;

import analysis.pointer.analyses.AString;

public class StringSolutionDelta {

    private final StringSolution sc;
    private Set<StringSolutionVariable> newlyActivated;
    private Set<StringSolutionVariable> updated;

    /* Factory Methods */

    public static final StringSolutionDelta makeEmpty(StringSolution sc) {
        return new StringSolutionDelta(sc);
    }

    public void addUpdated(StringSolutionVariable updatedVar) {
        this.updated.add(updatedVar);

    }

    public void addNewlyActivated(StringSolutionVariable newlyActivatedVar) {
        this.newlyActivated.add(newlyActivatedVar);
    }

    //    public static final StringSolutionDelta makeWithNeedUses(StringSolution sc, Set<StringSolutionVariable> needUses) {
    //        return new StringSolutionDelta(sc, needUses, AnalysisUtil.<StringSolutionVariable> createConcurrentSet());
    //    }
    //
    //    public static final StringSolutionDelta makeWithNeedDefs(StringSolution sc, Set<StringSolutionVariable> needDefs) {
    //        return new StringSolutionDelta(sc, AnalysisUtil.<StringSolutionVariable> createConcurrentSet(), needDefs);
    //    }

    //    public static final StringSolutionDelta make(StringSolution sc, Set<StringSolutionVariable> needUses,
    //                                                 Set<StringSolutionVariable> needDefs) {
    //        return new StringSolutionDelta(sc, needUses, needDefs);
    //    }

    /* Constructors */

    public StringSolutionDelta(StringSolution sc) {
        this.sc = sc;
        this.newlyActivated = new HashSet<>();
        this.updated = new HashSet<>();
    }

    /* Logic */

    public AString getAStringFor(StringSolutionVariable svr) {
        if (this.updated.contains(svr)) {
            return this.sc.getAStringFor(svr);
        }
        return null;
    }

    public boolean isEmpty() {
        return this.newlyActivated.isEmpty() && this.updated.isEmpty();
    }

    public void combine(StringSolutionDelta that) {
        assert this.sc == that.sc;
        this.newlyActivated.addAll(that.newlyActivated);
        this.updated.addAll(that.updated);
    }

    //    public Set<StmtAndContext> getStatementsNeededByStringUpdates() {
    //        Set<StmtAndContext> s = AnalysisUtil.createConcurrentSet();
    //
    //        for (StringSolutionVariable v : this.needDefs) {
    //            s.addAll(this.sc.getDefinedBy(v));
    //        }
    //
    //        for (StringSolutionVariable v : this.needUses) {
    //            s.addAll(this.sc.getUsedBy(v));
    //        }
    //
    //        return s;
    //    }

    @Override
    public String toString() {
        return "StringConstraintDelta [newlyActivated=" + this.newlyActivated + ", updated=" + this.updated + "]";
    }

}
