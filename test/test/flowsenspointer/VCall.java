package test.flowsenspointer;

public class VCall extends TestBaseClass {
    public static void main(String[] args) {
        VCall o = new VCall(); // a1
        o.foo();
        // "this" in method hashCode() --> a1
        mostRecent(o);
    }

    private void foo() {
        mostRecent(this);
    }
}
