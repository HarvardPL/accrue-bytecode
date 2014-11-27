package test.pdg;


public class StringBuilderTest2 {

    static String x = "PUBLIC";
    static String y = "SECRET";
    private static char w;

    public static void main(String[] args) {
        w = y.toCharArray()[5];
        char z = x.toCharArray()[5];
        bar(z);
    }

    private static void bar(char z) {

    }
}