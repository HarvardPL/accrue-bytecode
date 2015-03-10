package analysis.pointer.graph;

import java.util.Set;

import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;

public class StringConstraintDelta {

    private final StringConstraints sc;
    private Set<StringVariableReplica> svrs;

    /* Factory Methods */

    public static final StringConstraintDelta makeEmpty(StringConstraints sc) {
        return new StringConstraintDelta(sc, AnalysisUtil.<StringVariableReplica> createConcurrentSet());
    }

    public static final StringConstraintDelta make(StringConstraints sc, StringVariableReplica svr) {
        return new StringConstraintDelta(sc, AnalysisUtil.createConcurrentSingletonSet(svr));
    }

    public static final StringConstraintDelta make(StringConstraints sc, Set<StringVariableReplica> svrs) {
        return new StringConstraintDelta(sc, svrs);
    }

    /* Constructors */

    public StringConstraintDelta(StringConstraints sc, Set<StringVariableReplica> svrs) {
        this.sc = sc;
        this.svrs = svrs;
    }

    /* Logic */

    public Optional<AString> getAStringFor(StringVariableReplica svr) {
        if (this.svrs.contains(svr)) {
            return Optional.some(this.sc.getAStringFor(svr));
        }
        else {
            return Optional.none();
        }
    }

    public boolean isEmpty() {
        return this.svrs.isEmpty();
    }

    public void combine(StringConstraintDelta that) {
        this.svrs.addAll(that.svrs);

        //        Set<StringVariableReplica> svrs = AnalysisUtil.createConcurrentSet();
        //        svrs.addAll(this.svrs);
        //        svrs.addAll(that.svrs);
        //        this.svrs = svrs;
    }

    public Set<StringVariableReplica> getChangedStringVariables() {
        return this.svrs;
    }

    @Override
    public String toString() {
        return "StringConstraintDelta [svrs=" + svrs + "]";
    }

}
