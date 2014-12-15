package test.flowsenspointer;

public class New {

    Object f;

    public static void main(String[] args) {
        New s = new New();
        s.foo();
    }

    @SuppressWarnings("static-method")
    private void foo() {
        @SuppressWarnings("unused")
        New s = new New();
    }

}
