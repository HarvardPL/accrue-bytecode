package string.tests;

public class GetStaticStringBuilder {
    static StringBuilder s = new StringBuilder("foo");

    public static void main(String[] args) {
        bar(s.toString());
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
