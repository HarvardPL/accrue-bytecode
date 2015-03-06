package analysis.pointer.graph;

import java.util.Map;

import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.AString;

public class StringConstraints {

    private final Map<StringVariableReplica, AString> map;

    /* Factory Methods */

    public static StringConstraints make() {
        return new StringConstraints();
    }

    /* Constructors */

    private StringConstraints() {
        this.map = AnalysisUtil.createConcurrentHashMap();
    }

    /* Logic */

    public Optional<AString> getAStringFor(StringVariableReplica svr) {
        AString shat = this.map.get(svr);
        return shat == null ? Optional.<AString> none() : Optional.some(shat);
    }

    public StringConstraintDelta joinAt(StringVariableReplica svr, AString shat) {
        if (this.map.containsKey(svr)) {
            boolean changedp = this.map.get(svr).join(shat);
            return changedp ? StringConstraintDelta.make(this, svr) : StringConstraintDelta.makeEmpty(this);
        }
        else {
            this.map.put(svr, shat);
            return StringConstraintDelta.make(this, svr);
        }
    }

    public StringConstraintDelta upperBounds(StringVariableReplica svr1, StringVariableReplica svr2) {
        if (this.map.containsKey(svr2)) {
            return this.joinAt(svr1, this.map.get(svr2));
        } else {
            return StringConstraintDelta.makeEmpty(this);
        }
    }
}
