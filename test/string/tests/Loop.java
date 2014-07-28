package string.tests;

public class Loop {

    private static int x;

    public static void main(String[] args) {
        String s1 = "foo";
        String s2 = "baz";
        while (x == 1) {
            s2 = s1;
            s1 = "bar";
        }
        bar(s2);
    }

    private static void bar(@SuppressWarnings("unused") String s) {
        // TODO Auto-generated method stub
    }

}
