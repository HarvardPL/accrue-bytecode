package test.instruction;

public class InvokeInterface {

    static InvokeInterfaceInterface x;

    public static void main(String[] args) {
        x = new InvokeInterfaceSub();
        x.foo(5);
    }

    interface InvokeInterfaceInterface {
        void foo(int x);
    }

    static class InvokeInterfaceSub implements InvokeInterfaceInterface {

        static final int y = 90;

        @Override
        public void foo(int x) {
            x = y;

        }

    }
}
