package test.flowsenspointer;

public class New extends TestBaseClass {

    Object f;

    public static void main(String[] args) {
        New s = new New(); // a1
        // s --> a1
        s.foo();
        mostRecent(s);
        pointsToNull(s.f);
        pointsToSize(s, 1);
        pointsToSize(s.f, 1);
    }

    @SuppressWarnings("static-method")
    private void foo() {
        New s2 = new New(); // a2
        // s2 --> a2
        mostRecent(s2);
        pointsToNull(s2.f);
        pointsToSize(s2, 1);
        pointsToSize(s2.f, 1);
    }

}
