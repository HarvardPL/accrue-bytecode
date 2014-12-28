package test.flowsenspointer;

public class VCall {
    public static void main(String[] args) {
        Object o = new Object(); // a1
        o.hashCode();
        // "this" in method hashCode() --> a1
    }
}
