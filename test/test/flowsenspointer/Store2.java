package test.flowsenspointer;

public class Store2 extends TestBaseClass {

    public Object f;

    public static void main(String[] args) {
        Store2 s = new Store2(); // a1
        pointsToNull(s.f);
        pointsToSize(s.f, 1);
        mostRecent(s);
        pointsToSize(s, 1);
        Object o = null;
        while (o == null) {
            o = new Object(); // a2
        }
        // o --> most-recent a2 & non-most-recent a2
        mostRecent(o);
        nonMostRecent(o);
        pointsToSize(o, 2);

        s.f = o;
        // (most-recent a1).f --> most-recent a2 & non-most-recent a2
        mostRecent(s);
        pointsToSize(s, 1);
        mostRecent(s.f);
        nonMostRecent(s.f);
        pointsToSize(s.f, 2);
    }

}
