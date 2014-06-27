package test.duplicates;

public class Field {
    Field foo;

    public static void main(String[] args) {
        Field s0 = new Field();
        Field s1 = s0.foo;
        Field s2 = s0.foo;

        s1.baz();
        s2.baz();
    }

    public void baz() {
        // TODO Auto-generated method stub
    }
}