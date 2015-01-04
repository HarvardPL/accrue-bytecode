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
            pointsToSize(l, 2);
            pointsToNull(staticfield);
            nonMostRecent(staticfield);
            pointsToSize(staticfield, 2);

            staticfield = l;

            // Strong update to staticfield
            // l --> null || most-recent a1
            // staticfield --> null || most-recent a1
            pointsToNull(l);
            mostRecent(l);
            pointsToSize(l, 2);
            pointsToNull(staticfield);
            mostRecent(staticfield);
            pointsToSize(staticfield, 2);

            l = new Load2(); // a1

            // l -->  most-recent a1
            // staticfield --> null || non-most-recent a1
            mostRecent(l);
            pointsToSize(l, 1);
            pointsToNull(staticfield);
            nonMostRecent(staticfield);
            pointsToSize(staticfield, 2);

            x--;
        }

        Object o = staticfield.f;
        // o --> non-most-recent a2 || most-recent a2
        nonMostRecent(o);
        mostRecent(o);
        pointsToSize(o, 2);
        Object o2 = l.f;
        // o2 --> most-recent a2
        mostRecent(o2);
        pointsToSize(o2, 1);
    }
}
