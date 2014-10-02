package test.instruction;

public class Phi {
    static boolean y;
    static int z;

    public static void main(String[] args) {

        int x;
        if (y) {
            x = 42;
        }
        else {
            x = 43;
        }
        z = x;

    }
}
