package analysis.pointer.statements;

import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class StaticOrSpecialMethodCallString extends MethodCallString {

    private final List<OrderedPair<StringVariable, StringVariable>> stringArgumentAndParameters;
    private final StringVariable formalReturn;
    private final StringVariable actualReturn;

    public StaticOrSpecialMethodCallString(IMethod method,
                                  List<OrderedPair<StringVariable, StringVariable>> stringArgumentAndParameters,
                                  StringVariable returnedVariable, StringVariable returnToVariable) {
        super(method);
        this.stringArgumentAndParameters = stringArgumentAndParameters;
        this.formalReturn = returnedVariable;
        this.actualReturn = returnToVariable;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        return this.processCall(this.actualReturn,
                                this.formalReturn,
                                this.stringArgumentAndParameters,
                                context,
                                haf,
                                g,
                                delta,
                                registrar,
                                originator);
    }

    @Override
    public String toString() {
        return "MethodCallStringEscape [stringArgumentAndParameters=" + stringArgumentAndParameters
                + ", returnedVariable=" + formalReturn + ", returnToVariable=" + actualReturn + "]";
    }

}
