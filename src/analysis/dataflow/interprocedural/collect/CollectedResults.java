package analysis.dataflow.interprocedural.collect;

import analysis.dataflow.interprocedural.AnalysisResults;

public class CollectedResults implements AnalysisResults {

    private int nullPointerCounter;
    private int possibleNullPointerCounter;
    private int arithmeticExceptionCounter;
    private int possibleArithmeticExceptionCounter;
    private int castsRemoved;
    private int totalCasts;

    public void recordNullPointerException() {
        nullPointerCounter++;
    }

    public void recordPossibleNullPointerException() {
        possibleNullPointerCounter++;
    }

    public int getNullPointerExceptionCount() {
        return nullPointerCounter;
    }

    public int getPossibleNullPointerExceptionCount() {
        return possibleNullPointerCounter;
    }

    public int getArithmeticExceptionCount() {
        return arithmeticExceptionCounter;
    }

    public void recordArithmeticException() {
        arithmeticExceptionCounter++;
    }

    public int getPossibleArithmeticExceptionCount() {
        return possibleArithmeticExceptionCounter;
    }

    public void recordPossibleArithmeticException() {
        possibleArithmeticExceptionCounter++;
    }

    public int getCastRemovalCount() {
        return castsRemoved;
    }

    public void recordCastRemoval() {
        castsRemoved++;
    }

    public int getTotalCastCount() {
        return totalCasts;
    }

    public void recordCast() {
        totalCasts++;
    }
}
