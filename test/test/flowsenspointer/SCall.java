package test.flowsenspointer;

public class SCall {

    public static void main(String[] args) {
        Object o = SCall.foo();
        o.hashCode();
        // o --> most-recent a1
    }

    public static Object foo() {
        return new Object(); // a1
    }

}
