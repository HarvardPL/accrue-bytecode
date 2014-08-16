package string.tests;


public class StringBuilderAppend {
    private static int x = 0;

    public static void main(String[] args) {
        StringBuilder s1 = new StringBuilder("foo");
        if (x == 0) {
            s1.append("bar");
        }
        else {
            s1.append("baz");
        }
        bar(s1.toString());
    }

    private static void bar(String s2) {
        // TODO Auto-generated method stub

    }
}
