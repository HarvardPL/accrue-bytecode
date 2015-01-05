package test.flowsenspointer;

public class Move extends TestBaseClass {

    Object f;

    public static void main(String[] args) {
        Move s = new Move(); // a1
        mostRecent(s);
        pointsToNull(s.f);
        pointsToSize(s, 1);
        pointsToSize(s.f, 1);
        Move s2 = s;
        // s2 --> most-recent a1
        mostRecent(s2);
        pointsToNull(s2.f);
        pointsToSize(s2, 1);
        pointsToSize(s2.f, 1);
    }
}