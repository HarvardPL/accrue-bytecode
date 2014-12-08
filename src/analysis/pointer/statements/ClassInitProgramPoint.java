package analysis.pointer.statements;

import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.IMethod;

/**
 * Program point for a static class initializer
 */
public class ClassInitProgramPoint extends ProgramPoint {
    /**
     * Class initialization method
     */
    private final IMethod init;

    /**
     * Program point for a static class initializer
     *
     * @param init Class initialization method
     * @param caller of the class initializer
     */
    public ClassInitProgramPoint(IMethod init, IMethod caller) {
        super(caller, PrettyPrinter.methodString(init));
        this.init = init;
    }

    /**
     *
     * @return Class initialization method
     */
    public IMethod getInitializer() {
        return this.init;
    }
}
