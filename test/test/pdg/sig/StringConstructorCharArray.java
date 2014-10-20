package test.pdg.sig;

public class StringConstructorCharArray {

    static String foo;
    static char[] bar;

    public static void main(String[] args) {
        bar = new char[3];
        bar = new char[42];
        bar[0] = 'a';
        foo = new String(bar);
    }

}
