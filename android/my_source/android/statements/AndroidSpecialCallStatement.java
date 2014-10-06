package android.statements;

import java.util.List;

import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.SpecialCallStatement;
import android.AndroidConstants;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class AndroidSpecialCallStatement extends SpecialCallStatement {

    protected AndroidSpecialCallStatement(CallSiteReference callSite, IMethod caller, IMethod callee,
                                          ReferenceVariable result, ReferenceVariable receiver,
                                          List<ReferenceVariable> actuals, ReferenceVariable exception,
                                          MethodSummaryNodes calleeSummary) {
        super(callSite, caller, callee, result, receiver, actuals, exception, calleeSummary);
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext sac) {

        IMethod resolvedCallee = getResolvedCallee();
        if (AndroidConstants.INTERESTING_METHODS.contains(resolvedCallee)) {
                InterestingMethodProcessor.process(resolvedCallee, context, haf, g, registrar);
        }
        return super.process(context, haf, g, delta, registrar, sac);
    }
}
