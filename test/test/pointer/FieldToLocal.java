package test.pointer;

public class FieldToLocal {

    Object f = new Object();
    
    public static void main(String[] args) {
        FieldToLocal s = new FieldToLocal();
        @SuppressWarnings("unused")
        Object o1 = s.f;
    }
}
