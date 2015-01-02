package analysis.pointer.statements;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSAInstruction;
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
     * Map from a key (arguments used to create the points to statement) to points to statement, can be used to check
     * whether two identical points-to statements are created that are not the same Object. This is only active when
     * assertions are turned on.
     */
    private final Map<StatementKey, PointsToStatement> map = new HashMap<>();

    /**
     * Description used for a string literal value field
     */
    public static final String STRING_LIT_FIELD_DESC = "new String.value (compiler-generated)";

    /**
     * Points-to graph statement for an assignment from an array element, v = a[i], note that we do not track array
     * elements.
     *
     * @param v Points-to graph node for the assignee
     * @param a Points-to graph node for the array being accessed
     * @param baseType base type of the array
     * @param pp program point the statement was created at
     * @return statement to be processed during pointer analysis
     */
    public ArrayToLocalStatement arrayToLocal(ReferenceVariable v, ReferenceVariable a, TypeReference baseType,
                                              ProgramPoint pp) {
        assert v != null;
        assert a != null;
        assert baseType != null;
        assert pp != null;

        ArrayToLocalStatement s = new ArrayToLocalStatement(v, a, baseType, pp);
        assert map.put(new StatementKey(v), s) == null;
        return s;
    }

    /**
     * Create a points-to statement for class initialization
     *
     * @param clinits class initialization methods that might need to be called in the order they need to be called
     *            (i.e. element j is a super class of element j+1)
     * @param pp program point the statement was created at
     * @param i Instruction triggering the initialization
     * @return statement to be processed during pointer analysis
     */
    public ClassInitStatement classInit(List<IMethod> clinits, ProgramPoint pp, SSAInstruction i) {
        assert clinits != null;
        assert !clinits.isEmpty();
        assert pp != null;
        assert i != null;

        ClassInitStatement s = new ClassInitStatement(clinits, pp);
        // Could be duplicated in the same method, if we want a unique key use the instruction
        assert map.put(new StatementKey(clinits, i), s) == null : "Duplicate classinit " + clinits + " from " + i
                + " in " + pp;
        return s;
    }

    /**
     * Statement for the assignment from an exception to a catch-block formal or the summary node representing the
     * exception value on method exit
     *
     * @param thrown reference variable for the exception being thrown
     * @param caught reference variable for the caught exception (or summary for the method exit)
     * @param notType types that the exception being caught cannot have since those types must have been caught by
     *            previous catch blocks
     * @param pp program point the statement was created for
     * @param isToMethodSummaryVariable true if the variable we are assigning into, <code>caught</code>, is the
     *            exception summary node for a method
     * @return statement to be processed during pointer analysis
     */
    public static ExceptionAssignmentStatement exceptionAssignment(ReferenceVariable thrown, ReferenceVariable caught,
                                                                   Set<IClass> notType, ProgramPoint pp) {
        assert thrown != null;
        assert caught != null;
        assert notType != null;
        assert pp != null;

        ExceptionAssignmentStatement s = new ExceptionAssignmentStatement(thrown, caught, notType, pp);
        return s;

    }

    /**
     * Points-to statement for a field access assigned to a local, l = o.f
     *
     * @param l local assigned into
     * @param o receiver of field access
     * @param f field accessed
     * @param pp program point the statement was created at
     * @return statement to be processed during pointer analysis
     */
    public FieldToLocalStatement fieldToLocal(ReferenceVariable l, ReferenceVariable o, FieldReference f,
                                              ProgramPoint pp) {
        assert l != null;
        assert o != null;
        assert f != null;
        assert pp != null;

        FieldToLocalStatement s = new FieldToLocalStatement(l, o, f, pp);
        assert map.put(new StatementKey(l), s) == null;
        return s;
    }

    /**
     * Statement for an assignment into an array, a[i] = v. Note that we do not reason about the individual array
     * elements.
     *
     * @param array array assigned into
     * @param local assigned value
     * @param baseType type of the array elements
     * @param pp program point the statement was created at
     * @param i instruction the statement was for
     * @return statement to be processed during pointer analysis
     */
    public LocalToArrayStatement localToArrayContents(ReferenceVariable array, ReferenceVariable local,
                                                      TypeReference baseType, ProgramPoint pp,
                                                      SSAArrayStoreInstruction i) {
        assert array != null;
        assert local != null;
        assert baseType != null;
        assert pp != null;
        assert i != null;

        LocalToArrayStatement s = new LocalToArrayStatement(array, local, baseType, pp);
        // Could be duplicated in the same method, if we want a unique key use the instruction
        assert map.put(new StatementKey(array, local, i), s) == null;
        return s;
    }

    /**
     * Statement for an assignment into a field, o.f = v
     *
     * @param o receiver of field access
     * @param f field assigned to
     * @param v value assigned
     * @param pp program point the statement was created at
     * @param i instruction the statement is for
     * @return statement to be processed during pointer analysis
     */
    public LocalToFieldStatement localToField(ReferenceVariable o, FieldReference f, ReferenceVariable v,
                                              ProgramPoint pp, SSAPutInstruction i) {
        assert o != null;
        assert f != null;
        assert v != null;
        assert pp != null;
        assert i != null;

        LocalToFieldStatement s = new LocalToFieldStatement(o, f, v, pp);
        // Could be duplicated in the same method, if we want a unique key use the instruction
        assert map.put(new StatementKey(o, f, i), s) == null;
        return s;
    }

    /**
     * Statement for a local assignment, left = right. No filtering will be performed based on type.
     *
     * @param left assignee
     * @param right the assigned value
     * @param pp program point the statement was created at
     * @return statement to be processed during pointer analysis
     */
    public LocalToLocalStatement localToLocal(ReferenceVariable left, ReferenceVariable right, ProgramPoint pp) {
        assert left != null;
        assert right != null;
        assert pp != null;

        LocalToLocalStatement s = new LocalToLocalStatement(left, right, pp, false);
        assert map.put(new StatementKey(left), s) == null;
        return s;
    }

    /**
     * Statement for a local assignment, left = right. Filtering will be performed based on type.
     *
     * @param left assignee
     * @param right the assigned value
     * @param pp program point the statement was created at
     * @return statement to be processed during pointer analysis
     */
    public LocalToLocalStatement localToLocalFiltered(ReferenceVariable left, ReferenceVariable right, ProgramPoint pp) {
        assert left != null;
        assert right != null;
        assert pp != null;

        LocalToLocalStatement s = new LocalToLocalStatement(left, right, pp, true);
        assert map.put(new StatementKey(left), s) == null;
        return s;
    }

    /**
     * Statement for an assignment from a local into a static field, ClassName.staticField = local
     *
     * @param staticField the assigned value
     * @param local assignee
     * @param pp program point the statement was created at
     * @param i Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public LocalToStaticFieldStatement localToStaticField(ReferenceVariable staticField, ReferenceVariable local,
                                                          ProgramPoint pp, SSAPutInstruction i) {
        assert staticField != null;
        assert local != null;
        assert pp != null;
        assert i != null;

        LocalToStaticFieldStatement s = new LocalToStaticFieldStatement(staticField, local, pp);
        // Could be duplicated in the same method, if we want a unique key use the instruction
        assert map.put(new StatementKey(staticField, i), s) == null;
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
     * @param outerArray points-to graph node for outer array
     * @param innerArray points-to graph node for inner array
     * @param innerArrayType type of the inner array
     * @param pp program point the statement was created at
     * @return statement to be processed by the pointer analysis
     */
    public LocalToArrayStatement multidimensionalArrayContents(ReferenceVariable outerArray,
                                                               ReferenceVariable innerArray, ProgramPoint pp) {
        assert outerArray != null;
        assert innerArray != null;
        assert pp != null;

        LocalToArrayStatement s = new LocalToArrayStatement(outerArray, innerArray, innerArray.getExpectedType(), pp);
        assert map.put(new StatementKey(outerArray, innerArray), s) == null;
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of a JVM generated exception (e.g. NullPointerException),
     * and the assignment of this new exception to a local variable. We also use this method when we are using a single
     * allocation per throwable type.
     *
     * @param exceptionAssignee Reference variable for the local variable the exception is assigned to after being
     *            created
     * @param exceptionClass Class for the exception
     * @param pp program point the statement was created at
     *
     * @return a statement representing the allocation of a JVM generated exception to a local variable
     */
    public NewStatement newForGeneratedException(ReferenceVariable exceptionAssignee, IClass exceptionClass,
                                                 ProgramPoint pp) {
        return newForGeneratedObject(exceptionAssignee, exceptionClass, pp, PrettyPrinter.typeString(exceptionClass));
    }

    /**
     * Get a points-to statement representing allocation generated for a native method with no signature
     *
     * @param summary Reference variable for the method summary node assigned to after being created
     * @param allocatedClass Class being allocated
     * @param m native method
     * @param pp program point for the generated object
     * @return a statement representing the allocation for a native method with no signature
     */
    public NewStatement newForNative(ReferenceVariable summary, IClass allocatedClass, IMethod m, ProgramPoint pp) {
        assert m.isNative();
        assert pp.containingProcedure().equals(m);

        return newForGeneratedObject(summary,
                                     allocatedClass,
                                     pp,
                                     PrettyPrinter.getCanonical("NATIVE-" + PrettyPrinter.typeString(allocatedClass)));
    }

    /**
     * Get a points-to statement representing allocation generated for a native method with no signature
     *
     * @param v Reference variable for the method summary node assigned to after being created
     * @param allocatedClass Class being allocated
     * @param pp program point the generated object was created at
     * @return a statement representing the allocation for a native method with no signature
     */
    public NewStatement newForGeneratedObject(ReferenceVariable v, IClass allocatedClass, ProgramPoint pp,
                                              String description) {
        assert v != null;
        assert allocatedClass != null;
        assert pp != null;

        NewStatement s = new NewStatement(description, v, allocatedClass, pp, false);
        assert map.put(new StatementKey(v), s) == null;
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of an inner array of a multidimensional array
     *
     * @param innerArray Reference variable for the local variable the array is assigned to after being created
     * @param innerArrayClass Class for the array
     * @param pp program point the statement was created at
     * @return a statement representing the allocation of the inner array of a multidimensional array
     */
    public NewStatement newForInnerArray(ReferenceVariable innerArray, IClass innerArrayClass, ProgramPoint pp) {
        String name = PrettyPrinter.getCanonical("GENERATED-" + PrettyPrinter.typeString(innerArrayClass));
        return newForGeneratedObject(innerArray, innerArrayClass, pp, name);
    }

    /**
     * Points-to graph statement for a "new" instruction, e.g. result = new Object()
     *
     * @param result Points-to graph node for the assignee of the new
     * @param newClass Class being created
     * @param pp program point the statement was created at
     * @param pc The program counter where the allocation occured
     * @param lineNumber line number from source code if one can be found, -1 otherwise
     * @return statement to be processed during pointer analysis
     */
    public NewStatement newForNormalAlloc(ReferenceVariable result, IClass newClass, ProgramPoint pp, int pc,
                                          int lineNumber) {
        assert result != null;
        assert newClass != null;
        assert pp != null;

        NewStatement s = new NewStatement(result, newClass, pp, pc, lineNumber);
        assert map.put(new StatementKey(result), s) == null;
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of the value field of a string
     *
     * @param local Reference variable for the local variable for the string at the allocation site
     * @param pp program point the statement was created at
     * @return a statement representing the allocation of a new string literal's value field
     */
    public NewStatement newForStringField(ReferenceVariable local, ProgramPoint pp) {
        return newForGeneratedObject(local, AnalysisUtil.getStringValueClass(), pp, STRING_LIT_FIELD_DESC);
    }

    /**
     * Get a points-to statement representing the allocation of a String literal
     *
     * @param literalValue String value of the new string literal
     * @param local Reference variable for the local variable for the string at the allocation site
     * @param pp program point the statement was created at
     *
     * @return a statement representing the allocation of a new string literal
     */
    public NewStatement newForStringLiteral(String literalValue, ReferenceVariable local, ProgramPoint pp) {
        assert local != null;
        assert pp != null;

        NewStatement s = new NewStatement(literalValue, local, AnalysisUtil.getStringClass(), pp, true);
        assert map.put(new StatementKey(local, literalValue), s) == null;
        return s;
    }

    /**
     * Statement for a local assignment of null left = null.
     *
     * @param left assignee
     * @param pp program point the statement was created at
     *
     * @return statement to be processed during pointer analysis
     */
    public NullToLocalStatement nullToLocal(ReferenceVariable left, ProgramPoint pp) {
        assert left != null : "Null reference variable for " + pp;
        assert pp != null : "Null program point for " + left;

        NullToLocalStatement s = new NullToLocalStatement(left, pp);
        assert map.put(new StatementKey(left), s) == null : "Duplicate statement " + s;
        return s;
    }

    /**
     * Points-to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     *
     * @param v value assigned into
     * @param xs list of arguments to the phi, v is a choice amongst these
     * @param pp program point the statement was created at
     *
     * @return statement to be processed during pointer analysis
     */
    public PhiStatement phiToLocal(ReferenceVariable v, List<ReferenceVariable> xs, ProgramPoint pp) {
        assert v != null;
        assert xs != null;
        assert pp != null;

        PhiStatement s = new PhiStatement(v, xs, pp);
        assert map.put(new StatementKey(v), s) == null;
        return s;
    }

    /**
     * Create a points-to statement for a return instruction
     *
     * @param result Node for return result
     * @param returnSummary Node summarizing all return values for the method
     * @param pp program point the statement was created at
     * @param i return instruction
     * @return statement to be processed during pointer analysis
     */
    public ReturnStatement returnStatement(ReferenceVariable result, ReferenceVariable returnSummary, ProgramPoint pp,
                                           SSAReturnInstruction i) {
        assert result != null;
        assert returnSummary != null;
        assert i != null;
        assert pp != null;

        ReturnStatement s = new ReturnStatement(result, returnSummary, pp);
        assert map.put(new StatementKey(result, i), s) == null;
        return s;
    }

    /**
     * Points-to statement for a special method invocation.
     *
     * @param callerPP Method call site
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver Receiver of the call
     * @param actuals Actual arguments to the call
     * @param callerException Node in the caller representing the exceptions thrown by the callee
     * @param calleeSummary summary nodes for formals and exits of the callee
     * @return statement to be processed during pointer analysis
     */
    public SpecialCallStatement specialCall(CallSiteProgramPoint callerPP, IMethod callee, ReferenceVariable result,
                                            ReferenceVariable receiver, List<ReferenceVariable> actuals,
                                            ReferenceVariable callerException, MethodSummaryNodes calleeSummary) {
        assert callee != null;
        assert callerPP != null;
        assert receiver != null;
        assert actuals != null;
        assert callerException != null;
        assert calleeSummary != null;

        SpecialCallStatement s = new SpecialCallStatement(callerPP,
                                                          callee,
                                                          result,
                                                          receiver,
                                                          actuals,
                                                          callerException,
                                                          calleeSummary);
        assert map.put(new StatementKey(callerPP, callee, result, receiver, actuals, callerException), s) == null;
        return s;
    }

    /**
     * Points-to statement for a special method invocation.
     *
     * @param callerPP Method call site
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param actuals Actual arguments to the call
     * @param callerException Node in the caller representing the exception thrown by the callee
     * @param calleeSummary summary nodes for formals and exits of the callee
     * @return statement to be processed during pointer analysis
     */
    public StaticCallStatement staticCall(CallSiteProgramPoint callerPP, IMethod callee, ReferenceVariable result,
                                          List<ReferenceVariable> actuals, ReferenceVariable callerException,
                                          MethodSummaryNodes calleeSummary) {
        assert callee != null;
        assert callerPP != null;
        assert actuals != null;
        assert callerException != null;
        assert calleeSummary != null;

        StaticCallStatement s = new StaticCallStatement(callerPP,
                                                        callee,
                                                        result,
                                                        actuals,
                                                        callerException,
                                                        calleeSummary);
        assert map.put(new StatementKey(callerPP, callee, result, null, actuals, callerException), s) == null;
        return s;
    }

    /**
     * Statement for an assignment from a local into a static field, local = ClassName.staticField
     *
     * @param local assignee
     * @param staticField the assigned value
     * @param pp program point the statement was created at
     * @return statement to be processed during pointer analysis
     */
    public StaticFieldToLocalStatement staticFieldToLocal(ReferenceVariable local, ReferenceVariable staticField,
                                                          ProgramPoint pp) {
        assert local != null;
        assert staticField != null;
        assert pp != null;

        StaticFieldToLocalStatement s = new StaticFieldToLocalStatement(local, staticField, pp);
        assert map.put(new StatementKey(local), s) == null;
        return s;
    }

    /**
     * Statement for an assignment into a the value field of a new string literal, string.value = rv
     *
     * @param string string literal
     * @param value field reference for the String.value
     * @param rv allocation of the value field
     * @param pp program point the statement was created at
     *
     * @return statement to be processed during pointer analysis
     */
    public LocalToFieldStatement stringLiteralValueToField(ReferenceVariable string, FieldReference value,
                                                           ReferenceVariable rv, ProgramPoint pp) {
        assert string != null;
        assert value != null;
        assert rv != null;
        assert pp != null;
        assert value.getName().toString().equals("value")
                && value.getDeclaringClass().equals(TypeReference.JavaLangString) : "This method should only be called for String.value for a string literal";
        LocalToFieldStatement s = new LocalToFieldStatement(string, value, rv, pp);
        assert map.put(new StatementKey(string, value), s) == null;
        return s;
    }

    /**
     * Points-to statement for a virtual method invocation.
     *
     * @param callerPP Method call site
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver Receiver of the call
     * @param actuals Actual arguments to the call
     * @param callerException Node representing the exception thrown by this call (if any)
     * @param rvFactory factory used to find callee summary nodes
     * @return statement to be processed during pointer analysis
     */
    public VirtualCallStatement virtualCall(CallSiteProgramPoint callerPP, MethodReference callee,
                                            ReferenceVariable result, ReferenceVariable receiver,
                                            List<ReferenceVariable> actuals, ReferenceVariable callerException,
                                            ReferenceVariableFactory rvFactory) {
        assert callee != null;
        assert callerPP != null;
        assert receiver != null;
        assert actuals != null;
        assert callerException != null;
        assert rvFactory != null;

        VirtualCallStatement s = new VirtualCallStatement(callerPP,
                                                          callee,
                                                          result,
                                                          receiver,
                                                          actuals,
                                                          callerException,
                                                          rvFactory);
        assert map.put(new StatementKey(callerPP, callee, result, receiver, actuals, callerException), s) == null;
        return s;
    }

    /**
     * Duplication checking map key. Two different PointsToStatement objects should never have the same StatementKey
     */
    private static class StatementKey {

        private final Object[] keys;

        public StatementKey(Object... keys) {
            this.keys = keys;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (this.getClass() != obj.getClass()) {
                return false;
            }
            StatementKey other = (StatementKey) obj;
            if (this.keys.length != other.keys.length) {
                return false;
            }
            for (int i = 0; i < this.keys.length; i++) {
                Object a = this.keys[i];
                Object b = other.keys[i];
                if (!a.equals(b)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.keys) ^ 8752;
        }
    }

}
