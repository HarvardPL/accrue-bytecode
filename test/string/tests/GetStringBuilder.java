package string.tests;

public class GetStringBuilder {
    StringBuilder s = new StringBuilder("foo");

    public static void main(String[] args) {
        GetStaticStringBuilder ssss = new GetStaticStringBuilder();
        StringBuilder s1 = ssss.s;
        bar(s1.toString());
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
