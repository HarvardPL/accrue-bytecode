package analysis.dataflow.interprocedural.collect;

import analysis.dataflow.interprocedural.AnalysisResults;

public class CollectedResults implements AnalysisResults {

    private int nullPointerCounter;
    private int arithmeticExceptionCounter;
    private int castsRemoved;

    public void recordNullPointerException() {
        nullPointerCounter++;
    }

    public int getNullPointerExceptionCount() {
        return nullPointerCounter;
    }

    public int getArithmeticExceptionCount() {
        return arithmeticExceptionCounter;
    }

    public void recordArithmeticException() {
        arithmeticExceptionCounter++;
    }

    public int getCastRemovalCount() {
        return castsRemoved;
    }

    public void recordCastRemoval() {
        castsRemoved++;
    }
}
