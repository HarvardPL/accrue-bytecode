package pointer.statements;

import pointer.LocalNode;
import pointer.PointsToGraph;
import pointer.analyses.HeapAbstractionFactory;

import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.TypeReference;

/**
 * Points to graph node for a "new" statement, e.g. Object o = new Object()
 * TODO I believe this is the allocation not the constructor call
 */
public class NewStatement implements PointsToStatement {

    public NewStatement(LocalNode result, NewSiteReference newSite, IR ir) {
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
