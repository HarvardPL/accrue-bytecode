package test.flowsenspointer;

public class Load2 {

    public static Load2 staticfield = null;

    public Object f = new Object(); // a2

    public static void main(String[] args) {

        Load2 l = null;

        while (l == null) {
            staticfield = l;
            l = new Load2(); // a1
            // staticfield --> non-most-recent a1
        }

        Object o = staticfield.f;
        // o --> non-most-recent a2
        Object o2 = l.f;
        // o --> most-recent a2

    }
}
