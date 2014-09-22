package test.instruction;

public class Monitor {
    int y;
    public static void main(String[] args) {
        Monitor w = new Monitor();
        w.foo(42);
    }

    void foo(int x) {
        synchronized (this) {
            y = x;
        }
    }
}
