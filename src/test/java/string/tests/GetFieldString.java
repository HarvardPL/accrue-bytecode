package string.tests;

public class GetFieldString {

    String s = "foo";

    public static void main(String[] args) {
        GetFieldString c = new GetFieldString();

        String s1 = c.s;
        bar(s1);
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
