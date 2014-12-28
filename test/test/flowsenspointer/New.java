package test.flowsenspointer;

public class New {

    Object f;

    public static void main(String[] args) {
        New s = new New(); // a1
        // s --> a1
        s.foo();
    }

    @SuppressWarnings("static-method")
    private void foo() {
        @SuppressWarnings("unused")
        New s = new New(); // a2
        // s --> a2
    }

}
