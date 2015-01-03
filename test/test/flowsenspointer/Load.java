package test.flowsenspointer;

public class Load extends TestBaseClass {

    public Object f = new Object(); // a1

    public static void main(String[] args) {

        Load s = new Load(); // a2
        // (most-recent a2).f --> most-recent a1
        mostRecent(s);
        mostRecent(s.f);

        Object o = s.f;
        // o --> most-recent a1
        // (most-recent a2).f --> most-recent a1
        mostRecent(o);
        mostRecent(s);
        mostRecent(s.f);

        Load s2 = new Load(); // a3
        // o still points to the most-recent since we are able to distinguish the two calls to Load.<init>
        // o --> most-recent a1
        // (most-recent a2).f --> most-recent a1
        mostRecent(o);
        mostRecent(s);
        mostRecent(s.f);

        Object o2 = s2.f;
        // o2 --> most-recent a1
        // (most-recent a3).f --> most-recent a1
        mostRecent(o2);
        mostRecent(s2);
        mostRecent(s2.f);

    }
}
