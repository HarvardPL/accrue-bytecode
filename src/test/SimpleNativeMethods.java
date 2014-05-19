package test;

public class SimpleNativeMethods {
    static SimpleNativeMethods s;
    static SimpleNativeMethods x;
    static Object y;

    public static void main(String[] args) {
        s = new SimpleNativeMethods();
        x = (SimpleNativeMethods) s.foo();
        y = x.foo();
    }

    native Object foo();
}