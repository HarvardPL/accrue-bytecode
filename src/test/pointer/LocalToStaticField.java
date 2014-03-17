package test.pointer;

public class LocalToStaticField {

    static Object f = new Object();
    
    public static void main(String[] args) {
        Object o1 = new Object();
        f = o1;
        Object o2 = new Object();
        f = o2;
    }
}
