package test.flowsenspointer;

public class Preserve {

    public static Object staticfield = null;

    public static void main(String[] args) {

        Preserve l = null;

        while (l == null) {
            staticfield = l;
            // static field --> most-recent a1
            l = new Preserve(); // a1
            // static field --> non-most-recent a1
        }
    }
}
