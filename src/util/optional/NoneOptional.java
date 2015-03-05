package util.optional;

public class NoneOptional<T> extends Optional<T> {

    @Override
    public boolean isNone() {
        return true;
    }

    @Override
    public T get() {
        throw new RuntimeException("No value in None case");
    }

}
