package test.flowsenspointer;

public class Load2 {

    public static Object staticfield = null;

    // public Object f = new Object();

    public static void main(String[] args) {

        Load2 l = null;

        while (l == null) {
            // Load2 l2 = l;
            staticfield = l;
            l = new Load2();
            // l2 is a non-most recent object
            // l2.hashCode();
        }
    }
}
