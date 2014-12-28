package test.flowsenspointer;

public class VCall2 {

    public static void main(String[] args) {
        VCall2 v = new VCall2(); // a1
        Object o = v.foo();
        // "this" in method foo() --> a1
        // o --> most-recent a2
    }

    public Object foo() {
        this.hashCode();
        return new Object(); // a2
    }
}
