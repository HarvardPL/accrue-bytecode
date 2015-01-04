package test.flowsenspointer;

public class VCall2 extends TestBaseClass {

    public static void main(String[] args) {
        VCall2 v = new VCall2(); // a1
        mostRecent(v);
        pointsToSize(v, 1);

        Object o = v.foo();
        // "this" in method foo() --> most-recent a1
        // o --> most-recent a2
        mostRecent(o);
        pointsToSize(o, 1);
    }

    public Object foo() {
        mostRecent(this);
        pointsToSize(this, 1);

        return new Object(); // a2
    }
}
