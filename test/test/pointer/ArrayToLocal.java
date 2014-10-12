package test.pointer;

public class ArrayToLocal {

    public static void main(String[] args) {
        Object o1 = new Object();
        Object o2 = new Object();
        Object[] xs = { o1, o2 };
        @SuppressWarnings("unused")
        Object y = xs[0];
    }
}
