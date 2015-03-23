package analysis.pointer.graph;

import java.util.Set;

import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;

public class StringConstraintDelta {

    private final StringConstraints sc;
    private Set<StringVariableReplica> needUses;
    private Set<StringVariableReplica> needDefs;

    /* Factory Methods */

    public static final StringConstraintDelta makeEmpty(StringConstraints sc) {
        return new StringConstraintDelta(sc,
                                         AnalysisUtil.<StringVariableReplica> createConcurrentSet(),
                                         AnalysisUtil.<StringVariableReplica> createConcurrentSet());
    }

    public static final StringConstraintDelta makeWithNeedUses(StringConstraints sc, StringVariableReplica needsUses) {
        return new StringConstraintDelta(sc,
                                         AnalysisUtil.createConcurrentSingletonSet(needsUses),
                                         AnalysisUtil.<StringVariableReplica> createConcurrentSet());
    }

    public static final StringConstraintDelta makeWithNeedDefs(StringConstraints sc, StringVariableReplica needsDefs) {
        return new StringConstraintDelta(sc,
                                         AnalysisUtil.<StringVariableReplica> createConcurrentSet(),
                                         AnalysisUtil.createConcurrentSingletonSet(needsDefs));
    }

    public static final StringConstraintDelta makeWithNeedUses(StringConstraints sc, Set<StringVariableReplica> needUses) {
        return new StringConstraintDelta(sc, needUses, AnalysisUtil.<StringVariableReplica> createConcurrentSet());
    }

    public static final StringConstraintDelta makeWithNeedDefs(StringConstraints sc, Set<StringVariableReplica> needDefs) {
        return new StringConstraintDelta(sc, AnalysisUtil.<StringVariableReplica> createConcurrentSet(), needDefs);
    }

    public static final StringConstraintDelta make(StringConstraints sc, Set<StringVariableReplica> needUses,
                                                   Set<StringVariableReplica> needDefs) {
        return new StringConstraintDelta(sc, needUses, needDefs);
    }

    /* Constructors */

    public StringConstraintDelta(StringConstraints sc, Set<StringVariableReplica> needUses,
                                 Set<StringVariableReplica> needDefs) {
        this.sc = sc;
        this.needUses = needUses;
        this.needDefs = needDefs;
    }

    /* Logic */

    public Optional<AString> getAStringFor(StringVariableReplica svr) {
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

    public void combine(StringConstraintDelta that) {
        assert this.sc == that.sc;
        this.needUses.addAll(that.needUses);
        this.needDefs.addAll(that.needDefs);

        //        Set<StringVariableReplica> svrs = AnalysisUtil.createConcurrentSet();
        //        svrs.addAll(this.svrs);
        //        svrs.addAll(that.svrs);
        //        this.svrs = svrs;
    }

    public Set<StmtAndContext> getStatementsNeededByStringUpdates() {
        Set<StmtAndContext> s = AnalysisUtil.createConcurrentSet();

        for (StringVariableReplica v : this.needDefs) {
            s.addAll(this.sc.getDefinedBy(v));
        }

        for (StringVariableReplica v : this.needUses) {
            s.addAll(this.sc.getUsedBy(v));
        }

        return s;
    }

    @Override
    public String toString() {
        return "StringConstraintDelta [needDefs=" + this.needDefs + ", needUses=" + this.needUses + "]";
    }

}
