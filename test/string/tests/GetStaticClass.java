package string.tests;

public class GetStaticClass {
    static Class s = String.class;

    public static void main(String[] args) {
        bar(s.getName());
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
