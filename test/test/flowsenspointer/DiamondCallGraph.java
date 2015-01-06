package test.flowsenspointer;

public class DiamondCallGraph extends TestBaseClass {

    static Object f = new Object();

    public static void main(String[] args) {
        Object o = foo();
        mostRecent(o);
        pointsToSize(o, 1);

        Object o2 = bar();

        mostRecent(o);
        pointsToSize(o, 1);

        mostRecent(o2);
        pointsToSize(o2, 1);
    }

    private static Object bar() {
        return baz();
    }

    private static Object foo() {
        return baz();
    }

    private static Object baz() {
        return f;
    }
}
