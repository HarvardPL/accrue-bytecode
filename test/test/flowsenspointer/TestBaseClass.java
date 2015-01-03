package test.flowsenspointer;


public abstract class TestBaseClass {
    @SuppressWarnings("unused")
    static void nonMostRecent(Object arg1) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void mostRecent(Object arg2) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void pointsToNull(Object arg3) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void pointsTo(Object arg4) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void pointsToSize(Object arg5, int size) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void notNonMostRecent(Object arg6) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void notMostRecent(Object arg7) {
        // intentional
    }

    @SuppressWarnings("unused")
    static void notNull(Object arg8) {
        // intentional
    }
}
