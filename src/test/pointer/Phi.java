package test.pointer;


public class Phi {

    public static void main(String[] args) {
        Object o;
        Object o1 = new Object();
        Object o2 = new Object();
        if (args.length == 1) {
            o = o1;
        } else {
            o = o2;
        }
        foo(o);
    }

    private static void foo(@SuppressWarnings("unused") Object o) {
        //foo
    }
}
