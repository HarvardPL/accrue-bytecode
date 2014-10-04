package test.instruction;

public class GetField1 {

    int x;

    public static void main(String[] args) {
        GetField1 gf = new GetField1(42);
        int y = gf.x;

    }

    public GetField1(int i) {
        i = 0;
    }
}
