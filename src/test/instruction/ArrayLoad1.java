package test.instruction;

public class ArrayLoad1 {
    static int[] x;
    static int y;

    public static void main(String[] args) {
        y = 2;
        x = new int[4];
        x[y] = 42;
        y = x[y];
    }
}
