package test.pointer;

public class SuperCall {

    Object fieldScratch;
    
    public static void main(String[] args) {
        SuperCall ss = new SubScratch();
        Object o1 = new Object();
        @SuppressWarnings("unused")
        Object o2 = ss.foo(o1);
    }
    
    public Object foo(Object fooScratchArg) {

        fieldScratch = fooScratchArg;
        Object scratchLocal =  new Object();
        return scratchLocal;
    }
    
    static class SubScratch extends SuperCall {
        public Object foo(Object fooSubArg) {
            Object subLocal = super.foo(fooSubArg);
            return subLocal;
        }
    }
}
