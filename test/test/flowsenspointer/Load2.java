package test.flowsenspointer;

public class Load2 extends TestBaseClass {

    public static Load2 staticfield = null;

    public Object f = new Object(); // a2

    public static void main(String[] args) {

        Load2 l = null;

        int x = 5;
        while (x > 0) {
            // l --> null || most-recent a1
            // staticfield --> null || non-most-recent a1
            pointsToNull(l);
            mostRecent(l);
            pointsToNull(staticfield);
            nonMostRecent(staticfield);

            staticfield = l;

            // Strong update to staticfield
            // l --> null || most-recent a1
            // staticfield --> null || most-recent a1
            pointsToNull(l);
            mostRecent(l);
            pointsToNull(staticfield);
            mostRecent(staticfield);

            l = new Load2(); // a1

            // l --> null || most-recent a1
            // staticfield --> null || non-most-recent a1
            mostRecent(l);
            pointsToNull(staticfield);
            nonMostRecent(staticfield);

            x--;
        }

        Object o = staticfield.f;
        // o --> non-most-recent a2 || most-recent a2
        nonMostRecent(o);
        mostRecent(o);
        Object o2 = l.f;
        // o2 --> most-recent a2
        mostRecent(o2);

    }
}
