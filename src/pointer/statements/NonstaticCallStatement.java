package pointer.statements;

import java.util.List;

import pointer.LocalNode;
import pointer.PointsToGraph;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Points to statement for a call to a virtual method
 */
public class NonstaticCallStatement implements PointsToStatement {

    public NonstaticCallStatement(CallSiteReference callSite, IR ir, MethodReference callee, LocalNode receiver, List<LocalNode> actuals,
            LocalNode resultNode, LocalNode exceptionNode) {
        // TODO Auto-generated constructor stub
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public TypeReference getExpectedType() {
        // TODO Auto-generated method stub
        return null;
    }

}
