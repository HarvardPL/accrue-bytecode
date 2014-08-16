package android.statements;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class InterestingMethodProcessor {

    public static void process(IMethod m, Context context, HeapAbstractionFactory haf, PointsToGraph g,
                                    StatementRegistrar registrar) {
        System.err.println("Found interesting method: " + PrettyPrinter.methodString(m) + " in context: " + context);
    }

}
