package analysis.dataflow.interprocedural.pdg;

import java.util.Collections;
import java.util.Map;

import analysis.dataflow.interprocedural.pdg.graph.node.PDGNode;
import analysis.dataflow.util.AbstractLocation;
import analysis.dataflow.util.VarContext;

public class PDGContext extends VarContext<PDGNode> {

    public PDGContext() {
        this(Collections.<Integer, PDGNode> emptyMap(), null, null);
    }

    protected PDGContext(Map<Integer, PDGNode> locals, PDGNode returnResult, PDGNode exceptionValue) {
        super(locals, null, returnResult, exceptionValue, false, null);
    }

    @Override
    public PDGNode getLocation(AbstractLocation loc) {
        // TODO Memoize locations here?
        throw new UnsupportedOperationException("Get these from the interproc instead???");
    }

}
