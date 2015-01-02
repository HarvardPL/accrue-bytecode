package analysis.pointer.statements;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;

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
     * @param allocatingMethod method where allocation occurs
     * @param result variable the results will be assigned into
     * @param pc program counter where the allocation occurs
     * @param lineNumber line number from source code if one can be found, -1 otherwise
     * @return unique allocation node
     */
    protected static AllocSiteNode createNormal(IClass allocatedClass, IMethod allocatingMethod,
                                                ReferenceVariable result, int pc, int lineNumber) {
        String name = PrettyPrinter.typeString(allocatedClass);
        AllocSiteNode n = new AllocSiteNode(name, allocatedClass, allocatingMethod, pc, false, lineNumber);
        assert nodeMap.put(result, n) == null;
        return n;
    }

    /**
     * Create a new analysis-generated allocation, (i.e. a generated exception, a string literal, a native method
     * signature). This should only be called once for each allocation
     *
     * @param debugString String for printing and debugging
     * @param allocatedClass class being allocated
     * @param allocatingMethod method where allocation occurs
     * @param isStringLiteral true if this allocation is for a string literal, if this is true then debugString should
     *            be the literal string being allocated
     *
     * @return unique allocation node
     */
    public static AllocSiteNode createGenerated(String debugString, IClass allocatedClass, IMethod allocatingMethod,
                                                ReferenceVariable result, boolean isStringLiteral) {
        @SuppressWarnings("synthetic-access")
        AllocSiteNode n = new AllocSiteNode(debugString, allocatedClass, allocatingMethod, isStringLiteral);
        assert result == null || nodeMap.put(result, n) == null;
        return n;
    }

    /**
     * Represents an allocation site in the code
     */
    public static class AllocSiteNode {

        /**
         * Method where allocation occurs in
         */
        protected final IMethod allocatingMethod;
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
        protected final int programCounter;

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
         * @param allocatingMethod method where allocation occurs
         * @param isStringLiteral true if this allocation is for a string literal
         */
        private AllocSiteNode(String debugString, IClass allocatedClass, IMethod allocatingMethod,
                              boolean isStringLiteral) {
            this(debugString, allocatedClass, allocatingMethod, -1, isStringLiteral, -1);
        }

        /**
         * Represents the allocation of a new object
         *
         * @param debugString String for printing and debugging
         * @param allocatedClass class being allocated
         * @param allocatingMethod class where allocation occurs
         * @param programCounter program counter at the allocation site (-1 for generated allocations e.g. generated
         *            exceptions)
         * @param isStringLiteral true if this allocation is for a string literal
         * @param lineNumber line number from source code if one can be found, -1 otherwise
         */
        public AllocSiteNode(String debugString, IClass allocatedClass, IMethod allocatingMethod, int programCounter,
                             boolean isStringLiteral, int lineNumber) {
            assert debugString != null;
            assert allocatingMethod != null;
            assert allocatedClass != null;
            this.debugString = debugString;
            this.allocatingMethod = allocatingMethod;
            this.allocatedClass = allocatedClass;
            this.programCounter = programCounter;
            this.isStringLiteral = isStringLiteral;
            this.lineNumber = lineNumber;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(debugString.replace("\n", "(newline)"));
            sb.append(" allocated at ");
            sb.append(PrettyPrinter.methodString(allocatingMethod));
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
            return allocatingMethod.getDeclaringClass();
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

        public IMethod getAllocatingMethod() {
            return allocatingMethod;
        }

        public int getProgramCounter() {
            return programCounter;
        }
    }
}
