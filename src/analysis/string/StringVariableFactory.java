package analysis.string;

import java.util.LinkedHashMap;
import java.util.Map;

import util.OrderedPair;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * Factory for creating unique string variables for locals, static fields, and method summaries.
 * <p>
 * If assertions are turned on then duplication of nodes will throw an AssertionError where appropriate.
 */
public class StringVariableFactory {

    /**
     * string variables for static fields
     */
    private final Map<IField, StringVariable> staticFields = new LinkedHashMap<>();
    /**
     * string variables for local variables
     */
    private final Map<OrderedPair<Integer, IMethod>, StringVariable> locals = new LinkedHashMap<>();
    /**
     * variables for method exit
     */
    private final Map<MethodSummaryKey, StringVariable> methodExitSummaries = new LinkedHashMap<>();
    /**
     * Variables for string builders
     */
    private final Map<StringBuilderKey, StringVariable> stringBuilders = new LinkedHashMap<>();

    /**
     * Get the reference variable for the given local in the given method. Create if one does not already exist
     *
     * @param local local ID, the type of this should not be primitive or null
     * @param method method
     * @param pp Pretty printer used to get a name for the local (default name will be used if null)
     *
     * @return reference variable for the local
     */
    @SuppressWarnings("synthetic-access")
    protected StringVariable getOrCreateLocal(int local, IMethod method, PrettyPrinter pp) {
        OrderedPair<Integer, IMethod> key = new OrderedPair<>(local, method);
        StringVariable rv = locals.get(key);
        if (rv == null) {
            String name;
            if (pp != null) {
                name = pp.valString(local) + "-" + method.getName();
            }
            else {
                name = PrettyPrinter.getCanonical("v" + local + "-" + method.getName());
            }
            rv = new StringVariable(name, false);
            locals.put(key, rv);
        }
        return rv;
    }

    /**
     * Get the reference variable for the given local in the given method.
     *
     * @param local local ID, the type of this should not be primitive or null
     * @param method method
     * @param pp Pretty printer used to get a name for the local (default name will be used if null)
     *
     * @return reference variable for the local
     */
    public StringVariable getLocal(int local, IMethod method) {
        OrderedPair<Integer, IMethod> key = new OrderedPair<>(local, method);
        StringVariable rv = locals.get(key);
        if (rv == null) {
            throw new RuntimeException("Could not find StringVariable for local " + local + " in "
                    + PrettyPrinter.methodString(method));
        }
        return rv;
    }

    /**
     * Get a string variable for a method exit summary node. This should only be called once for any given arguments
     *
     * @param method method the summary node is for
     *
     * @return reference variable for a return value
     */
    @SuppressWarnings("synthetic-access")
    protected StringVariable createMethodReturn(IMethod method) {
        StringVariable rv = new StringVariable(PrettyPrinter.methodString(method) + "-" + ExitType.NORMAL, false);
        // These should only be created once assert that this is true
        assert methodExitSummaries.put(new MethodSummaryKey(method, ExitType.NORMAL), rv) == null;
        return rv;
    }

    /**
     * Get a string variable for a formal parameter summary node. This should only be called once for any given
     * arguments
     *
     * @param paramNum formal parameter index (by convention the 0th argument is "this" for non-static methods)
     * @param method method these are summary nodes for
     *
     * @return reference variable for a formal parameter
     */
    @SuppressWarnings("synthetic-access")
    protected StringVariable createFormal(int paramNum, IMethod method) {
        StringVariable rv = new StringVariable(PrettyPrinter.methodString(method) + "-formal(" + paramNum + ")", false);
        // These should only be created once assert that this is true
        assert methodExitSummaries.put(new MethodSummaryKey(method, paramNum), rv) == null;
        return rv;
    }

    /**
     * Get the string variable for the given static field
     *
     * @param field field to get the node for
     * @return string variable for the static field
     */
    @SuppressWarnings("synthetic-access")
    protected StringVariable getOrCreateStaticField(FieldReference field) {
        IField f = AnalysisUtil.getClassHierarchy().resolveField(field);
        StringVariable node = staticFields.get(f);
        if (node == null) {
            if (f.getFieldTypeReference().equals(TypeReference.JavaLangString)) {
                throw new RuntimeException("Trying to create reference variable for a static field with a primitive type.");
            }

            node = new StringVariable(PrettyPrinter.typeString(f.getDeclaringClass()) + "." + f.getName().toString(),
                                      true);
            staticFields.put(f, node);
        }
        return node;
    }

    /**
     * Get the string variable for the state of a StringBuilder at a particular program point
     *
     * @param valueNumber value number for the local variable holding the StringBuilder
     * @param method method where the call to append occurs
     * @param pc program counter for program point this is represents the value for (-1 for compiler generated, -2 for
     *            merge nodes)
     * @param bbNum basic block number
     * @param type type of variable being created (e.g. variable for a new StringBuilder, for a call result, etc)
     * @param pp pretty printer for the method containing the StringBuilder
     *
     * @return string variable for the state of the StringBuilder after tha call at the given PC
     */
    protected StringVariable createStringBuilder(int valueNumber, IMethod method, int bbNum, StringBuilderVarType type,
                                                 int pc, PrettyPrinter pp) {
        String name;
        if (pp != null) {
            name = pp.valString(valueNumber) + "@" + bbNum + "." + pc + "(" + type + ")-"
                    + PrettyPrinter.methodString(method);
        }
        else {
            name = "v" + valueNumber + "@" + bbNum + "." + pc + "-" + PrettyPrinter.methodString(method);
        }
        @SuppressWarnings("synthetic-access")
        StringVariable sv = new StringVariable(name, false);
        assert stringBuilders.put(new StringBuilderKey(valueNumber, method, bbNum, type, pc), sv) == null : " DUPLICATE: "
                + valueNumber + " " + method + " " + bbNum + " " + type + " " + pc;
        return sv;
    }

    /**
     * Key for the StringVariable representing the state of a StringBuilder at a particularPC
     */
    private static class StringBuilderKey {
        private final IMethod method;
        private final int pc;
        private final int valueNumber;
        private final int bbNum;
        private final StringBuilderVarType type;

        public StringBuilderKey(int valueNumber, IMethod method, int bbNum, StringBuilderVarType type, int pc) {
            this.valueNumber = valueNumber;
            this.method = method;
            this.bbNum = bbNum;
            this.type = type;
            this.pc = pc;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + bbNum;
            result = prime * result + ((method == null) ? 0 : method.hashCode());
            result = prime * result + pc;
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            result = prime * result + valueNumber;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            StringBuilderKey other = (StringBuilderKey) obj;
            if (bbNum != other.bbNum) {
                return false;
            }
            if (method == null) {
                if (other.method != null) {
                    return false;
                }
            }
            else if (!method.equals(other.method)) {
                return false;
            }
            if (pc != other.pc) {
                return false;
            }
            if (type != other.type) {
                return false;
            }
            if (valueNumber != other.valueNumber) {
                return false;
            }
            return true;
        }
    }

    /**
     * Key uniquely identifying a method summary node
     */
    private static class MethodSummaryKey {
        /**
         * Containing method
         */
        private final IMethod method;
        /**
         * Is this node for normal or exceptional exit
         */
        private final ExitType exitType;
        /**
         * Compute the hashcode once
         */
        private final int memoizedHashCode;
        /**
         * If this is a summary node for a formal parameter then this is the parameter number
         */
        private final int paramNum;

        /**
         * Create a new key for a method exit
         *
         * @param method method this node is for
         * @param exitType normal or exceptional exit
         */
        public MethodSummaryKey(IMethod method, ExitType exitType) {
            assert method != null;
            assert exitType != null;
            this.method = method;
            this.exitType = exitType;
            paramNum = -1;
            memoizedHashCode = computeHashCode();
        }

        /**
         * Create a new key for a formal parameter
         *
         * @param method method this node is for
         * @param paramNum index of the formal
         */
        public MethodSummaryKey(IMethod method, int paramNum) {
            assert paramNum >= 0;
            assert method != null;
            exitType = null;
            this.paramNum = paramNum;
            this.method = method;
            memoizedHashCode = computeHashCode();
        }

        public int computeHashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (exitType == null ? 0 : exitType.hashCode());
            result = prime * result + method.hashCode();
            result = prime * result + paramNum;
            return result;
        }

        @Override
        public int hashCode() {
            return memoizedHashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            MethodSummaryKey other = (MethodSummaryKey) obj;
            if (exitType == null) {
                if (other.exitType != null) {
                    return false;
                }
            }
            else if (!exitType.equals(other.exitType)) {
                return false;
            }
            if (!method.equals(other.method)) {
                return false;
            }
            if (paramNum != other.paramNum) {
                return false;
            }
            return true;
        }
    }

    /**
     * Represents a local variable or static field.
     */
    public static class StringVariable {

        /**
         * True if this node represents a static field, or other node for which only one global reference variable
         * should be created
         */
        private final boolean isSingleton;
        /**
         * String used for debugging
         */
        private final String debugString;

        /**
         * Create a new (unique) reference variable for a local variable, do not call this outside the pointer analysis.
         *
         * @param debugString String used for debugging and printing
         * @param isSingleton Whether this reference variable represents a static field, or other global singleton (for
         *            which only one reference variable replica will be created usually in the initial context)
         */
        private StringVariable(String debugString, boolean isSingleton) {
            assert debugString != null;
            assert !debugString.equals("null");

            this.debugString = debugString;
            this.isSingleton = isSingleton;
        }

        @Override
        public String toString() {
            return debugString;
        }

        @Override
        public final boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public final int hashCode() {
            return System.identityHashCode(this);
        }

        /**
         * Is this reference variable a singleton? That is, should there be only a single ReferenceVariableReplica for
         * it? This should return true for reference variables that represent e.g., static fields. Because there is only
         * one location represented by the static field, there should not be multiple replicas of the reference variable
         * that represents the static field.
         *
         * @return true if this is a static variable
         */
        public boolean isSingleton() {
            return isSingleton;
        }
    }
}
