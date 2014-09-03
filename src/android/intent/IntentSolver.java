package android.intent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import util.WorkQueue;
import analysis.string.AbstractString;
import android.intent.model.AbstractIntent;
import android.intent.statements.IntentStatement;

public class IntentSolver {

    public static Map<Integer, AbstractIntent> solve(Set<IntentStatement> statements,
                                                     Map<Integer, AbstractString> stringResults) {
        Map<Integer, Set<IntentStatement>> dependencies = new HashMap<>();
        WorkQueue<IntentStatement> q = new WorkQueue<>();
        q.addAll(statements);

        IntentRegistrar registrar = new IntentRegistrar();

        while (!q.isEmpty()) {
            IntentStatement ss = q.poll();

            //            System.err.println("Proccessing: " + ss);
            //            System.err.println("INPUT " + map);
            ss.process(registrar, stringResults);
            //            System.err.println("OUTPUT " + map);

            // record reads as dependencies
            Set<Integer> read = registrar.getAndClearReadVariables();
            for (Integer readVar : read) {
                Set<IntentStatement> deps = dependencies.get(readVar);
                if (deps == null) {
                    deps = new LinkedHashSet<>();
                    dependencies.put(readVar, deps);
                }
                deps.add(ss);
            }

            // if the map changed then add dependencies for changed variables to the queue
            Set<Integer> changedVars = registrar.getAndClearChangedVariables();
            for (Integer sv : changedVars) {
                Set<IntentStatement> deps = dependencies.get(sv);
                if (deps != null) {
                    q.addAll(deps);
                }
            }
        }

        return registrar.getAnalysisResults();
    }
}
