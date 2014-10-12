package test.instruction;

public class InvokeStatic {
    static int z;
    public static void main(String[] args) {
        z = foo(42);
    }

    static int y;

    static int foo(int x) {

        y = x;
        return 43;
    }
}
