package test.pdg;


public class StringBuilderTest {

    static String x = "PUBLIC";
    static String y = "SECRET";

    public static void main(String[] args) {
        y = y + "bar";
        bar(x + "foo");
    }

    private static void bar(String string) {

    }
}