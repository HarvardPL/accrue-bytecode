package test.flowsenspointer;

public class Move extends TestBaseClass {

    Object f;

    public static void main(String[] args) {
        Move s = new Move(); // a1
        mostRecent(s);
        mostRecent(s.f);
        Move s2 = s;
        // s2 --> most-recent a1
        mostRecent(s2);
        mostRecent(s2.f);
    }
}
