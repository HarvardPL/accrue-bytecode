package analysis.string;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.WorkQueue;
import analysis.string.StringVariableFactory.StringVariable;
import analysis.string.statements.StringStatement;

public class StringSolver {

    public static Map<StringVariable, AbstractString> solve(Set<StringStatement> statements) {
        Map<StringVariable, Set<StringStatement>> dependencies = new HashMap<>();
        StringVariableMap map = new StringVariableMap();
        WorkQueue<StringStatement> q = new WorkQueue<>();
        q.addAll(statements);

        while (!q.isEmpty()) {
            StringStatement ss = q.poll();

            //            System.err.println("Proccessing: " + ss);
            //            System.err.println("INPUT " + map);
            ss.process(map);
            //            System.err.println("OUTPUT " + map);

            // record reads as dependencies
            Set<StringVariable> read = map.getAndClearReadVariables();
            for (StringVariable readVar : read) {
                Set<StringStatement> deps = dependencies.get(readVar);
                if (deps == null) {
                    deps = new LinkedHashSet<>();
                    dependencies.put(readVar, deps);
                }
                deps.add(ss);
            }

            // if the map changed then add dependencies for changed variables to the queue
            Set<StringVariable> changedVars = map.getAndClearChangedVariables();
            for (StringVariable sv : changedVars) {
                Set<StringStatement> deps = dependencies.get(sv);
                if (deps != null) {
                    q.addAll(deps);
                }
            }
        }

        return map.getMap();
    }
}
