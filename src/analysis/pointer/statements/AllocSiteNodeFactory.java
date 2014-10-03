package analysis.pointer.statements;

import java.util.HashMap;
import java.util.Map;

import util.print.PrettyPrinter;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.types.TypeReference;

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
     * @param allocatedClass
     *            class being allocated
     * @param allocatingClass
     *            class where allocation occurs
     * @param result
     *            variable the results will be assigned into
     * @param pc
     *            program counter where the allocation occurs
     * @return unique allocation node
     */
    protected static AllocSiteNode createNormal(IClass allocatedClass, IClass allocatingClass,
                                    ReferenceVariable result, int pc) {
        String name = PrettyPrinter.typeString(allocatedClass);
        @SuppressWarnings("synthetic-access")
        AllocSiteNode n = new AllocSiteNode(name, allocatedClass, allocatingClass, pc, false);
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
    protected static AllocSiteNode createGenerated(String debugString, IClass allocatedClass, IClass allocatingClass,
                                                   ReferenceVariable result, boolean isStringLiteral) {
        @SuppressWarnings("synthetic-access")
        AllocSiteNode n = new AllocSiteNode(debugString, allocatedClass, allocatingClass, isStringLiteral);
        assert nodeMap.put(result, n) == null;
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
            this(debugString, allocatedClass, allocatingClass, -1, isStringLiteral);
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
         */
        private AllocSiteNode(String debugString, IClass allocatedClass, IClass allocatingClass, int programCounter,
                              boolean isStringLiteral) {
            assert debugString != null;
            assert allocatingClass != null;
            assert allocatedClass != null;
            this.debugString = debugString;
            this.allocatingClass = allocatingClass;
            this.allocatedClass = allocatedClass;
            this.programCounter = programCounter;
            this.isStringLiteral = isStringLiteral;
        }

        @Override
        public String toString() {
            if (programCounter >= 0) {
                return debugString + "@" + programCounter;
            }
            return (isStringLiteral ? "LITERAL " : "") + debugString.replace("\n", "(newline)");
        }

        public String getStringLiteralValue() {
            if (isStringLiteral) {
                return debugString;
            }
            throw new RuntimeException("Trying to get String literal value for an allocation that is not a String literal. Call isStringLiteral() first.");
        }

        public boolean isStringLiteral() {
            return isStringLiteral;
        }

        public TypeReference getExpectedType() {
            return allocatedClass.getReference();
        }

        public IClass getAllocatingClass() {
            return allocatingClass;
        }

        public IClass getAllocatedClass() {
            return allocatedClass;
        }

        public int getProgramCounter() {
            return programCounter;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
