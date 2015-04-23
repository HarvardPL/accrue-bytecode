package analysis.pointer.graph;

import java.util.Set;

import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

public class StringSolutionDelta {

    private final StringSolution sc;
    private Set<StringSolutionVariable> needUses;
    private Set<StringSolutionVariable> needDefs;

    /* Factory Methods */

    public static final StringSolutionDelta makeEmpty(StringSolution sc) {
        return new StringSolutionDelta(sc,
                                       AnalysisUtil.<StringSolutionVariable> createConcurrentSet(),
                                       AnalysisUtil.<StringSolutionVariable> createConcurrentSet());
    }

    public static final StringSolutionDelta makeWithNeedUses(StringSolution sc, StringSolutionVariable needsUses) {
        return new StringSolutionDelta(sc,
                                       AnalysisUtil.createConcurrentSingletonSet(needsUses),
                                       AnalysisUtil.<StringSolutionVariable> createConcurrentSet());
    }

    public static final StringSolutionDelta makeWithNeedDefs(StringSolution sc, StringSolutionVariable needsDefs) {
        return new StringSolutionDelta(sc,
                                       AnalysisUtil.<StringSolutionVariable> createConcurrentSet(),
                                       AnalysisUtil.createConcurrentSingletonSet(needsDefs));
    }

    public static final StringSolutionDelta makeWithNeedUses(StringSolution sc, Set<StringSolutionVariable> needUses) {
        return new StringSolutionDelta(sc, needUses, AnalysisUtil.<StringSolutionVariable> createConcurrentSet());
    }

    public static final StringSolutionDelta makeWithNeedDefs(StringSolution sc, Set<StringSolutionVariable> needDefs) {
        return new StringSolutionDelta(sc, AnalysisUtil.<StringSolutionVariable> createConcurrentSet(), needDefs);
    }

    public static final StringSolutionDelta make(StringSolution sc, Set<StringSolutionVariable> needUses,
                                                 Set<StringSolutionVariable> needDefs) {
        return new StringSolutionDelta(sc, needUses, needDefs);
    }

    /* Constructors */

    public StringSolutionDelta(StringSolution sc, Set<StringSolutionVariable> needUses,
                               Set<StringSolutionVariable> needDefs) {
        this.sc = sc;
        this.needUses = needUses;
        this.needDefs = needDefs;
    }

    /* Logic */

    public Optional<AString> getAStringFor(StringSolutionVariable svr) {
        if (this.needUses.contains(svr)) {
            return Optional.some(this.sc.getAStringFor(svr));
        }
        else {
            return Optional.none();
        }
    }

    public boolean isEmpty() {
        return this.needUses.isEmpty() && this.needDefs.isEmpty();
    }

    public void combine(StringSolutionDelta that) {
        assert this.sc == that.sc;
        this.needUses.addAll(that.needUses);
        this.needDefs.addAll(that.needDefs);
    }

    public Set<StmtAndContext> getStatementsNeededByStringUpdates() {
        Set<StmtAndContext> s = AnalysisUtil.createConcurrentSet();

        for (StringSolutionVariable v : this.needDefs) {
            s.addAll(this.sc.getDefinedBy(v));
        }

        for (StringSolutionVariable v : this.needUses) {
            s.addAll(this.sc.getUsedBy(v));
        }

        return s;
    }

    @Override
    public String toString() {
        return "StringConstraintDelta [needDefs=" + this.needDefs + ", needUses=" + this.needUses + "]";
    }

}
