package test.flowsenspointer;

public class VCall2 extends TestBaseClass {

    public static void main(String[] args) {
        VCall2 v = new VCall2(); // a1
        mostRecent(v);
        Object o = v.foo();
        // "this" in method foo() --> most-recent a1
        // o --> most-recent a2
        mostRecent(o);
    }

    public Object foo() {
        mostRecent(this);
        return new Object(); // a2
    }
}
