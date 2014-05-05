package analysis.dataflow.interprocedural.pdg.graph.node;

import java.util.List;

import analysis.dataflow.interprocedural.pdg.PDGContext;

public class ProcedureSummaryNodes {

    private List<PDGNode> formals;

    public PDGNode getFormal(int j) {
        assert j < formals.size();
        // TODO Auto-generated method stub
        return null;
    }

    public PDGContext getEntryContext() {
        // TODO Auto-generated method stub
        return null;
    }

    public PDGContext getNormalExitContext() {
        // TODO Auto-generated method stub
        return null;
    }
    
    public PDGContext getExceptionalExitContext() {
        // TODO Auto-generated method stub
        return null;
    }
}
