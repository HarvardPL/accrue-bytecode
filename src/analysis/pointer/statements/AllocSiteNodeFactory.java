package analysis.pointer.statements;

import java.util.HashMap;
import java.util.Map;

import util.ImplicitEx;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.ExitType;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;

public class AllocSiteNodeFactory {

    private final Map<AllocSiteKey, AllocSiteNode> map = new HashMap<>();
    private final Map<String, String> stringMemo = new HashMap<>();

    protected AllocSiteNode getAllocationNode(IClass instantiatedClass, IClass allocatingClass,
                                    SSAInstruction i, Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(instantiatedClass, allocatingClass, i, disambiguationKey);
        assert !map.containsKey(key) : "Duplicate normal allocation node: " + instantiatedClass + " from "
                                        + allocatingClass + " for " + i;
        AllocSiteNode n = new AllocSiteNode(getCanonical("new "
                                        + PrettyPrinter.typeString(instantiatedClass.getReference())),
                                        instantiatedClass, allocatingClass);
        map.put(key, n);
        return n;
    }

    protected AllocSiteNode getGeneratedAllocationNode(String name, IClass instantiatedClass,
                                    IClass allocatingClass,
                                    SSAInstruction i, Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(instantiatedClass, allocatingClass, i, disambiguationKey);
        assert !map.containsKey(key) : "Duplicate generated allocation node: " + instantiatedClass + " from "
                                        + allocatingClass + " for " + i;
        AllocSiteNode n = new AllocSiteNode(name, instantiatedClass, allocatingClass);
        map.put(key, n);
        return n;
    }

    protected AllocSiteNode getGeneratedExceptionNode(IClass instantiatedClass, IClass allocatingClass,
                                    SSAInstruction i, Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(instantiatedClass, allocatingClass, i, disambiguationKey);
        assert !map.containsKey(key) : "Duplicate generated allocation node: " + instantiatedClass + " from "
                                        + allocatingClass + " for " + i;
        AllocSiteNode n = new AllocSiteNode(ImplicitEx.fromType(instantiatedClass.getReference()).toString(),
                                        instantiatedClass,
                                        allocatingClass);
        map.put(key, n);
        return n;
    }

    protected AllocSiteNode getAllocationNodeForNative(IClass instantiatedClass, IClass allocatingClass,
                                    SSAInvokeInstruction nativeInvoke, ExitType type, Object disambiguationKey) {
        AllocSiteKey key = new AllocSiteKey(instantiatedClass, allocatingClass, nativeInvoke, type, disambiguationKey);
        assert !map.containsKey(key) : "Duplicate native allocation node: " + instantiatedClass + " from "
                                        + allocatingClass + " for " + type;
        AllocSiteNode n = new AllocSiteNode(getCanonical("new "
                                        + PrettyPrinter.typeString(instantiatedClass.getReference())
                                        + " (compiler-generated-native)"), instantiatedClass, allocatingClass);
        map.put(key, n);
        return n;
    }

    /**
     * Get the canonical version of a string
     * 
     * @param s
     *            string to get
     * @return String that is .equal to the string passed in, but is the canonical version
     */
    private String getCanonical(String s) {
        String canonical = stringMemo.get(s);
        if (canonical == null) {
            canonical = s;
            stringMemo.put(canonical, canonical);
        }
        return canonical;
    }

    private static class AllocSiteKey {
        private final IClass instantiatedClass;
        private final IClass containingClass;
        private final SSAInstruction i;
        private final ExitType exitType;
        private final Object disambiguationKey;

        public AllocSiteKey(IClass instantiatedClass, IClass containingClass, SSAInstruction i, Object disambiguationKey) {
            assert !(disambiguationKey instanceof ExitType) : "Missing argument for disambiguation key";
            this.instantiatedClass = instantiatedClass;
            this.containingClass = containingClass;
            this.i = i;
            this.exitType = null;
            this.disambiguationKey = disambiguationKey;
        }

        public AllocSiteKey(IClass instantiatedClass, IClass containingClass, SSAInvokeInstruction nativeCall,
                                        ExitType exitType, Object disambiguationKey) {
            this.instantiatedClass = instantiatedClass;
            this.containingClass = containingClass;
            this.i = nativeCall;
            this.exitType = exitType;
            this.disambiguationKey = disambiguationKey;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((containingClass == null) ? 0 : containingClass.hashCode());
            result = prime * result + ((disambiguationKey == null) ? 0 : disambiguationKey.hashCode());
            result = prime * result + ((exitType == null) ? 0 : exitType.hashCode());
            result = prime * result + ((i == null) ? 0 : i.hashCode());
            result = prime * result + ((instantiatedClass == null) ? 0 : instantiatedClass.hashCode());
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
            if (containingClass == null) {
                if (other.containingClass != null)
                    return false;
            } else if (!containingClass.equals(other.containingClass))
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
            if (instantiatedClass == null) {
                if (other.instantiatedClass != null)
                    return false;
            } else if (!instantiatedClass.equals(other.instantiatedClass))
                return false;
            return true;
        }
    }

    /**
     * Represents an allocation site in the code
     */
    public static class AllocSiteNode {

        /**
         * Class allocation occurs in
         */
        private final IClass containingClass;
        /**
         * Allocated class
         */
        private final IClass instantiatedClass;

        /**
         * Unique ID, used for testing whether two {@link AllocSiteNode}s are
         * equal
         */
        private final int id;
        /**
         * String used for printing and debugging
         */
        private final String debugString;
        /**
         * Counter for unique IDs
         */
        private static int count;

        /**
         * Represents the allocation of a new object
         * 
         * @param debugString
         *            String for printing and debugging
         * @param expectedType
         *            type of the newly allocated object
         * @param instantiatedClass
         *            class being allocated
         * @param containingClass
         *            class where allocation occurs
         */
        protected AllocSiteNode(String debugString, IClass instantiatedClass, IClass containingClass) {
            this.id = ++count;
            this.debugString = debugString;
            this.containingClass = containingClass;
            this.instantiatedClass = instantiatedClass;
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
            return instantiatedClass.getReference();
        }

        public IClass getContainingClass() {
            return containingClass;
        }

        public IClass getInstantiatedClass() {
            return instantiatedClass;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            AllocSiteNode other = (AllocSiteNode) obj;
            if (id != other.id)
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
