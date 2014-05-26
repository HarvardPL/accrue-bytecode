package signatures.library.java.io;

import java.io.File;
import java.io.IOException;


public class UnixFileSystem extends FileSystem {
    private final char slash;
    private final char colon;
    @SuppressWarnings("unused")
    private final String javaHome;

    public UnixFileSystem() {
        slash = '/';
        colon = ':';
        javaHome = "JAVA_HOME";
    }

    @SuppressWarnings({ "static-method", "unused" })
    private String canonicalize0(String path) throws IOException {
        if (path == null) {
            throw new IOException();
        }
        return path;
    }

    @SuppressWarnings("static-method")
    public boolean checkAccess(File f, int access) {
        if (f == null || access == 42) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("static-method")
    public boolean createFileExclusively(String path) {
        if (path == null) {
            return false;
        }
        return true;
    }

    @SuppressWarnings({ "static-method", "unused" })
    private boolean delete0(File f) {
        if (f == null) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("static-method")
    public String[] list(File f) {
        if (f != null) {
            return new String[] { "foo", "bar", "baz" };
        }
        return new String[] {};
    }

    @SuppressWarnings("static-method")
    public boolean createDirectory(File f) {
        if (f == null) {
            return false;
        }
        return true;
    }

    @SuppressWarnings({ "static-method", "unused" })
    private boolean rename0(File f1, File f2) {
        if (f1 == null || f2 == null) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("static-method")
    public boolean setLastModifiedTime(File f, long time) {
        if (f == null || time == 0) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("static-method")
    public boolean setReadOnly(File f) {
        if (f == null) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("static-method")
    public long getSpace(File f, int t) {
        if (f != null && t != 42) {
            return 0L;
        }
        return 42L;
    }

    @SuppressWarnings("unused")
    private static void initIDs() {
        // intentional
    }

    @Override
    public char getSeparator() {
        return slash;
    }

    @Override
    public char getPathSeparator() {
        return colon;
    }
}
