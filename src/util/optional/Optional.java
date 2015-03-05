package util.optional;

public abstract class Optional<T> {

    public static <T> Optional<T> none() {
        return new NoneOptional<>();
    }

    public static <T> Optional<T> some(T t) {
        return new SomeOptional<>(t);
    }

    public abstract boolean isNone();

    public boolean isSome() {
        return !isNone();
    }

    public abstract T get();

}
