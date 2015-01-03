package test.flowsenspointer;

public class Store extends TestBaseClass {

    public Object f;

    public static void main(String[] args) {
        Store s = new Store(); // a1
        pointsToNull(s.f);
        s.f = new Object(); // a2
        // (most-recent a1).f --> most-recent a2;
        mostRecent(s);
        mostRecent(s.f);
    }

}
