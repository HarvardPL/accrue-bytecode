package test.duplicates;


public class StaticField {
    static StaticField foo;

    public static void main(String[] args) {
        StaticField s1 = StaticField.foo;
        StaticField s2 = StaticField.foo;

        s1.baz();
        s2.baz();
    }

    public void baz() {
        // TODO Auto-generated method stub
    }
}