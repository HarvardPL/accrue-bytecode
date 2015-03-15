package test.flowsenspointer;

public class StrongUpdate extends TestBaseClass {

    public static Object os;
    public Object o;

    public static void main(String[] args) {
        StrongUpdate s = new StrongUpdate(); // a0
        mostRecent(s);
        pointsToSize(s, 1);
        pointsToNull(s.o);
        pointsToSize(s.o, 1);
        pointsToNull(StrongUpdate.os);
        pointsToSize(StrongUpdate.os, 1);

        StrongUpdate.os = new Object(); // a1
        // os --> most-recent a1
        mostRecent(StrongUpdate.os);
        pointsToSize(StrongUpdate.os, 1);

        StrongUpdate.os = new Object(); // a2
        // os --> most-recent a2
        mostRecent(StrongUpdate.os);
        pointsToSize(StrongUpdate.os, 1);

        s.o = new Object(); // a3
        // (most-recent a0).o --> most-recent a3
        mostRecent(s);
        pointsToSize(s, 1);
        mostRecent(s.o);
        pointsToSize(s.o, 1);
        s.o = new Object(); // a4
        // (most-recent a0).o --> most-recent a4 (and not most-recent a3)
        mostRecent(s);
        pointsToSize(s, 1);
        mostRecent(s.o);
        pointsToSize(s.o, 1);
    }

}