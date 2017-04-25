package string.tests;

import javax.swing.text.Segment;

public class StringBuilderInitCharSequence {
    public static void main(String[] args) {
        StringBuilder s1 = new StringBuilder(new Segment());
        bar(s1.toString());
    }

    private static void bar(String s2) {
        // TODO Auto-generated method stub

    }
}
