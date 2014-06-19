package test;


public class InferenceImprecisionForInterfaces {
    static boolean b;
    static Foo y;

    public static void main(String[] args) {
        Foo x;
        if (b) {
            x = new C1();
        } else {
            x = new C2();
        }
        bar(x);
        y = x.foo();
    }

    private static void bar(@SuppressWarnings("unused") Foo x) {
        // TODO Auto-generated method stub

    }

    public static interface Foo {
        Foo foo();
    }

    public static class C1 implements Foo {
        @Override
        public Foo foo() {
            return this;
        }
    }

    public static class C2 implements Foo {
        @Override
        public Foo foo() {
            return this;
        }
    }
}