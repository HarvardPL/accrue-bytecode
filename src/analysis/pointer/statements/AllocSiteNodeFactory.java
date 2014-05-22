package analysis.pointer.statements;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import util.ImplicitEx;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;

/**
 * Factory for creating representations of memory allocations
 */
public class AllocSiteNodeFactory {

    private static final boolean CHECK_FOR_DUPLICATES = false;
    private static final Map<AllocSiteKey, AllocSiteNode> paranoidMap;
    static {
        if (CHECK_FOR_DUPLICATES) {
            paranoidMap = new HashMap<>();
        } else {
            paranoidMap = Collections.emptyMap();
        }
    }

    /**
     * Methods should be accessed statically
     */
    private AllocSiteNodeFactory() {
        // Methods should be accessed statically
    }

    protected static AllocSiteNode getAllocationNode(IClass allocatedClass, IClass allocatingClass, SSAInstruction i,
                                    Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(allocatedClass, allocatingClass, i, disambiguationKey);
        AllocSiteNode n = new AllocSiteNode(PrettyPrinter.typeString(allocatedClass.getReference()), allocatedClass,
                                        allocatingClass);
        checkForDuplicates(key, n);
        return n;
    }

    protected static AllocSiteNode getGeneratedAllocationNode(String name, IClass allocatedClass,
                                    IClass allocatingClass, SSAInstruction i, Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(allocatedClass, allocatingClass, i, disambiguationKey);
        AllocSiteNode n = new AllocSiteNode(name, allocatedClass, allocatingClass);
        checkForDuplicates(key, n);
        return n;
    }

    protected static AllocSiteNode getGeneratedExceptionNode(IClass allocatedClass, IClass allocatingClass,
                                    SSAInstruction i, Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(allocatedClass, allocatingClass, i, disambiguationKey);
        AllocSiteNode n = new AllocSiteNode(ImplicitEx.fromType(allocatedClass.getReference()).toString(),
                                        allocatedClass, allocatingClass);
        checkForDuplicates(key, n);
        return n;
    }

    protected static AllocSiteNode getAllocationNodeForNative(IClass allocatedClass, IClass allocatingClass,
                                    SSAInvokeInstruction nativeInvoke, ExitType type, Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(allocatedClass, allocatingClass, nativeInvoke, type, disambiguationKey);
        assert !paranoidMap.containsKey(key) : "Duplicate native allocation node: " + allocatedClass + " from "
                                        + allocatingClass + " for " + type;
        AllocSiteNode n = new AllocSiteNode(PrettyPrinter.typeString(allocatedClass.getReference()), allocatedClass,
                                        allocatingClass);
        paranoidMap.put(key, n);
        return n;
    }

    /**
     * If in paranoid mode then make sure the same parameters are never used to create two differet allocation nodes
     * 
     * @param key
     *            parameters for the allocation
     * @param node
     *            node being allocated
     */
    private static void checkForDuplicates(AllocSiteKey key, AllocSiteNode node) {
        if (CHECK_FOR_DUPLICATES) {
            if (paranoidMap.containsKey(key)) {
                throw new RuntimeException("Duplicate allocation node: " + node + "\n\texisting was: "
                                                + paranoidMap.get(key) + "\n\tkey: " + key);
            }
            paranoidMap.put(key, node);
        }
    }

    private static class AllocSiteKey {
        private final IClass allocatedClass;
        private final IClass allocatingClass;
        private final SSAInstruction i;
        private final ExitType exitType;
        private final Object disambiguationKey;

        public AllocSiteKey(IClass allocatedClass, IClass allocatingClass, SSAInstruction i, Object disambiguationKey) {
            assert !(disambiguationKey instanceof ExitType) : "Missing argument for disambiguation key";
            this.allocatedClass = allocatedClass;
            this.allocatingClass = allocatingClass;
            this.i = i;
            this.exitType = null;
            this.disambiguationKey = disambiguationKey;
        }

        public AllocSiteKey(IClass allocatedClass, IClass containingClass, SSAInvokeInstruction nativeCall,
                                        ExitType exitType, Object disambiguationKey) {
            this.allocatedClass = allocatedClass;
            this.allocatingClass = containingClass;
            this.i = nativeCall;
            this.exitType = exitType;
            this.disambiguationKey = disambiguationKey;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Alloc: ");
            sb.append(PrettyPrinter.typeString(allocatedClass.getReference()));
            sb.append(" from ");
            sb.append(PrettyPrinter.typeString(allocatingClass.getReference()));
            sb.append(" for ");
            sb.append(i);
            sb.append(exitType != null ? " native " + exitType : "");
            sb.append("\n\tdisambiguation: ");
            sb.append(disambiguationKey);
            return sb.toString();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((allocatingClass == null) ? 0 : allocatingClass.hashCode());
            result = prime * result + ((disambiguationKey == null) ? 0 : disambiguationKey.hashCode());
            result = prime * result + ((exitType == null) ? 0 : exitType.hashCode());
            result = prime * result + ((i == null) ? 0 : i.hashCode());
            result = prime * result + ((allocatedClass == null) ? 0 : allocatedClass.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            AllocSiteKey other = (AllocSiteKey) obj;
            if (allocatingClass == null) {
                if (other.allocatingClass != null)
                    return false;
            } else if (!allocatingClass.equals(other.allocatingClass))
                return false;
            if (disambiguationKey == null) {
                if (other.disambiguationKey != null)
                    return false;
            } else if (!disambiguationKey.equals(other.disambiguationKey))
                return false;
            if (exitType == null) {
                if (other.exitType != null)
                    return false;
            } else if (!exitType.equals(other.exitType))
                return false;
            if (i == null) {
                if (other.i != null)
                    return false;
            } else if (!i.equals(other.i))
                return false;
            if (allocatedClass == null) {
                if (other.allocatedClass != null)
                    return false;
            } else if (!allocatedClass.equals(other.allocatedClass))
                return false;
            return true;
        }
    }

    /**
     * Represents an allocation site in the code
     */
    public static final class AllocSiteNode {

        /**
         * Class allocation occurs in
         */
        private final IClass containingClass;
        /**
         * Allocated class
         */
        private final IClass allocatedClass;
        /**
         * String used for printing and debugging
         */
        private final String debugString;

        /**
         * Represents the allocation of a new object
         * 
         * @param debugString
         *            String for printing and debugging
         * @param expectedType
         *            type of the newly allocated object
         * @param allocatedClass
         *            class being allocated
         * @param containingClass
         *            class where allocation occurs
         */
        protected AllocSiteNode(String debugString, IClass allocatedClass, IClass containingClass) {
            this.debugString = debugString;
            this.containingClass = containingClass;
            this.allocatedClass = allocatedClass;
            if (debugString == null) {
                throw new RuntimeException("Need debug string");
            }
            if ("null".equals(debugString)) {
                throw new RuntimeException("Weird debug string");
            }
        }

        @Override
        public String toString() {
            return debugString;
        }

        public TypeReference getExpectedType() {
            return allocatedClass.getReference();
        }

        public IClass getContainingClass() {
            return containingClass;
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
    }
}
