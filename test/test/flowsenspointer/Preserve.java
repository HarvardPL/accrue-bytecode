package test.flowsenspointer;

public class Preserve extends TestBaseClass {

    public static Object staticfield = null;

    public static void main(String[] args) {

        Preserve l = null;

        while (l == null) {
            staticfield = l;
            // static field --> most-recent a1
            mostRecent(staticfield);
            l = new Preserve(); // a1
            // static field --> non-most-recent a1
            nonMostRecent(staticfield);
        }
    }
}
