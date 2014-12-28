package test.flowsenspointer;

public class Load {

    public Object f = new Object(); // a1

    public static void main(String[] args) {

        Load s = new Load(); // a2
        @SuppressWarnings("unused")
        Object o = s.f;
        // o --> most-recent a1
        // (most-recent a2).f --> most-recent a1

        Load s2 = new Load(); // a3
        // o --> non-most-recent a1
        // (most-recent a2).f --> non-most-recent a1
        Object o2 = s2.f;
        // o2 --> most-recent a1

    }
}
