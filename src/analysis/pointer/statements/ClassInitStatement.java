package analysis.pointer.statements;

import java.util.Iterator;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;

/**
 * Points-to statement for class initialization
 */
public class ClassInitStatement extends PointsToStatement {

    /**
     * Class initialization methods that might need to be called in the order they need to be called (i.e. element j is
     * a super class of element j+1)
     */
    private final List<IMethod> clinits;

    /**
     * Create a points-to statement for class initialization
     * 
     * @param clinits
     *            class initialization methods that might need to be called in the order they need to be called (i.e.
     *            element j is a super class of element j+1)
     * @param m
     *            method the instruction triggering the initialization is in
     */
    protected ClassInitStatement(List<IMethod> clinits, IMethod m) {
        super(m);
        assert !clinits.isEmpty() : "No need for a statment if there are no class inits.";
        this.clinits = clinits;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        boolean added = g.addClassInitializers(clinits);
        // TODO process exceptions thrown by a clinit
        // TODO add more precise edges to the call graph for a clinit
        // Since we are flow insensitive, it is imprecise and unsound to treat the triggering method as the caller since
        // it may not actually call this init, to be sound we could throw the exceptions in any possible caller, but
        // this would be very imprecise and would blow up the call graph and points-to graph.
        // As a compromise we don't do anything here, and use this statement only to trigger the analysis of the
        // statements in the clinit method, this doesn't blow up the points-to graph, but is unsound.
        if (PointsToAnalysis.outputLevel >= 0 && added) {
            for (IMethod m : clinits) {
                System.err.print("ADDING CLINIT: " + PrettyPrinter.methodString(m));
            }
            System.err.println("\tFROM " + PrettyPrinter.methodString(getMethod()) + " in " + context);
        }

        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Initialize: [");
        Iterator<IMethod> iter = clinits.iterator();
        sb.append(PrettyPrinter.methodString(iter.next()));
        while (iter.hasNext()) {
            sb.append(", " + PrettyPrinter.methodString(iter.next()));
        }
        sb.append("]");
        return sb.toString();
    }
}
