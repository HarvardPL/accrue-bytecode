package signatures.library.java.io;


public class File {

    static private FileSystem fs = new UnixFileSystem();
    public static final char separatorChar = fs.getSeparator();
    public static final String separator = "" + separatorChar;
    public static final char pathSeparatorChar = fs.getPathSeparator();
    public static final String pathSeparator = "" + pathSeparatorChar;
    @SuppressWarnings("unused")
    private static final long serialVersionUID = 301077366599181567L;
}
