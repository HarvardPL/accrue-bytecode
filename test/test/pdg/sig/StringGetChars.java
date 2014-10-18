package test.pdg.sig;

public class StringGetChars {

    static String foo = "foo";
    static char[] bar;

    public static void main(String[] args) {
        bar = new char[3];
        bar[0] = 'a';
        foo.getChars(0, 2, bar, 1);
    }

}
