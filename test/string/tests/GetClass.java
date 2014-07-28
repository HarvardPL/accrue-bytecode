package string.tests;

public class GetClass {
    Class s = String.class;

    public static void main(String[] args) {
        GetClass s1 = new GetClass();
        bar(s1.s.getName());
    }

    private static void bar(@SuppressWarnings("unused")
    String s) {
        // TODO Auto-generated method stub
    }
}
