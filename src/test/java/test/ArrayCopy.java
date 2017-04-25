package test;

public class ArrayCopy {
    static int[] x = new int[3];
    static int[] y = new int[2];

    public static void main(String[] args) {
        x[1] = 46;
        System.arraycopy(x, 1, y, 0, 2);
    }
}