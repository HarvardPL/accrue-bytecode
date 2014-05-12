package analysis.pointer.statements;

import java.util.LinkedHashMap;
import java.util.Map;

import util.print.PrettyPrinter;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.TypeReference;

public class AllocSiteNodeFactory {

    private static final Map<AllocSiteKey, AllocSiteNode> map = new LinkedHashMap<>();

    @SuppressWarnings("synthetic-access")
    protected static AllocSiteNode getAllocationNode(IClass instantiatedClass, IClass containingClass, SSAInstruction i) {
        AllocSiteKey key = new AllocSiteKey(instantiatedClass, containingClass, i);
        assert !map.containsKey(key) : "Duplicate allocation node.";
        AllocSiteNode n = new AllocSiteNode("new " + PrettyPrinter.parseType(instantiatedClass.getReference()),
                                        instantiatedClass, containingClass);
        map.put(key, n);
        return n;
    }

    private static class AllocSiteKey {
        private final IClass instantiatedClass;
        private final IClass containingClass;
        private final SSAInstruction i;

        public AllocSiteKey(IClass instantiatedClass, IClass containingClass, SSAInstruction i) {
            this.instantiatedClass = instantiatedClass;
            this.containingClass = containingClass;
            this.i = i;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((containingClass == null) ? 0 : containingClass.hashCode());
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
        private AllocSiteNode(String debugString, IClass instantiatedClass, IClass containingClass) {
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
