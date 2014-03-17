package test.pointer;

public class Return {

    public static void main(String[] args) {
        @SuppressWarnings("unused")
        Object o = foo(42);
    }

    private static Object foo(int x) {
        if (x > 7) {
            return new Object();
        } else {
            return new Return();
        }
    }
}
