package signatures.library.java.security;

import java.security.AccessControlException;
import java.security.Permission;

public class AccessController {

    public static void checkPermission(Permission perm) throws AccessControlException {
        if (perm == null) {
            throw new AccessControlException("");
        }
    }
}
