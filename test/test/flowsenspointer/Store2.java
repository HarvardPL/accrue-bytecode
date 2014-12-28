package test.flowsenspointer;

public class Store2 {

    public Object f;

    public static void main(String[] args) {
        Store2 s = new Store2(); // a1

        Object o = null;
        while (o == null) {
            o = new Object(); // a2
        }
        // o --> most-recent a2 & non-most-recent a2

        s.f = o;
        // (most-recent a1).f --> most-recent a2 & non-most-recent a2
    }

}
