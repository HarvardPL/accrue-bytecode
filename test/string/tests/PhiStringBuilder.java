package string.tests;

public class PhiStringBuilder {
    static int x = 0;

    public static void main(String[] args) {
        StringBuilder s1;
        if (x == 1) {
            s1 = new StringBuilder("foo1");
        }
        else {
            s1 = new StringBuilder("foo2");
        }
        bar(s1.toString());
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
