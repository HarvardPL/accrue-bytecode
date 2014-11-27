package test.pdg.sig;

public class CaseInsenstiveComparator2 {

    static String foo = "foo";
    static String bar = "bar";
    static int compare;

    public static void main(String[] args) {
        compare = String.CASE_INSENSITIVE_ORDER.compare(foo, bar);
    }

}
