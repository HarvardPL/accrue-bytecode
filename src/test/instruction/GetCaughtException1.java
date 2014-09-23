package test.instruction;

public class GetCaughtException1 {
    static int x;
    static int y;
    static int z;
    static int w;

    public static void main(String[] args) {

        try {
            w = x / y;
        } catch (ArithmeticException e) {
            e.hashCode();
        }
    }
}
