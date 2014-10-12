package test.instruction;

public class InvokeSpecial {

    int x;

    public InvokeSpecial(int argX) {
        this.x = argX;
    }

    public static void main(String[] args) {

        InvokeSpecial s = new InvokeSpecial(42);
    }
}
