package signatures.library.java.security;

import java.security.AccessControlContext;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

/**
 * Model that give the semantics without the security
 */
public class AccessController {

    public static <T> T doPrivileged(PrivilegedAction<T> action) {
        return action.run();
    }

    public static <T> T doPrivileged(PrivilegedAction<T> action,
                                    @SuppressWarnings("unused") AccessControlContext context) {
        return action.run();
    }

    public static <T> T doPrivileged(PrivilegedExceptionAction<T> action) throws PrivilegedActionException {
        try {
            return action.run();
        } catch (Exception e) {
            return null;
//            throw new PrivilegedActionException(e);
        }
    }

    public static <T> T doPrivileged(PrivilegedExceptionAction<T> action,
                                    @SuppressWarnings("unused") AccessControlContext context)
                                    throws PrivilegedActionException {
        try {
            return action.run();
        } catch (Exception e) {
            return null;
            // throw new PrivilegedActionException(e);
        }
    }
}
