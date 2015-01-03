package test.flowsenspointer;

public class SCall extends TestBaseClass {

    public static void main(String[] args) {
        Object o = SCall.foo();
        // o --> most-recent a1
        mostRecent(o);
    }

    public static Object foo() {
        return new Object(); // a1
    }

}
