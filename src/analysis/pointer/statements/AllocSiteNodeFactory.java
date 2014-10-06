package analysis.pointer.statements;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;

/**
 * Factory for creating representations of memory allocations
 */
public class AllocSiteNodeFactory {

    /**
     * Only one allocation should be created for a given Reference variable. If assertions are on this map will be used
     * to guarantee that
     */
    private static final Map<ReferenceVariable, AllocSiteNode> nodeMap = new HashMap<>();

    /**
     * Methods should be accessed statically
     */
    private AllocSiteNodeFactory() {
        // Methods should be accessed statically
    }

    /**
     * Create an allocation node for a normal allocation (i.e. from a "new" instruction o = new Object). This should
     * only be called once for each allocation.
     *
     * @param allocatedClass class being allocated
     * @param allocatingClass class where allocation occurs
     * @param result variable the results will be assigned into
     * @param pc program counter where the allocation occurs
     * @param lineNumber line number from source code if one can be found, -1 otherwise
     * @return unique allocation node
     */
    protected static AllocSiteNode createNormal(IClass allocatedClass, IClass allocatingClass,
                                                ReferenceVariable result, int pc, int lineNumber) {
        String name = PrettyPrinter.typeString(allocatedClass);
        @SuppressWarnings("synthetic-access")
        AllocSiteNode n = new AllocSiteNode(name, allocatedClass, allocatingClass, pc, false, lineNumber);
        assert nodeMap.put(result, n) == null;
        return n;
    }

    /**
     * Create a new analysis-generated allocation, (i.e. a generated exception, a string literal, a native method
     * signature). This should only be called once for each allocation
     *
     * @param debugString String for printing and debugging
     * @param allocatedClass class being allocated
     * @param allocatingClass class where allocation occurs
     * @param isStringLiteral true if this allocation is for a string literal, if this is true then debugString should
     *            be the literal string being allocated
     *
     * @return unique allocation node
     */
    public static AllocSiteNode createGenerated(String debugString, IClass allocatedClass, IClass allocatingClass,
                                                   ReferenceVariable result, boolean isStringLiteral) {
        @SuppressWarnings("synthetic-access")
        AllocSiteNode n = new AllocSiteNode(debugString, allocatedClass, allocatingClass, isStringLiteral);
        assert result == null || nodeMap.put(result, n) == null;
        return n;
    }

    /**
     * Represents an allocation site in the code
     */
    public static final class AllocSiteNode {

        /**
         * Class allocation occurs in
         */
        private final IClass allocatingClass;
        /**
         * Allocated class
         */
        private final IClass allocatedClass;
        /**
         * String used for printing and debugging
         */
        private final String debugString;
        /**
         * program counter at the allocation site (-1 for generated allocations e.g. generated exceptions)
         */
        private final int programCounter;

        /**
         * Is this allocation a string literal then this is the literal value?
         */
        private final boolean isStringLiteral;
        /**
         * line number from source code if one can be found, -1 otherwise
         */
        private final int lineNumber;

        /**
         * Represents the allocation of an object by something other than a "new" instruction.
         * <p>
         * e.g. a string literal, generated exception, signature for a native method
         *
         * @param debugString String for printing and debugging
         * @param allocatedClass class being allocated
         * @param allocatingClass class where allocation occurs
         * @param isStringLiteral true if this allocation is for a string literal
         */
        private AllocSiteNode(String debugString, IClass allocatedClass, IClass allocatingClass, boolean isStringLiteral) {
            this(debugString, allocatedClass, allocatingClass, -1, isStringLiteral, -1);
        }

        /**
         * Represents the allocation of a new object
         *
         * @param debugString String for printing and debugging
         * @param allocatedClass class being allocated
         * @param allocatingClass class where allocation occurs
         * @param programCounter program counter at the allocation site (-1 for generated allocations e.g. generated
         *            exceptions)
         * @param isStringLiteral true if this allocation is for a string literal
         * @param lineNumber line number from source code if one can be found, -1 otherwise
         */
        private AllocSiteNode(String debugString, IClass allocatedClass, IClass allocatingClass, int programCounter,
                              boolean isStringLiteral, int lineNumber) {
            assert debugString != null;
            assert allocatingClass != null;
            assert allocatedClass != null;
            this.debugString = debugString;
            this.allocatingClass = allocatingClass;
            this.allocatedClass = allocatedClass;
            this.programCounter = programCounter;
            this.isStringLiteral = isStringLiteral;
            this.lineNumber = lineNumber;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(debugString.replace("\n", "(newline)"));
            sb.append(" allocated in ");
            sb.append(PrettyPrinter.typeString(allocatingClass));
            if (lineNumber >= 0) {
                sb.append("(line:" + lineNumber + ")");
            }
            if (programCounter >= 0) {
                sb.append("@" + programCounter);
            }
            return (isStringLiteral ? "LITERAL " : "") + sb.toString();
        }

        public boolean isStringLiteral() {
            return isStringLiteral;
        }

        public IClass getAllocatingClass() {
            return allocatingClass;
        }

        public IClass getAllocatedClass() {
            return allocatedClass;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }
}
