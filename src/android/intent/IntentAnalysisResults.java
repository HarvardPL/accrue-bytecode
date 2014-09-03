package android.intent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import analysis.AnalysisUtil;
import analysis.string.StringAnalysisResults;
import android.intent.model.AbstractIntent;
import android.intent.statements.IntentStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;

public class IntentAnalysisResults {
    // TODO could make this soft reference if memory becomes an issue
    private final Map<IMethod, Map<Integer, AbstractIntent>> results = new LinkedHashMap<>();
    private final StringAnalysisResults stringResults;

    public IntentAnalysisResults(StringAnalysisResults stringResults) {
        this.stringResults = stringResults;
    }

    public Map<Integer, AbstractIntent> getResultsForMethod(IMethod m) {
        Map<Integer, AbstractIntent> result = this.results.get(m);
        if (result == null) {
            IR ir = AnalysisUtil.getIR(m);
            Set<IntentStatement> statements = CollectIntentStatements.collect(ir);
            System.err.println(statements.size() + " STATEMENTS:");
            for (IntentStatement s : statements) {
                System.err.println("\t" + s);
            }
            result = IntentSolver.solve(statements, stringResults.getIntegerResultsForMethod(m));
            this.results.put(m, result);
        }
        return result;
    }

    public AbstractIntent getResultsForLocal(int variableNumber, IMethod m) {
        Map<Integer, AbstractIntent> resultsForMethod = getResultsForMethod(m);
        return resultsForMethod.get(variableNumber);
    }
}
