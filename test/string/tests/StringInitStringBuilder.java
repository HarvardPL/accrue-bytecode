package string.tests;

public class StringInitStringBuilder {
    public static void main(String[] args) {
        StringBuilder s1 = new StringBuilder("foo");
        String s2 = new String(s1);
        bar(s2);
    }

    private static void bar(String s2) {
        // TODO Auto-generated method stub

    }
}
