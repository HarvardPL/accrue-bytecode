package analysis.pointer.graph;

import java.util.Map;

import util.optional.Optional;
import analysis.AnalysisUtil;
import analysis.pointer.analyses.StringInstanceKey;

public class StringConstraints {

    private final Map<StringVariableReplica, StringInstanceKey> map;

    /* Factory Methods */

    public static StringConstraints make() {
        return new StringConstraints();
    }

    /* Constructors */

    private StringConstraints() {
        this.map = AnalysisUtil.createConcurrentHashMap();
    }

    /* Logic */

    public Optional<StringInstanceKey> getAStringFor(StringVariableReplica svr) {
        StringInstanceKey shat = this.map.get(svr);
        return shat == null ? Optional.<StringInstanceKey> none() : Optional.some(shat);
    }

    public StringConstraintDelta joinAt(StringVariableReplica svr, StringInstanceKey shat) {
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
