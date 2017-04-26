package util;

public class Histogram {
    int[] keys = new int[] { 0, 1, 2, 3, 4, 5, 10, 50, 100, 500, 1000, 5000,
            10000, 50000, 100000, 500000, Integer.MAX_VALUE };
    int[] counts = new int[this.keys.length];

    int weightedTotal = 0;
    public void record(int x) {
        this.weightedTotal += x;
        for (int i = 0; i < this.keys.length; i++) {
            if (x <= this.keys[i]) {
                this.counts[i]++;
                return;
            }
        }
        throw new RuntimeException("No idea what happened " + x);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        int last = 0;
        int total = 0;
        for (int i = 0; i < this.keys.length; i++) {
            if (this.counts[i] != 0) {
                sb.append("  " + last + "-" + this.keys[i] + " : ");
                sb.append(this.counts[i]);
                sb.append("\n");
                total+=this.counts[i];
            }
            last = this.keys[i] + 1;
        }
        sb.append(" TOTAL: " + total);
        sb.append(" WEIGHTED TOTAL: " + this.weightedTotal);
        return sb.toString();
    }
}
