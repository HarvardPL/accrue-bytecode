package analysis.pointer.statements;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.recency.RecencyHeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.engine.PointsToAnalysis.StmtAndContext;
import analysis.pointer.graph.GraphDelta;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
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
     * @param clinits class initialization methods that might need to be called in the order they need to be called
     *            (i.e. element j is a super class of element j+1)
     * @param pp program point for the instruction triggering the initialization
     */
    protected ClassInitStatement(List<IMethod> clinits, ProgramPoint pp) {
        super(pp);
        assert !clinits.isEmpty() : "No need for a statment if there are no class inits.";
        this.clinits = clinits;
    }

    @Override
    public GraphDelta process(Context context, RecencyHeapAbstractionFactory haf, PointsToGraph g, GraphDelta delta,
                              StatementRegistrar registrar, StmtAndContext originator) {
        boolean added = g.addClassInitializers(clinits);
        // TODO process exceptions thrown by a clinit
        // TODO add more precise edges to the call graph for a clinit
        // Since we are flow insensitive, it is imprecise and unsound to treat the triggering method as the caller since
        // it may not actually call this init, to be sound we could throw the exceptions in any possible caller, but
        // this would be very imprecise and would blow up the call graph and points-to graph.
        // As a compromise we don't do anything here, and use this statement only to trigger the analysis of the
        // statements in the clinit method, this doesn't blow up the points-to graph, but is unsound.
        if (PointsToAnalysis.outputLevel >= 2 && added) {
            for (IMethod m : clinits) {
                System.err.print("ADDING CLINIT: " + PrettyPrinter.methodString(m));
            }
            System.err.println("\n\tFROM " + PrettyPrinter.methodString(getMethod()) + " in " + context);
        }

        return new GraphDelta(g);
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

    @Override
    public void replaceUse(int useNumber, ReferenceVariable newVariable) {
        throw new UnsupportedOperationException("ClassInitStatement has no uses");
    }

    @Override
    public List<ReferenceVariable> getDefs() {
        return Collections.emptyList();
    }

    @Override
    public List<ReferenceVariable> getUses() {
        return Collections.emptyList();
    }

    @Override
    public boolean mayChangeOrUseFlowSensPointsToGraph() {
        // this is like a call.
        return true;
    }

}
