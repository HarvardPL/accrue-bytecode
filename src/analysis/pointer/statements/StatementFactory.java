package analysis.pointer.statements;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.pointer.registrar.ReferenceVariableFactory;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Used to create points-to statements to be processed by the points-to analysis engine to construct a points-to graph.
 */
public class StatementFactory {

    /**
     * Flag to ensure that no two statements are created with the same parameters
     */
    public static boolean CHECK_FOR_DUPLICATES = true;
    /**
     * Map from a key (arguments used to create the points to statement) to points to statement, can be used to check
     * whether two identical points-to statements are created that are not the same Object.
     */
    private static final Map<StatementKey, PointsToStatement> paranoiaMap;
    static {
        if (CHECK_FOR_DUPLICATES) {
            paranoiaMap = new HashMap<>();
        } else {
            paranoiaMap = Collections.emptyMap();
        }
    }

    /**
     * Points-to graph statement for an assignment from an array element, v = a[i]
     * 
     * @param v
     *            Points-to graph node for the assignee
     * @param a
     *            Points-to graph node for the array being accessed
     * @param baseType
     *            base type of the array
     * @param ir
     *            Code this statement occurs in
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static ArrayToLocalStatement arrayToLocal(ReferenceVariable v, ReferenceVariable a, TypeReference baseType,
                                    IR ir, SSAArrayLoadInstruction i) {
        ArrayToLocalStatement s = new ArrayToLocalStatement(v, a, baseType, ir, i);
        checkForDuplicates(new StatementKey(v, a, baseType, ir, i), s);
        return s;
    }

    /**
     * Create a points-to statement for class initialization
     * 
     * @param clinits
     *            class initialization methods that might need to be called in the order they need to be called (i.e.
     *            element j is a super class of element j+1)
     * @param ir
     *            Code triggering the initialization
     * @param i
     *            Instruction triggering the initialization
     * @return statement to be processed during pointer analysis
     */
    public static ClassInitStatement classInit(List<IMethod> clinits, IR ir, SSAInstruction i) {
        ClassInitStatement s = new ClassInitStatement(clinits, ir, i);
        checkForDuplicates(new StatementKey(clinits, ir, i), s);
        return s;
    }

    /**
     * Statement for the assignment from a thrown exception to a caught exception or the summary node for the
     * exceptional exit to a method
     * 
     * @param thrown
     *            reference variable for the exception being thrown
     * @param caught
     *            reference variable for the caught exception (or summary for the method exit)
     * @param i
     *            instruction throwing the exception
     * @param ir
     *            code containing the instruction that throws the exception
     * @param notType
     *            types that the exception being caught cannot have since those types must have been caught by previous
     *            catch blocks
     * @return statement to be processed during pointer analysis
     */
    public static ExceptionAssignmentStatement exceptionAssignment(ReferenceVariable thrown, ReferenceVariable caught,
                                    SSAInstruction i, IR ir, Set<IClass> notType) {
        ExceptionAssignmentStatement s = new ExceptionAssignmentStatement(thrown, caught, i, ir, notType);
        checkForDuplicates(new StatementKey(thrown, caught, i, ir, notType), s);
        return s;
    }

    /**
     * Points-to statement for a field access assigned to a local, l = o.f
     * 
     * @param f
     *            field accessed
     * @param o
     *            receiver of field access
     * @param l
     *            local assigned into
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static FieldToLocalStatment fieldToLocal(FieldReference f, ReferenceVariable o, ReferenceVariable l, IR ir,
                                    SSAGetInstruction i) {
        FieldToLocalStatment s = new FieldToLocalStatment(f, o, l, ir, i);
        checkForDuplicates(new StatementKey(f, o, l, ir, i), s);
        return s;
    }

    /**
     * Statement for an assignment into an array, a[i] = v. Note that we do not reason about the individual array
     * elements.
     * 
     * @param array
     *            array assigned into
     * @param local
     *            assigned value
     * @param baseType
     *            type of the array elements
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static LocalToArrayStatement localToArrayContents(ReferenceVariable array, ReferenceVariable local,
                                    TypeReference baseType, IR ir, SSAArrayStoreInstruction i) {
        LocalToArrayStatement s = new LocalToArrayStatement(array, local, baseType, ir, i);
        checkForDuplicates(new StatementKey(array, local, baseType, ir, i), s);
        return s;
    }

    /**
     * Statement for an assignment into a field, o.f = v
     * 
     * @param f
     *            field assigned to
     * @param o
     *            receiver of field access
     * @param v
     *            value assigned
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static LocalToFieldStatement localToField(FieldReference f, ReferenceVariable o, ReferenceVariable v, IR ir,
                                    SSAPutInstruction i) {
        LocalToFieldStatement s = new LocalToFieldStatement(f, o, v, ir, i);
        checkForDuplicates(new StatementKey(f, o, v, ir, i), s);
        return s;
    }

    /**
     * Statement for a local assignment, left = right
     * 
     * @param left
     *            assignee
     * @param right
     *            the assigned value
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static LocalToLocalStatement localToLocal(ReferenceVariable left, ReferenceVariable right, IR ir,
                                    SSAInstruction i) {
        LocalToLocalStatement s = new LocalToLocalStatement(left, right, ir, i);
        checkForDuplicates(new StatementKey(left, right, ir, i), s);
        return s;
    }

    /**
     * Statement for an assignment from a local into a static field, ClassName.staticField = local
     * 
     * @param staticField
     *            the assigned value
     * @param local
     *            assignee
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static LocalToStaticFieldStatement localToStaticField(ReferenceVariable staticField,
                                    ReferenceVariable local, IR ir, SSAPutInstruction i) {
        LocalToStaticFieldStatement s = new LocalToStaticFieldStatement(staticField, local, ir, i);
        checkForDuplicates(new StatementKey(staticField, local, ir, i), s);
        return s;
    }

    /**
     * Statement for array contents assigned to an inner array during multidimensional array creation. This means that
     * any assignments to the inner array will correctly point to an array with dimension one less than the outer array.
     * <p>
     * int[] b = new int[5][4]
     * <p>
     * results in
     * <p>
     * COMPILER-GENERATED = new int[5]
     * <p>
     * b.[contents] = COMPILER-GENERATED
     * 
     * @param outerArray
     *            points-to graph node for outer array
     * @param innerArray
     *            points-to graph node for inner array
     * @param innerArrayType
     *            type of the inner array
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            New Array instruction that generated the multidimensional array
     */
    public static LocalToArrayStatement multidimensionalArrayContents(ReferenceVariable outerArray,
                                    ReferenceVariable innerArray, IR ir, SSANewInstruction i) {
        LocalToArrayStatement s = new LocalToArrayStatement(outerArray, innerArray, innerArray.getExpectedType(), ir, i);
        checkForDuplicates(new StatementKey(outerArray, innerArray, ir, i), s);
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of a JVM generated exception (e.g. NullPointerException),
     * and the assignment of this new exception to a local variable
     * 
     * @param exceptionAssignee
     *            Reference variable for the local variable the exception is assigned to after being created
     * @param exceptionClass
     *            Class for the exception
     * @param ir
     *            code containing the instruction throwing the exception
     * @param i
     *            exception throwing the exception
     * @return a statement representing the allocation of a JVM generated exception to a local variable
     */
    public static NewStatement newForGeneratedException(ReferenceVariable exceptionAssignee, IClass exceptionClass,
                                    IR ir, SSAInstruction i, AllocSiteNodeFactory asnFactory) {
        NewStatement s = new NewStatement(exceptionAssignee, exceptionClass, ir, i, asnFactory);
        checkForDuplicates(new StatementKey(exceptionAssignee, exceptionClass, ir, i, asnFactory), s);
        return s;
    }

    /**
     * Get a points-to statement representing the exit from a native method
     * 
     * @param summaryNode
     *            Reference variable for the method exit summary node
     * @param ir
     *            code containing the native method invocation
     * @param i
     *            native method invocation
     * @param exitClass
     *            WALA representation of the the return type class
     * @param exitType
     *            whether this node is for exceptional or normal exit
     * @param resolved
     *            resolved method this is a node for
     * @return a statement representing the (compiler-generated) allocation for a native method call
     */
    public static NewStatement newForNativeExit(ReferenceVariable summaryNode, IR ir, SSAInvokeInstruction i,
                                    IClass exitClass, ExitType exitType, IMethod resolved,
                                    AllocSiteNodeFactory asnFactory) {
        NewStatement s = new NewStatement(summaryNode, exitClass, ir, i, exitType, resolved, asnFactory);
        checkForDuplicates(new StatementKey(summaryNode, exitClass, ir, i, exitType, resolved, asnFactory), s);
        return s;
    }

    /**
     * Points-to graph statement for a "new" instruction, e.g. Object o = new Object()
     * 
     * @param result
     *            Points-to graph node for the assignee of the new
     * @param newClass
     *            Class being created
     * @param cha
     *            class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static NewStatement newForNormalAlloc(ReferenceVariable result, IClass newClass, IR ir, SSANewInstruction i,
                                    AllocSiteNodeFactory asnFactory) {
        NewStatement s = new NewStatement(result, newClass, ir, i, asnFactory);
        checkForDuplicates(new StatementKey(result, newClass, ir, i, asnFactory), s);
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of the value field of a string
     * 
     * @param local
     *            Reference variable for the local variable for the string at the allocation site
     * @param ir
     *            code containing the instruction throwing the exception
     * @param i
     *            exception throwing the exception
     * @param charArrayClass
     *            WALA representation of a char[]
     * @return a statement representing the allocation of a new string literal's value field
     */
    public static NewStatement newForStringField(String name, ReferenceVariable local, IR ir, SSAInstruction i,
                                    IClass charArrayClass, AllocSiteNodeFactory asnFactory) {
        NewStatement s = new NewStatement(name, local, charArrayClass, ir, i, asnFactory);
        checkForDuplicates(new StatementKey(name, local, charArrayClass, ir, i, asnFactory), s);
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of a String literal
     * 
     * @param local
     *            Reference variable for the local variable for the string at the allocation site
     * @param ir
     *            code containing the instruction throwing the exception
     * @param i
     *            exception throwing the exception
     * @param stringClass
     *            WALA representation of the java.lang.String class
     * @return a statement representing the allocation of a new string literal
     */
    public static NewStatement newForStringLiteral(String name, ReferenceVariable local, IR ir, SSAInstruction i,
                                    IClass stringClass, AllocSiteNodeFactory asnFactory) {
        NewStatement s = new NewStatement(name, local, stringClass, ir, i, asnFactory);
        checkForDuplicates(new StatementKey(name, local, stringClass, ir, i, asnFactory), s);
        return s;
    }

    /**
     * Points-to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     * 
     * @param v
     *            value assigned into
     * @param xs
     *            list of arguments to the phi, v is a choice amongst these
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static PhiStatement phiToLocal(ReferenceVariable v, List<ReferenceVariable> xs, IR ir, SSAPhiInstruction i) {
        PhiStatement s = new PhiStatement(v, xs, ir, i);
        checkForDuplicates(new StatementKey(v, xs, ir, i), s);
        return s;
    }

    /**
     * Create a points-to statement for a return instruction
     * 
     * @param result
     *            Node for return result
     * @param returnSummary
     *            Node summarizing all return values for the method
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static ReturnStatement returnStatement(ReferenceVariable result, ReferenceVariable returnSummary, IR ir,
                                    SSAReturnInstruction i) {
        ReturnStatement s = new ReturnStatement(result, returnSummary, ir, i);
        checkForDuplicates(new StatementKey(result, returnSummary, ir, i), s);
        return s;
    }

    /**
     * Points-to statement for a special method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param callee
     *            Method being called
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node representing the exception thrown by the callee and implicit exceptions
     * @param callerIR
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @param util
     *            Used to create the IR for the callee
     * @param rvFactory
     *            factory for managing the creation of reference variables for local variables and static fields
     * @return statement to be processed during pointer analysis
     */
    public static SpecialCallStatement specialCall(CallSiteReference callSite, IMethod resolvedCallee,
                                    ReferenceVariable receiver, List<ReferenceVariable> actuals,
                                    ReferenceVariable resultNode, ReferenceVariable exceptionNode, IR callerIR,
                                    SSAInvokeInstruction i, WalaAnalysisUtil util, ReferenceVariableFactory rvFactory) {
        SpecialCallStatement s = new SpecialCallStatement(callSite, resolvedCallee, receiver, actuals, resultNode,
                                        exceptionNode, callerIR, i, util, rvFactory);
        checkForDuplicates(new StatementKey(callSite, resolvedCallee, receiver, actuals, resultNode, exceptionNode,
                                        callerIR, i, util, rvFactory), s);
        return s;
    }

    /**
     * Points-to statement for a special method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param callee
     *            Method being called
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node representing the exception thrown by the callee and implicit exceptions
     * @param callerIR
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @param util
     *            Used to create the IR for the callee
     * @param rvFactory
     *            factory for managing the creation of reference variables for local variables and static fields
     * @return statement to be processed during pointer analysis
     */
    public static StaticCallStatement staticCall(CallSiteReference callSite, IMethod callee,
                                    List<ReferenceVariable> actuals, ReferenceVariable resultNode,
                                    ReferenceVariable exceptionNode, IR callerIR, SSAInvokeInstruction i,
                                    WalaAnalysisUtil util, ReferenceVariableFactory rvFactory) {
        StaticCallStatement s = new StaticCallStatement(callSite, callee, actuals, resultNode, exceptionNode, callerIR,
                                        i, util, rvFactory);
        checkForDuplicates(new StatementKey(callSite, callee, actuals, resultNode, exceptionNode, callerIR, i, util,
                                        rvFactory), s);
        return s;
    }

    /**
     * Statement for an assignment from a local into a static field, ClassName.staticField = local
     * 
     * @param staticField
     *            the assigned value
     * @param local
     *            assignee
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static StaticFieldToLocalStatement staticFieldToLocal(ReferenceVariable local,
                                    ReferenceVariable staticField, IR ir, SSAGetInstruction i) {
        StaticFieldToLocalStatement s = new StaticFieldToLocalStatement(local, staticField, ir, i);
        checkForDuplicates(new StatementKey(local, staticField, ir, i), s);
        return s;
    }

    /**
     * Statement for an assignment into a the value field of a new string literal
     * 
     * @param f
     *            field reference for the string
     * @param o
     *            string
     * @param v
     *            allocation of the value field
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public static LocalToFieldStatement stringLiteralValueField(FieldReference f, ReferenceVariable o,
                                    ReferenceVariable v, IR ir, SSAInstruction i) {
        assert f.getName().toString().equals("value") && f.getDeclaringClass().equals(TypeReference.JavaLangString) : "This method should only be called for String.value for a string literal";
        LocalToFieldStatement s = new LocalToFieldStatement(f, o, v, ir, i);
        checkForDuplicates(new StatementKey(f, o, v, ir, i), s);
        return s;
    }

    /**
     * Points-to statement for a virtual method invocation.
     * 
     * @param callSite
     *            Method call site
     * @param callee
     *            Method being called
     * @param receiver
     *            Receiver of the call
     * @param actuals
     *            Actual arguments to the call
     * @param resultNode
     *            Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param exceptionNode
     *            Node representing the exception thrown by this call (if any)
     * @param cha
     *            Class hierarchy
     * @param ir
     *            Code for the method the points-to statement came from
     * @param i
     *            Instruction that generated this points-to statement
     * @param rvFactory
     *            factory for managing the creation of reference variables for local variables and static fields
     * @return statement to be processed during pointer analysis
     */
    public static VirtualCallStatement virtualCall(CallSiteReference callSite, MethodReference callee,
                                    ReferenceVariable receiver, List<ReferenceVariable> actuals,
                                    ReferenceVariable resultNode, ReferenceVariable exceptionNode, IClassHierarchy cha,
                                    IR ir, SSAInvokeInstruction i, WalaAnalysisUtil util,
                                    ReferenceVariableFactory rvFactory) {
        VirtualCallStatement s = new VirtualCallStatement(callSite, callee, receiver, actuals, resultNode,
                                        exceptionNode, cha, ir, i, util, rvFactory);
        checkForDuplicates(new StatementKey(callSite, callee, receiver, actuals, resultNode, exceptionNode, cha, ir, i,
                                        util, rvFactory), s);
        return s;
    }

    /**
     * If in DEBUG mode check whether a statement has already been created for the given key
     * 
     * @param key
     *            key to check
     * @param s
     *            points-to statement for the key
     */
    private static void checkForDuplicates(StatementKey key, PointsToStatement s) {
        if (CHECK_FOR_DUPLICATES) {
            if (paranoiaMap.containsKey(key)) {
                throw new RuntimeException("Duplicate statement created for " + s + " the existing one was "
                                                + paranoiaMap.get(key));
            }
            paranoiaMap.put(key, s);

        }
    }

    /**
     * DEBUG paranoia map key. Two different PointsToStatement objects should never have the same StatementKey
     */
    private static class StatementKey {

        private final Object key1;
        private final Object key2;
        private final Object key3;
        private final Object key4;
        private final Object key5;
        private final Object key6;
        private final Object key7;
        private final Object key8;
        private final Object key9;
        private final Object key10;
        private final Object key11;

        public StatementKey(Object key1, Object key2, Object key3) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = null;
            this.key5 = null;
            this.key6 = null;
            this.key7 = null;
            this.key8 = null;
            this.key9 = null;
            this.key10 = null;
            this.key11 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = null;
            this.key6 = null;
            this.key7 = null;
            this.key8 = null;
            this.key9 = null;
            this.key10 = null;
            this.key11 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = null;
            this.key7 = null;
            this.key8 = null;
            this.key9 = null;
            this.key10 = null;
            this.key11 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5, Object key6) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = key6;
            this.key7 = null;
            this.key8 = null;
            this.key9 = null;
            this.key10 = null;
            this.key11 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5, Object key6, Object key7) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = key6;
            this.key7 = key7;
            this.key8 = null;
            this.key9 = null;
            this.key10 = null;
            this.key11 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5, Object key6, Object key7,
                                        Object key8, Object key9) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = key6;
            this.key7 = key7;
            this.key8 = key8;
            this.key9 = key9;
            this.key10 = null;
            this.key11 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5, Object key6, Object key7,
                                        Object key8, Object key9, Object key10) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = key6;
            this.key7 = key7;
            this.key8 = key8;
            this.key9 = key9;
            this.key10 = key10;
            this.key11 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5, Object key6, Object key7,
                                        Object key8, Object key9, Object key10, Object key11) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = key6;
            this.key7 = key7;
            this.key8 = key8;
            this.key9 = key9;
            this.key10 = key10;
            this.key11 = key11;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            StatementKey other = (StatementKey) obj;
            if (key1 == null) {
                if (other.key1 != null)
                    return false;
            } else if (!key1.equals(other.key1))
                return false;
            if (key10 == null) {
                if (other.key10 != null)
                    return false;
            } else if (!key10.equals(other.key10))
                return false;
            if (key11 == null) {
                if (other.key11 != null)
                    return false;
            } else if (!key11.equals(other.key11))
                return false;
            if (key2 == null) {
                if (other.key2 != null)
                    return false;
            } else if (!key2.equals(other.key2))
                return false;
            if (key3 == null) {
                if (other.key3 != null)
                    return false;
            } else if (!key3.equals(other.key3))
                return false;
            if (key4 == null) {
                if (other.key4 != null)
                    return false;
            } else if (!key4.equals(other.key4))
                return false;
            if (key5 == null) {
                if (other.key5 != null)
                    return false;
            } else if (!key5.equals(other.key5))
                return false;
            if (key6 == null) {
                if (other.key6 != null)
                    return false;
            } else if (!key6.equals(other.key6))
                return false;
            if (key7 == null) {
                if (other.key7 != null)
                    return false;
            } else if (!key7.equals(other.key7))
                return false;
            if (key8 == null) {
                if (other.key8 != null)
                    return false;
            } else if (!key8.equals(other.key8))
                return false;
            if (key9 == null) {
                if (other.key9 != null)
                    return false;
            } else if (!key9.equals(other.key9))
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((key1 == null) ? 0 : key1.hashCode());
            result = prime * result + ((key10 == null) ? 0 : key10.hashCode());
            result = prime * result + ((key11 == null) ? 0 : key11.hashCode());
            result = prime * result + ((key2 == null) ? 0 : key2.hashCode());
            result = prime * result + ((key3 == null) ? 0 : key3.hashCode());
            result = prime * result + ((key4 == null) ? 0 : key4.hashCode());
            result = prime * result + ((key5 == null) ? 0 : key5.hashCode());
            result = prime * result + ((key6 == null) ? 0 : key6.hashCode());
            result = prime * result + ((key7 == null) ? 0 : key7.hashCode());
            result = prime * result + ((key8 == null) ? 0 : key8.hashCode());
            result = prime * result + ((key9 == null) ? 0 : key9.hashCode());
            return result;
        }
    }

}
