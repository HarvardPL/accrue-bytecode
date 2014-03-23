package test.pointer;

public class StaticFieldSubClass {
    
    public static void main(String[] args) {
        @SuppressWarnings("unused")
        Object maino = D.o;
    }

    static class C {
        static Object o = new Object();
    }
    
    static class D extends C {
        
    }
}
