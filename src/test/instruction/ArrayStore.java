package test.instruction;

public class ArrayStore {
    static int[] x;
    static int y;
    public static void main(String[] args) {
        x = new int[4];
        x[0] = y;
    }
}
