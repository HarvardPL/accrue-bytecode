package analysis.string;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import analysis.AnalysisUtil;
import analysis.string.StringVariableFactory.StringVariable;
import analysis.string.statements.StringStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.IR;

public class StringAnalysisResults {
    // TODO could make this soft reference if memory becomes an issue
    private final Map<IMethod, Map<StringVariable, AbstractString>> results;
    private final StringVariableFactory factory;

    public StringAnalysisResults(StringVariableFactory factory) {
        this.results = new LinkedHashMap<>();
        this.factory = factory;
    }

    public Map<StringVariable, AbstractString> getResultsForMethod(IMethod m) {
        Map<StringVariable, AbstractString> result = this.results.get(m);
        if (result == null) {
            IR ir = AnalysisUtil.getIR(m);
            Set<StringStatement> statements = CollectStringStatements.collect(ir, this.factory, this);
            System.err.println(statements.size() + " STATEMENTS:");
            for (StringStatement s : statements) {
                System.err.println("\t" + s);
            }
            result = StringSolver.solve(statements);
            this.results.put(m, result);
        }
        return result;
    }

    public StringVariableFactory getFactory() {
        return this.factory;
    }
}
