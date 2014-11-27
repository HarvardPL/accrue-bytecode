package test.pdg.sig;

public class CaseInsenstiveComparator {

    static int compare;

    public static void main(String[] args) {
        String foo = new String("foo");
        String bar = new String();
        compare = String.CASE_INSENSITIVE_ORDER.compare(foo, bar);
    }

}
