package test.pdg.sig;

public class ArrayCopy {

    static int[] x = new int[4];
    static int[] y = new int[4];

    public static void main(String[] args) {
        x[0] = 5;
        y[0] = 6;
        System.arraycopy(x, 0, y, 0, 3);

    }

}
