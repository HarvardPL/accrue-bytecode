package test.pointer;

public class LocalToField {

    Object f = new Object();
    
    public static void main(String[] args) {
        LocalToField s = new LocalToField();
        Object o = new Object();
        s.f = o;
    }
}
