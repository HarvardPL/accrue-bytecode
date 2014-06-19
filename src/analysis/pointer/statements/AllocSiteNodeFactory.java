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
        AllocSiteNode n = new AllocSiteNode(name, allocatedClass, allocatingClass, pc);
        assert nodeMap.put(result, n) == null;
        return n;
    }

    /**
     * Create a new analysis-generated allocation, (i.e. a generated exception, a string literal, a native method
     * signature). This should only be called once for each allocation
     * 
     * @param debugString
     *            String for printing and debugging
     * @param allocatedClass
     *            class being allocated
     * @param allocatingClass
     *            class where allocation occurs
     * @return unique allocation node
     */
    protected static AllocSiteNode createGenerated(String debugString, IClass allocatedClass, IClass allocatingClass,
                                    ReferenceVariable result) {
        @SuppressWarnings("synthetic-access")
        AllocSiteNode n = new AllocSiteNode(debugString, allocatedClass, allocatingClass);
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
         * Represents the allocation of an object by something other than a "new" instruction.
         * <p>
         * e.g. a string literal, generated exception, signature for a native method
         * 
         * @param debugString
         *            String for printing and debugging
         * @param allocatedClass
         *            class being allocated
         * @param allocatingClass
         *            class where allocation occurs
         */
        private AllocSiteNode(String debugString, IClass allocatedClass, IClass allocatingClass) {
            this(debugString, allocatedClass, allocatingClass, -1);
        }

        /**
         * Represents the allocation of a new object
         * 
         * @param debugString
         *            String for printing and debugging
         * @param allocatedClass
         *            class being allocated
         * @param allocatingClass
         *            class where allocation occurs
         * @param programCounter
         *            program counter at the allocation site (-1 for generated allocations e.g. generated exceptions)
         */
        private AllocSiteNode(String debugString, IClass allocatedClass, IClass allocatingClass, int programCounter) {
            assert debugString != null;
            assert allocatingClass != null;
            assert allocatedClass != null;
            this.debugString = debugString;
            this.allocatingClass = allocatingClass;
            this.allocatedClass = allocatedClass;
            this.programCounter = programCounter;
        }

        @Override
        public String toString() {
            if (programCounter >= 0) {
                return debugString + "@" + programCounter;
            }
            return debugString;
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
