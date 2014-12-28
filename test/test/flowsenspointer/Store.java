package test.flowsenspointer;

public class Store {

    public Object f;

    public static void main(String[] args) {
        Store s = new Store(); // a1
        s.f = new Object(); // a2
        // (most-recent a1).f --> most-recent a2;
    }

}
