package test.instruction;

public class InvokeVirtual {
    int y;
    static int z;

    public static void main(String[] args) {
        InvokeVirtual w = new InvokeVirtual();
        z = w.foo(42);
    }

    public int foo(int x) {
        y = x;
        return x;
    }
}
