package analysis.pointer.statements;

import java.util.List;

import util.print.PrettyPrinter;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.PointsToGraph;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;

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
     * @param ir
     *            Code triggering the initialization
     * @param i
     *            Instruction triggering the initialization
     */
    public ClassInitStatement(List<IMethod> clinits, IR ir, SSAInstruction i) {
        super(ir, i);
        this.clinits = clinits;
    }

    @Override
    public boolean process(Context context, HeapAbstractionFactory haf, PointsToGraph g, StatementRegistrar registrar) {
        boolean added = g.addClassInitializers(clinits);
        // TODO process exceptions thrown by a clinit
        // TODO add more precise edges to the call graph for a clinit
        // Since we are flow insensitive, it is imprecise and unsound to treat the triggering method as the caller since
        // it may not actually call this init, to be sound we could throw the exceptions in any possible caller, but
        // this would be very imprecise and could blow up the call graph and points-to graph.
        // As a compromise we don't do anything here, and use this statement only to trigger the analysis of the
        // statements in the clinit method
        if (PointsToAnalysis.outputLevel >= 1 && added) {
            System.err.println("CLINIT: " + PrettyPrinter.instructionString(getInstruction(), getCode()) + " in "
                                            + PrettyPrinter.methodString(getCode().getMethod()) + " in context: "
                                            + context);

            for (IMethod m : clinits) {
                System.err.println("\t" + PrettyPrinter.methodString(m));
            }

        }

        return false;
    }
}
