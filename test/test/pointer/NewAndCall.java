package test.pointer;

public class NewAndCall {

    public static void main(String[] args) {
        NewAndCall s = new NewAndCall();
        s.foo();
    }

    @SuppressWarnings("static-method")
    private void foo() {
        @SuppressWarnings("unused")
        NewAndCall s = new NewAndCall();
    }

}
