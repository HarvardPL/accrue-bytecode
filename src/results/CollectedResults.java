package results;

import analysis.dataflow.interprocedural.AnalysisResults;

public class CollectedResults implements AnalysisResults {

    private int nullPointerCounter;
    private int possibleNullPointerCounter;
    private int arithmeticExceptionCounter;
    private int possibleArithmeticExceptionCounter;
    private int castsNotRemoved;
    private int totalCasts;
    private int zeroIntervals;
    private int possibleZeroIntervals;
    private int negIndexExceptionCounter;
    private int possibleNegIndexCounter;

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

    public int getZeroIntervalCount() {
        return zeroIntervals;
    }

    public void recordZeroInterval() {
        zeroIntervals++;
    }

    public int getPossibleZeroIntervalCount() {
        return possibleZeroIntervals;
    }

    public void recordPossibleZeroInterval() {
        possibleZeroIntervals++;
    }

    public int getCastsNotRemovedCount() {
        return castsNotRemoved;
    }

    public void recordCastNotRemoved() {
        castsNotRemoved++;
    }

    public int getTotalCastCount() {
        return totalCasts;
    }

    public void recordCast() {
        totalCasts++;
    }

    public void recordPossibleNegIndex() {
        possibleNegIndexCounter++;
    }

    public void recordNegIndex() {
        negIndexExceptionCounter++;
    }

    public int getPossibleNegIndex() {
        return possibleNegIndexCounter;
    }

    public int getNegIndex() {
        return negIndexExceptionCounter;
    }
}
