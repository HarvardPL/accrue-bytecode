package test.flowsenspointer;

public class Load2 {

    public static Load2 staticfield = null;

    public Object f = new Object(); // a2

    public static void main(String[] args) {

        Load2 l = null;

        while (l == null) {
            // l --> null || most-recent a1
            // staticfield --> non-most-recent a1
            //            pointsToNull(l);
            //            mostRecent(l);
            //            pointsToNull(staticfield);
            pointsTo(staticfield);
            pointsTo(l);
            staticfield = l;
            pointsTo(l);
            pointsTo(staticfield);
            l = new Load2(); // a1
            pointsTo(staticfield);
            pointsTo(l);
            // staticfield --> non-most-recent a1
            nonMostRecent(staticfield);
        }
        pointsTo(staticfield);

        //        mostRecent(l);
        Object o = staticfield.f;
        // o --> non-most-recent a2
        pointsTo(staticfield);
        pointsTo(o);
        nonMostRecent(o);
        Object o2 = l.f;
        // o2 --> most-recent a2
        mostRecent(o2);

    }

    private static void pointsTo(Object arg4) {
        // TODO Auto-generated method stub

    }

    private static void nonMostRecent(Object arg1) {
        // TODO Auto-generated method stub
    }

    private static void mostRecent(Object arg2) {
        // TODO Auto-generated method stub

    }

    private static void pointsToNull(Object arg3) {
        // TODO Auto-generated method stub

    }
}
