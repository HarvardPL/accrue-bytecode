package test.flowsenspointer;

public class Move {

    Object f;

    public static void main(String[] args) {
        Move s = new Move(); // a1
        @SuppressWarnings("unused")
        Object s2 = s;
        s2.hashCode();
        // s2 --> most-recent a1
    }
}
