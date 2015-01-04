package test.flowsenspointer;

public class Store extends TestBaseClass {

    public Object f;

    public static void main(String[] args) {
        Store s = new Store(); // a1
        pointsToNull(s.f);
        pointsToSize(s.f, 1);
        s.f = new Object(); // a2
        // (most-recent a1).f --> most-recent a2;
        mostRecent(s);
        pointsToSize(s, 1);
        mostRecent(s.f);
        pointsToSize(s.f, 1);
    }

}
