package util.optional;

public class SomeOptional<T> extends Optional<T> {

    private final T t;

    SomeOptional(T t) {
        this.t = t;
    }

    @Override
    public boolean isNone() {
        return false;
    }

    @Override
    public T get() {
        return t;
    }

}
