package android.statements;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.StatementRegistrar;
import analysis.pointer.statements.PointsToStatement;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

public class CallBackStatement extends PointsToStatement {

    private Set<IMethod> callbackMethods;

    public CallBackStatement(IMethod containingMethod, Set<IMethod> callbacks) {
        super(containingMethod);
        this.callbackMethods = callbacks;
    }

    @Override
    public GraphDelta process(Context context, HeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar) {
        boolean changed = false;
        for (IMethod m : this.callbackMethods) {
            changed |= g.addEntryPoint(m);
        }

        if (PointsToAnalysis.outputLevel >= 2 && changed) {
            for (IMethod m : this.callbackMethods) {
                System.err.print("ADDING CALLBACK: " + PrettyPrinter.methodString(m));
            }
            System.err.println("\n\tFROM " + PrettyPrinter.methodString(this.getMethod()) + " in " + context);
        }

        return new GraphDelta(g);
    }

    @Override
    public String toString() {
        Set<String> names = new LinkedHashSet<>();
        for (IMethod m : this.callbackMethods) {
            names.add(PrettyPrinter.methodString(m));
        }
        return "callbacks: " + names;
    }

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        throw new UnsupportedOperationException("CallBackStatement has no uses");
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.emptyList();
    }

    @Override
    public ReferenceVariable getDef() {
        return null;
    }

    @Override
    public Collection<?> getReadDependencies(Context ctxt,
                                             HeapAbstractionFactory haf) {
        throw new UnsupportedOperationException("Chat with Steve about what should go here.");
    }

    @Override
    public Collection<?> getWriteDependencies(Context ctxt,
                                             HeapAbstractionFactory haf) {
        throw new UnsupportedOperationException("Chat with Steve about what should go here.");
    }

}
