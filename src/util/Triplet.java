package util;

public class Triplet<A, B, C> {
    private final A a;
    private final B b;
    private final C c;

    public Triplet(A a, B b, C c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }

    public A getA() {
        return a;
    }

    public B getB() {
        return b;
    }

    public C getC() {
        return c;
    }

    @Override
    public int hashCode() {
        return this.a.hashCode() + this.b.hashCode() + this.c.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Triplet<?, ?, ?>
            && this.a.equals(((Triplet<?, ?, ?>) o).a)
            && this.b.equals(((Triplet<?, ?, ?>) o).b)
            && this.c.equals(((Triplet<?, ?, ?>) o).c);

    }
}
