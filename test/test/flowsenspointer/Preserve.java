package test.flowsenspointer;

public class Preserve extends TestBaseClass {

    public static Object staticfield = null;

    public static void main(String[] args) {

        Preserve l = null;

        int x = 0;
        while (x < 5) {
            staticfield = l;
            // static field --> most-recent a1
            mostRecent(staticfield);
            pointsToNull(staticfield);
            pointsToSize(staticfield, 2);
            mostRecent(l);
            pointsToNull(l);
            pointsToSize(l, 2);

            l = new Preserve(); // a1
            // staticfield --> non-most-recent a1
            pointsToNull(staticfield);
            nonMostRecent(staticfield);
            pointsToSize(staticfield, 2);
            mostRecent(l);
            pointsToSize(l, 1);

            x++;
        }

        nonMostRecent(staticfield);
        pointsToNull(staticfield);
        pointsToSize(staticfield, 2);

        mostRecent(l);
        pointsToNull(l);
        pointsToSize(l, 2);
    }
}
