package analysis.pointer.statements;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.pointer.registrar.FlowSensitiveStringVariableFactory;
import analysis.pointer.registrar.MethodSummaryNodes;
import analysis.pointer.registrar.ReferenceVariableFactory;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.strings.StringVariable;

import com.ibm.wala.classLoader.CallSiteReference;
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
     * @return statement to be processed during pointer analysis
     */
    public ArrayToLocalStatement arrayToLocal(ReferenceVariable v, ReferenceVariable a, TypeReference baseType,
                                              IMethod m) {
        assert v != null;
        assert a != null;
        assert baseType != null;
        assert m != null;

        ArrayToLocalStatement s = new ArrayToLocalStatement(v, a, baseType, m);
        assert map.put(new StatementKey(v), s) == null;
        return s;
    }

    /**
     * Create a points-to statement for class initialization
     *
     * @param clinits class initialization methods that might need to be called in the order they need to be called
     *            (i.e. element j is a super class of element j+1)
     * @param i Instruction triggering the initialization
     * @return statement to be processed during pointer analysis
     */
    public ClassInitStatement classInit(List<IMethod> clinits, IMethod m, SSAInstruction i) {
        assert clinits != null;
        assert !clinits.isEmpty();
        assert m != null;
        assert i != null;

        ClassInitStatement s = new ClassInitStatement(clinits, m);
        // Could be duplicated in the same method, if we want a unique key use the instruction
        assert map.put(new StatementKey(clinits, i), s) == null : "Duplicate classinit " + clinits + " from " + i
                + " in " + m;
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
     * @param m method the statement was created for
     * @param isToMethodSummaryVariable true if the variable we are assigning into, <code>caught</code>, is the
     *            exception summary node for a method
     * @return statement to be processed during pointer analysis
     */
    public static ExceptionAssignmentStatement exceptionAssignment(ReferenceVariable thrown, ReferenceVariable caught,
                                                                   Set<IClass> notType, IMethod m,
                                                                   boolean isToMethodSummaryVariable) {
        assert thrown != null;
        assert caught != null;
        assert notType != null;
        assert m != null;

        ExceptionAssignmentStatement s = new ExceptionAssignmentStatement(thrown,
                                                                          caught,
                                                                          notType,
                                                                          m,
                                                                          isToMethodSummaryVariable);
        return s;

    }

    /**
     * Points-to statement for a field access assigned to a local, l = o.f
     *
     * @param l local assigned into
     * @param o receiver of field access
     * @param f field accessed
     * @param m method the statement was created for
     * @return statement to be processed during pointer analysis
     */
    public FieldToLocalStatement fieldToLocal(ReferenceVariable l, ReferenceVariable o, FieldReference f, IMethod m) {
        assert l != null;
        assert o != null;
        assert f != null;
        assert m != null;

        FieldToLocalStatement s = new FieldToLocalStatement(l, o, f, m);
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
     * @param m method the statement was created for
     * @return statement to be processed during pointer analysis
     */
    public LocalToArrayStatement localToArrayContents(ReferenceVariable array, ReferenceVariable local,
                                                      TypeReference baseType, IMethod m, SSAArrayStoreInstruction i) {
        assert array != null;
        assert local != null;
        assert baseType != null;
        assert m != null;
        assert i != null;

        LocalToArrayStatement s = new LocalToArrayStatement(array, local, baseType, m);
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
     * @param m method the points-to statement came from
     * @return statement to be processed during pointer analysis
     */
    public LocalToFieldStatement localToField(ReferenceVariable o, FieldReference f, ReferenceVariable v, IMethod m,
                                              SSAPutInstruction i) {
        assert o != null;
        assert f != null;
        assert v != null;
        assert m != null;
        assert i != null;

        LocalToFieldStatement s = new LocalToFieldStatement(o, f, v, m);
        // Could be duplicated in the same method, if we want a unique key use the instruction
        assert map.put(new StatementKey(o, f, i), s) == null;
        return s;
    }

    /**
     * Statement for a local assignment, left = right. No filtering will be performed based on type.
     *
     * @param left assignee
     * @param right the assigned value
     * @param m method the points-to statement came from
     * @return statement to be processed during pointer analysis
     */
    public LocalToLocalStatement localToLocal(ReferenceVariable left, ReferenceVariable right, IMethod m,
                                              boolean rightIsMethodSummary) {
        assert left != null;
        assert right != null;
        assert m != null;

        LocalToLocalStatement s = new LocalToLocalStatement(left, right, m, false, rightIsMethodSummary);
        assert map.put(new StatementKey(left), s) == null;
        return s;
    }

    /**
     * Statement for a local assignment, left = right. Filtering will be performed based on type.
     *
     * @param left assignee
     * @param right the assigned value
     * @param m method the points-to statement came from
     * @return statement to be processed during pointer analysis
     */
    public LocalToLocalStatement localToLocalFiltered(ReferenceVariable left, ReferenceVariable right, IMethod m) {
        assert left != null;
        assert right != null;
        assert m != null;

        LocalToLocalStatement s = new LocalToLocalStatement(left, right, m, true, false);
        assert map.put(new StatementKey(left), s) == null;
        return s;
    }

    /**
     * Statement for an assignment from a local into a static field, ClassName.staticField = local
     *
     * @param staticField the assigned value
     * @param local assignee
     * @param m method the points-to statement came from
     * @param i Instruction that generated this points-to statement
     * @return statement to be processed during pointer analysis
     */
    public LocalToStaticFieldStatement localToStaticField(ReferenceVariable staticField, ReferenceVariable local,
                                                          IMethod m, SSAPutInstruction i) {
        assert staticField != null;
        assert local != null;
        assert m != null;
        assert i != null;

        LocalToStaticFieldStatement s = new LocalToStaticFieldStatement(staticField, local, m);
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
     * @param m Method the points-to statement came from
     */
    public LocalToArrayStatement multidimensionalArrayContents(ReferenceVariable outerArray,
                                                               ReferenceVariable innerArray, IMethod m) {
        assert outerArray != null;
        assert innerArray != null;
        assert m != null;

        LocalToArrayStatement s = new LocalToArrayStatement(outerArray, innerArray, innerArray.getExpectedType(), m);
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
     * @param m method containing the instruction throwing the exception
     * @return a statement representing the allocation of a JVM generated exception to a local variable
     */
    public NewStatement newForGeneratedException(ReferenceVariable exceptionAssignee, IClass exceptionClass, IMethod m) {
        return newForGeneratedObject(exceptionAssignee, exceptionClass, m, PrettyPrinter.typeString(exceptionClass));
    }

    /**
     * Get a points-to statement representing allocation generated for a native method with no signature
     *
     * @param summary Reference variable for the method summary node assigned to after being created
     * @param allocatedClass Class being allocated
     * @param m native method
     * @return a statement representing the allocation for a native method with no signature
     */
    public NewStatement newForNative(ReferenceVariable summary, IClass allocatedClass, IMethod m) {
        assert m.isNative();
        return newForGeneratedObject(summary,
                                     allocatedClass,
                                     m,
                                     PrettyPrinter.getCanonical("NATIVE-" + PrettyPrinter.typeString(allocatedClass)));
    }

    /**
     * Get a points-to statement representing allocation generated for a native method with no signature
     *
     * @param summary Reference variable for the method summary node assigned to after being created
     * @param allocatedClass Class being allocated
     * @param m method
     * @return a statement representing the allocation for a native method with no signature
     */
    public NewStatement newForGeneratedObject(ReferenceVariable v, IClass allocatedClass, IMethod m, String description) {
        assert v != null;
        assert allocatedClass != null;
        assert m != null;

        NewStatement s = new NewStatement(description, v, allocatedClass, m, false);
        assert map.put(new StatementKey(v), s) == null;
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of an inner array of a multidimensional array
     *
     * @param innerArray Reference variable for the local variable the array is assigned to after being created
     * @param innerArrayClass Class for the array
     * @param m method containing the instruction creating the multidimensional array
     * @return a statement representing the allocation of the inner array of a multidimensional array
     */
    public NewStatement newForInnerArray(ReferenceVariable innerArray, IClass innerArrayClass, IMethod m) {
        String name = PrettyPrinter.getCanonical("GENERATED-" + PrettyPrinter.typeString(innerArrayClass));
        return newForGeneratedObject(innerArray, innerArrayClass, m, name);
    }

    /**
     * Points-to graph statement for a "new" instruction, e.g. result = new Object()
     *
     * @param result Points-to graph node for the assignee of the new
     * @param newClass Class being created
     * @param m method the points-to statement came from
     * @param pc The program counter where the allocation occured
     * @param lineNumber line number from source code if one can be found, -1 otherwise
     * @return statement to be processed during pointer analysis
     */
    public NewStatement newForNormalAlloc(ReferenceVariable result, IClass newClass, IMethod m, int pc, int lineNumber) {
        assert result != null;
        assert newClass != null;
        assert m != null;

        NewStatement s = new NewStatement(result, newClass, m, pc, lineNumber);
        assert map.put(new StatementKey(result), s) == null;
        return s;
    }

    /**
     * Get a points-to statement representing the allocation of the value field of a string
     *
     * @param local Reference variable for the local variable for the string at the allocation site
     * @param m method containing the String literal
     * @return a statement representing the allocation of a new string literal's value field
     */
    public NewStatement newForStringField(ReferenceVariable local, IMethod m) {
        return newForGeneratedObject(local, AnalysisUtil.getStringValueClass(), m, STRING_LIT_FIELD_DESC);
    }

    /**
     * Get a points-to statement representing the allocation of a String literal
     *
     * @param literalValue String value of the new string literal
     * @param local Reference variable for the local variable for the string at the allocation site
     * @param m method containing the String literal
     * @return a statement representing the allocation of a new string literal
     */
    public NewStatement newForStringLiteral(String literalValue, ReferenceVariable local, IMethod m) {
        assert local != null;
        assert m != null;

        NewStatement s = new NewStatement(literalValue, local, AnalysisUtil.getStringClass(), m, true);
        assert map.put(new StatementKey(local, literalValue), s) == null;
        return s;
    }

    /**
     * Points-to graph statement for a phi, v = phi(xs[1], xs[2], ...)
     *
     * @param v value assigned into
     * @param xs list of arguments to the phi, v is a choice amongst these
     * @param m method the points-to statement came from
     * @return statement to be processed during pointer analysis
     */
    public PhiStatement phiToLocal(ReferenceVariable v, List<ReferenceVariable> xs, IMethod m) {
        assert v != null;
        assert xs != null;
        assert m != null;

        PhiStatement s = new PhiStatement(v, xs, m);
        assert map.put(new StatementKey(v), s) == null;
        return s;
    }

    /**
     * Create a points-to statement for a return instruction
     *
     * @param result Node for return result
     * @param returnSummary Node summarizing all return values for the method
     * @param m method the points-to statement came from
     * @param i return instruction
     * @return statement to be processed during pointer analysis
     */
    public ReturnStatement returnStatement(ReferenceVariable result, ReferenceVariable returnSummary, IMethod m,
                                           SSAReturnInstruction i) {
        assert result != null;
        assert returnSummary != null;
        assert i != null;
        assert m != null;

        ReturnStatement s = new ReturnStatement(result, returnSummary, m);
        assert map.put(new StatementKey(result, i), s) == null;
        return s;
    }

    /**
     * Points-to statement for a special method invocation.
     *
     * @param callSite Method call site
     * @param caller caller method
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver Receiver of the call
     * @param actuals Actual arguments to the call
     * @param callerException Node in the caller representing the exceptions thrown by the callee
     * @param calleeSummary summary nodes for formals and exits of the callee
     * @return statement to be processed during pointer analysis
     */
    public SpecialCallStatement specialCall(CallSiteReference callSite, IMethod caller, IMethod callee,
                                            ReferenceVariable result, ReferenceVariable receiver,
                                            List<ReferenceVariable> actuals, ReferenceVariable callerException,
                                            MethodSummaryNodes calleeSummary) {
        assert callSite != null;
        assert callee != null;
        assert caller != null;
        assert receiver != null;
        assert actuals != null;
        assert callerException != null;
        assert calleeSummary != null;

        SpecialCallStatement s = new SpecialCallStatement(callSite,
                                                          caller,
                                                          callee,
                                                          result,
                                                          receiver,
                                                          actuals,
                                                          callerException,
                                                          calleeSummary);
        assert map.put(new StatementKey(callSite, caller, callee, result, receiver, actuals, callerException), s) == null;
        return s;
    }

    /**
     * Points-to statement for a special method invocation.
     *
     * @param callSite Method call site
     * @param caller caller method
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param actuals Actual arguments to the call
     * @param callerException Node in the caller representing the exception thrown by the callee
     * @param calleeSummary summary nodes for formals and exits of the callee
     * @return statement to be processed during pointer analysis
     */
    public StaticCallStatement staticCall(CallSiteReference callSite, IMethod caller, IMethod callee,
                                          ReferenceVariable result, List<ReferenceVariable> actuals,
                                          ReferenceVariable callerException, MethodSummaryNodes calleeSummary) {
        assert callSite != null;
        assert callee != null;
        assert caller != null;
        assert actuals != null;
        assert callerException != null;
        assert calleeSummary != null;

        StaticCallStatement s = new StaticCallStatement(callSite,
                                                        caller,
                                                        callee,
                                                        result,
                                                        actuals,
                                                        callerException,
                                                        calleeSummary);
        assert map.put(new StatementKey(callSite, caller, callee, result, null, actuals, callerException), s) == null;
        return s;
    }

    /**
     * Statement for an assignment from a local into a static field, local = ClassName.staticField
     *
     * @param local assignee
     * @param staticField the assigned value
     * @param m method the points-to statement came from
     * @return statement to be processed during pointer analysis
     */
    public StaticFieldToLocalStatement staticFieldToLocal(ReferenceVariable local, ReferenceVariable staticField,
                                                          IMethod m) {
        assert local != null;
        assert staticField != null;
        assert m != null;

        StaticFieldToLocalStatement s = new StaticFieldToLocalStatement(local, staticField, m);
        assert map.put(new StatementKey(local), s) == null;
        return s;
    }

    /**
     * Statement for an assignment into a the value field of a new string literal, string.value = rv
     *
     * @param string string literal
     * @param value field reference for the String.value
     * @param rv allocation of the value field
     * @param m method the points-to statement came from
     *
     * @return statement to be processed during pointer analysis
     */
    public LocalToFieldStatement stringLiteralValueToField(ReferenceVariable string, FieldReference value,
                                                           ReferenceVariable rv, IMethod m) {
        assert string != null;
        assert value != null;
        assert rv != null;
        assert m != null;
        assert value.getName().toString().equals("value")
                && value.getDeclaringClass().equals(TypeReference.JavaLangString) : "This method should only be called for String.value for a string literal";
        LocalToFieldStatement s = new LocalToFieldStatement(string, value, rv, m);
        assert map.put(new StatementKey(string, value), s) == null;
        return s;
    }

    /**
     * Points-to statement for a virtual method invocation.
     *
     * @param callSite Method call site
     * @param caller caller method
     * @param callee Method being called
     * @param result Node for the assignee if any (i.e. v in v = foo()), null if there is none or if it is a primitive
     * @param receiver Receiver of the call
     * @param actuals Actual arguments to the call
     * @param callerException Node representing the exception thrown by this call (if any)
     * @param rvFactory factory used to find callee summary nodes
     * @return statement to be processed during pointer analysis
     */
    public PointsToStatement virtualCall(CallSiteReference callSite, IMethod caller, MethodReference callee,
                                         ReferenceVariable result, ReferenceVariable receiver,
                                         List<ReferenceVariable> actuals, ReferenceVariable callerException,
                                         ReferenceVariableFactory rvFactory) {
        assert callSite != null;
        assert callee != null;
        assert caller != null;
        assert receiver != null;
        assert actuals != null;
        assert callerException != null;
        assert rvFactory != null;

        PointsToStatement s;

        if (ClassMethodInvocationStatement.isReflectiveMethod(callSite.getDeclaredTarget())) {
            s = new ClassMethodInvocationStatement(callSite, caller, result, receiver, actuals, callerException);
        }
        else {
            s = new VirtualCallStatement(callSite,
                                         caller,
                                         callee,
                                         result,
                                         receiver,
                                         actuals,
                                         callerException,
                                         rvFactory);
        }
        assert map.put(new StatementKey(callSite, caller, callee, result, receiver, actuals, callerException), s) == null;
        return s;
    }

    public StringStatement localFromFormalString(StringVariable param, ReferenceVariable formal, IMethod method,
                                                 boolean rightIsMethodSummary) {
        assert param != null;
        assert formal != null;
        assert method != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(param, formal, method));

        return new LocalFromFormalStringStatement(param, formal, method);
    }

    public StringStatement fieldToLocalString(StringVariable svv, ReferenceVariable o, FieldReference f, IMethod method) {
        assert svv != null;
        assert o != null;
        assert f != null;
        assert method != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(svv, o, f, method, null, null, null));

        return new FieldToLocalStringStatement(svv, o, f, method);
    }

    public StringStatement staticFieldToLocalString(StringVariable svv, StringVariable svf, IMethod method) {
        assert svv != null;
        assert svf != null;
        assert method != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(svv, svf, method));

        return new StaticFieldToLocalStringStatement(svv, svf, method);
    }

    public StringStatement newString(StringVariable result, IMethod method) {
        assert result != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(result, method));

        return new NewStringStatement(result, method);
    }

    public StringStatement stringMethodCall(CallSiteReference callSite, IMethod method, MethodReference declaredTarget,
                                            StringVariable svresult, StringVariable svreceiverUse,
                                            StringVariable svreceiverDef, List<StringVariable> svarguments,
                                            FlowSensitiveStringVariableFactory stringVariableFactory) {
        assert callSite != null;
        assert method != null;
        assert declaredTarget != null;
        assert svresult != null;
        assert svreceiverUse != null;
        assert svreceiverDef != null;
        assert svarguments != null;
        for (StringVariable svargument : svarguments) {
            assert svargument != null;
        }
        assert stringVariableFactory != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(callSite,
                                                                  method,
                                                                  declaredTarget,
                                                                  svresult,
                                                                  svreceiverUse,
                                                                  svreceiverDef,
                                                                  svarguments));

        return new StringMethodCall(callSite,
                                    method,
                                    declaredTarget,
                                    svresult,
                                    svreceiverUse,
                                    svreceiverDef,
                                    svarguments,
                                    stringVariableFactory);
    }

    public StringStatement phiToLocalString(StringVariable svassignee, List<StringVariable> svuses, IMethod method,
                                            PrettyPrinter pp) {
        assert svassignee != null;
        assert svuses != null;
        assert method != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(svassignee, svuses, method));

        return new PhiToLocalStringStatement(svassignee, svuses, method);
    }

    public StringStatement localToStaticFieldString(StringVariable svf, StringVariable svv, IMethod method) {
        assert svf != null;
        assert svv != null;
        assert method != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(svf, svv, method));

        return new LocalToStaticFieldStringStatement(svf, svv, method);
    }

    public StringStatement returnString(StringVariable svv, ReferenceVariable summary, IMethod method, SSAInstruction i) {
        assert svv != null;
        assert summary != null;
        assert method != null;
        assert i != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(svv, summary, method, i));

        return new ReturnStringStatement(svv, summary, method);
    }

    public StringStatement stringInit(CallSiteReference callSite, IMethod method, StringVariable sv) {
        assert callSite != null;
        assert method != null;
        assert sv != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(callSite, method, sv));

        return new StringInitStatement(callSite, method, sv);
    }


    public StringStatement stringPhiNode(IMethod method, StringVariable sv, Set<StringVariable> dependentSVs) {
        assert method != null;
        assert sv != null;
        assert dependentSVs != null;
        for (StringVariable dsv : dependentSVs) {
            assert dsv != null;
        }

        assert stringStatementNeverCreatedBefore(new StatementKey(sv, dependentSVs));

        return new StringPhiNode(method, sv, dependentSVs);
    }

    public PointsToStatement forNameCall(CallSiteReference callSite, IMethod caller, MethodReference callee,
                                         ReferenceVariable result, List<StringVariable> actuals) {
        assert callSite != null;
        assert callee != null;
        assert caller != null;
        assert actuals != null;

        assert stringStatementNeverCreatedBefore(new StatementKey(callSite,
                                                                  caller,
                                                                  callee,
                                                                  result,
                                                                  actuals,
                                                                  null));

        return new ForNameCallStatement(callSite, caller, callee, result, actuals);

    }

    /* void is the one element type, it only contains null */
    private final Map<StatementKey, Void> stringStatementMap = new HashMap<>();

    private boolean stringStatementNeverCreatedBefore(StatementKey statementKey) {
        if (stringStatementMap.containsKey(statementKey)) {
            System.err.println("I already saw: " + statementKey);
            return false;
        }
        else {
            stringStatementMap.put(statementKey, null);
            return true;
        }
    }

    /**
     * Duplication checking map key. Two different PointsToStatement objects should never have the same StatementKey
     */
    private static class StatementKey {

        private final Object key1;
        private final Object key2;
        private final Object key3;
        private final Object key4;
        private final Object key5;
        private final Object key6;
        private final Object key7;

        public StatementKey(Object key1) {
            this.key1 = key1;
            this.key2 = null;
            this.key3 = null;
            this.key4 = null;
            this.key5 = null;
            this.key6 = null;
            this.key7 = null;
        }

        public StatementKey(Object key1, Object key2) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = null;
            this.key4 = null;
            this.key5 = null;
            this.key6 = null;
            this.key7 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = null;
            this.key5 = null;
            this.key6 = null;
            this.key7 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5, Object key6, Object key7) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = key6;
            this.key7 = key7;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = null;
            this.key6 = null;
            this.key7 = null;
        }

        public StatementKey(Object key1, Object key2, Object key3, Object key4, Object key5, Object key6) {
            this.key1 = key1;
            this.key2 = key2;
            this.key3 = key3;
            this.key4 = key4;
            this.key5 = key5;
            this.key6 = key6;
            this.key7 = null;
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
            if (this.key1 == null) {
                if (other.key1 != null) {
                    return false;
                }
            }
            else if (!this.key1.equals(other.key1)) {
                return false;
            }
            if (this.key2 == null) {
                if (other.key2 != null) {
                    return false;
                }
            }
            else if (!this.key2.equals(other.key2)) {
                return false;
            }
            if (this.key3 == null) {
                if (other.key3 != null) {
                    return false;
                }
            }
            else if (!this.key3.equals(other.key3)) {
                return false;
            }
            if (this.key4 == null) {
                if (other.key4 != null) {
                    return false;
                }
            }
            else if (!this.key4.equals(other.key4)) {
                return false;
            }
            if (this.key5 == null) {
                if (other.key5 != null) {
                    return false;
                }
            }
            else if (!this.key5.equals(other.key5)) {
                return false;
            }
            if (this.key6 == null) {
                if (other.key6 != null) {
                    return false;
                }
            }
            else if (!this.key6.equals(other.key6)) {
                return false;
            }
            if (this.key7 == null) {
                if (other.key7 != null) {
                    return false;
                }
            }
            else if (!this.key7.equals(other.key7)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (this.key1 == null ? 0 : this.key1.hashCode());
            result = prime * result + (this.key2 == null ? 0 : this.key2.hashCode());
            result = prime * result + (this.key3 == null ? 0 : this.key3.hashCode());
            result = prime * result + (this.key4 == null ? 0 : this.key4.hashCode());
            result = prime * result + (this.key5 == null ? 0 : this.key5.hashCode());
            result = prime * result + (this.key6 == null ? 0 : this.key6.hashCode());
            result = prime * result + (this.key7 == null ? 0 : this.key7.hashCode());
            return result;
        }

        @Override
        public String toString() {
            return "StatementKey [key1=" + key1 + ", key2=" + key2 + ", key3=" + key3 + ", key4=" + key4 + ", key5="
                    + key5 + ", key6=" + key6 + ", key7=" + key7 + "]";
        }
    }
}
