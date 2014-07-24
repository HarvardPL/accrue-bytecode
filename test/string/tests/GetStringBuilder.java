package string.tests;

public class GetStringBuilder {
    static StringBuilder s = new StringBuilder("foo");

    public static void main(String[] args) {
        StringBuilder s1 = GetStringBuilder.s;
        bar(s1.toString());
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
