package test.pointer;


public class CallOnStaticField {

    static Object f = new Object();
    public static void main(String[] args) {
        CallOnStaticField.f.hashCode();
    }
}
