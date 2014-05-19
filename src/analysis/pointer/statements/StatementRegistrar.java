package analysis.pointer.statements;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.WalaAnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.statements.ReferenceVariableFactory.ReferenceVariable;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.TypeReference;

/**
 * This class manages the registration of new points-to graph statements, which are then processed by the pointer
 * analysis
 */
public class StatementRegistrar {

    /**
     * Set of all points-to statements
     */
    private final Set<PointsToStatement> statements = new LinkedHashSet<>();

    /**
     * Map from method signature to nodes representing formals and returns
     */
    private final Map<IMethod, MethodSummaryNodes> methods = new LinkedHashMap<>();
    /**
     * Entry point for the code being analyzed
     */
    private IMethod entryPoint;
    /**
     * Map from method to the points-to statements generated from instructions in that method
     */
    private final Map<IMethod, Set<PointsToStatement>> statementsForMethod = new HashMap<>();

    private final Set<ReferenceVariable> handledStringLit = new HashSet<>();

    /**
     * x = v[j], load from an array
     * 
     * @param i
     *            instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerArrayLoad(SSAArrayLoadInstruction i, IR ir) {
        TypeReference baseType = TypeRepository.getType(i.getArrayRef(), ir).getArrayElementType();
        if (baseType.isPrimitiveType()) {
            // Assigning from a primitive array so result is not a pointer
            return;
        }
        ReferenceVariable array = ReferenceVariableFactory.getOrCreateLocal(i.getArrayRef(), ir);
        ReferenceVariable local = ReferenceVariableFactory.getOrCreateLocal(i.getDef(), ir);
        addStatement(new ArrayToLocalStatement(local, array, baseType, ir, i));
    }

    /**
     * v[j] = x, store into an array
     * 
     * @param i
     *            array store instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerArrayStore(SSAArrayStoreInstruction i, IR ir) {
        TypeReference t = i.getElementType();
        if (t.isPrimitiveType() || TypeRepository.getType(i.getValue(), ir) == TypeReference.Null) {
            // Assigning into a primitive array so value is not a pointer, or
            // assigning null
            return;
        }
        ReferenceVariable array = ReferenceVariableFactory.getOrCreateLocal(i.getArrayRef(), ir);
        ReferenceVariable value = ReferenceVariableFactory.getOrCreateLocal(i.getValue(), ir);
        addStatement(new LocalToArrayStatement(array, value, i.getElementType(), ir, i));
    }

    /**
     * v = (Type) x
     * 
     * @param i
     *            cast instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerCheckCast(SSACheckCastInstruction i, IR ir) {
        if (TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // the cast value is null so no effect on pointer analysis
            return;
        }

        // This has the same effect as a copy, v = x (except for the exception
        // it could throw)
        ReferenceVariable result = ReferenceVariableFactory.getOrCreateLocal(i.getResult(), ir);
        ReferenceVariable checkedVal = ReferenceVariableFactory.getOrCreateLocal(i.getVal(), ir);
        addStatement(new LocalToLocalStatement(result, checkedVal, ir, i));
    }

    /**
     * v = o.f
     * 
     * @param i
     *            instruction getting the field
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerGetField(SSAGetInstruction i, IR ir) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = ReferenceVariableFactory.getOrCreateLocal(i.getDef(), ir);
        ReferenceVariable receiver = ReferenceVariableFactory.getOrCreateLocal(i.getRef(), ir);
        addStatement(new FieldToLocalStatment(i.getDeclaredField(), receiver, assignee, ir, i));
    }

    /**
     * v = ClassName.f
     * 
     * @param i
     *            instruction getting the field
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerGetStatic(SSAGetInstruction i, IR ir, IClassHierarchy cha) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = ReferenceVariableFactory.getOrCreateLocal(i.getDef(), ir);
        ReferenceVariable field = ReferenceVariableFactory.getOrCreateStaticField(i.getDeclaredField(), cha);
        addStatement(new StaticFieldToLocalStatement(assignee, field, ir, i));
    }

    /**
     * Handle an assignment into a field, o.f = v
     * 
     * @param i
     *            instruction for the assignment
     * @param ir
     *            code for the method containing the instruction
     */
    protected void registerPutField(SSAPutInstruction i, IR ir) {
        if (i.getDeclaredFieldType().isPrimitiveType() || TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        FieldReference f = i.getDeclaredField();
        ReferenceVariable assignedValue = ReferenceVariableFactory.getOrCreateLocal(i.getVal(), ir);
        ReferenceVariable receiver = ReferenceVariableFactory.getOrCreateLocal(i.getRef(), ir);

        addStatement(new LocalToFieldStatement(f, receiver, assignedValue, ir, i));
    }

    /**
     * Handle an assignment into a static field, ClassName.f = v
     * 
     * @param i
     *            instruction for the assignment
     * @param ir
     *            code for the method containing the instruction
     */
    protected void registerPutStatic(SSAPutInstruction i, IR ir, IClassHierarchy cha) {
        if (i.getDeclaredFieldType().isPrimitiveType() || TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        FieldReference f = i.getDeclaredField();
        ReferenceVariable assignedValue = ReferenceVariableFactory.getOrCreateLocal(i.getVal(), ir);
        ReferenceVariable fieldNode = ReferenceVariableFactory.getOrCreateStaticField(f, cha);
        addStatement(new LocalToStaticFieldStatement(fieldNode, assignedValue, ir, i));

    }

    /**
     * Handle a virtual, static, special, or interface invocation
     * 
     * @param i
     *            invoke instruction
     * @param ir
     *            code for the method containing the instruction (the caller)
     * @param util
     *            WALA utilities, like the class hierarchy, and IR repository
     */
    protected void registerInvoke(SSAInvokeInstruction i, IR ir, WalaAnalysisUtil util) {
        assert (i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1);

        ReferenceVariable resultNode = null;
        if (i.getNumberOfReturnValues() > 0) {
            TypeReference returnType = TypeRepository.getType(i.getReturnValue(0), ir);
            if (!returnType.isPrimitiveType()) {
                resultNode = ReferenceVariableFactory.getOrCreateLocal(i.getReturnValue(0), ir);
            }
        }

        List<ReferenceVariable> actuals = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            if (TypeRepository.getType(i.getUse(j), ir).isPrimitiveType()) {
                actuals.add(null);
            } else {
                actuals.add(ReferenceVariableFactory.getOrCreateLocal(i.getUse(j), ir));
            }
        }

        ReferenceVariable exceptionNode = ReferenceVariableFactory.getOrCreateLocal(i.getException(), ir);

        // Get the receiver if it is not a static call
        // TODO the second condition is used because sometimes the receiver is a null constant
        // This is usually due to something like <code>o = null; if (o != null) { o.foo() }</code>,
        // note that the o.foo() is dead code
        ReferenceVariable receiver = null;
        if (!i.isStatic() && !ir.getSymbolTable().isNullConstant(i.getReceiver())) {
            receiver = ReferenceVariableFactory.getOrCreateLocal(i.getReceiver(), ir);
        }

        Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, util);
        if (resolvedMethods.isEmpty()) {
            if (StatementRegistrationPass.VERBOSE >= 1) {
                System.err.println("No resolved methods for " + PrettyPrinter.instructionString(i, ir) + " method: "
                                                + PrettyPrinter.methodString(i.getDeclaredTarget()) + " caller: "
                                                + PrettyPrinter.methodString(ir.getMethod()));
            }
            return;
        }

        if (i.isStatic()) {
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            addStatement(new StaticCallStatement(i.getCallSite(), resolvedMethod, actuals, resultNode, exceptionNode,
                                            ir, i, util));
        } else if (i.isSpecial()) {
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            addStatement(new SpecialCallStatement(i.getCallSite(), resolvedMethod, receiver, actuals, resultNode,
                                            exceptionNode, ir, i, util));
        } else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                                        || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            addStatement(new VirtualCallStatement(i.getCallSite(), i.getDeclaredTarget(), receiver, actuals,
                                            resultNode, exceptionNode, util.getClassHierarchy(), ir, i, util));
        } else {
            throw new UnsupportedOperationException("Unhandled invocation code: " + i.getInvocationCode() + " for "
                                            + PrettyPrinter.methodString(i.getDeclaredTarget()));
        }
    }

    /**
     * Register a new array allocation, note that this is only the allocation not the initialization if there is any.
     * 
     * @param i
     *            new instruction
     * @param ir
     *            code for the method containing the instruction
     * @param cha
     *            WALA class hierarchy
     */
    protected void registerNewArray(SSANewInstruction i, IR ir, IClassHierarchy cha) {
        // all "new" instructions are assigned to a local
        ReferenceVariable result = ReferenceVariableFactory.getOrCreateLocal(i.getDef(), ir);

        IClass klass = cha.lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        addStatement(new NewStatement(result, klass, ir, i));

        // Handle arrays with multiple dimensions
        ReferenceVariable array = result;
        for (int dim = 1; dim < i.getNumberOfUses(); dim++) {
            // Create local for array contents
            ReferenceVariable contents = ReferenceVariableFactory.getOrCreateArrayContents(dim, array.getExpectedType()
                                            .getArrayElementType(), i, ir);
            // Add an allocation for the contents
            IClass arrayklass = cha.lookupClass(contents.getExpectedType());
            assert arrayklass != null : "No class found for "
                                            + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
            addStatement(new NewStatement(contents, arrayklass, ir, i));

            // Add field assign from the field of the outer array to the array
            // contents
            addStatement(new LocalToFieldStatement(array, contents, ir, i));

            // The array on the next iteration will be contents of this one
            array = contents;
        }
    }

    /**
     * Handle an allocation of the form: "new Foo". Note that this is only the allocation not the constructor call.
     * 
     * @param i
     *            new instruction
     * @param ir
     *            code for the method containing the instruction
     * @param classHierarchy
     *            WALA class hierarchy
     */
    protected void registerNewObject(SSANewInstruction i, IR ir, IClassHierarchy cha) {
        // all "new" instructions are assigned to a local
        ReferenceVariable result = ReferenceVariableFactory.getOrCreateLocal(i.getDef(), ir);

        IClass klass = cha.lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        addStatement(new NewStatement(result, klass, ir, i));
    }

    /**
     * Handle an SSA phi instruction, x = phi(x_1, x_2, ...)
     * 
     * @param i
     *            phi instruction
     * @param ir
     *            code for the method containing the instruction
     */
    protected void registerPhiAssignment(SSAPhiInstruction i, IR ir) {
        TypeReference type = TypeRepository.getType(i.getDef(), ir);
        if (type.isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = ReferenceVariableFactory.getOrCreateLocal(i.getDef(), ir);
        List<ReferenceVariable> uses = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int arg = i.getUse(j);
            if (TypeRepository.getType(arg, ir) != TypeReference.Null) {
                if (TypeRepository.getType(arg, ir).isPrimitiveType()) {
                    System.err.println("PRIMITIVE: " + PrettyPrinter.valString(arg, ir) + ":"
                                                    + PrettyPrinter.typeString(TypeRepository.getType(arg, ir))
                                                    + " for phi with return type " + PrettyPrinter.typeString(type));
                    System.err.println("\t" + PrettyPrinter.instructionString(i, ir) + " in "
                                                    + PrettyPrinter.methodString(ir.getMethod()));
                    CFGWriter.writeToFile(ir);
                    continue;
                }
                ReferenceVariable use = ReferenceVariableFactory.getOrCreateLocal(arg, ir);
                uses.add(use);
            }
        }
        if (uses.isEmpty()) {
            // All entries to the phi are null literals, no effect on pointer
            // analysis
            return;
        }
        addStatement(new PhiStatement(assignee, uses, ir, i));
    }

    /**
     * Load-metadata is used for reflective operations
     * 
     * @param i
     *            instruction
     * @param ir
     *            code for method containing instruction
     */
    @SuppressWarnings("unused")
    protected void registerReflection(SSALoadMetadataInstruction i, IR ir) {
        // TODO statement registrar not handling reflection yet
    }

    /**
     * Handle a return instruction "return v"
     * 
     * @param i
     *            instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerReturn(SSAReturnInstruction i, IR ir) {
        if (i.returnsPrimitiveType() || i.returnsVoid()
                                        || TypeRepository.getType(i.getResult(), ir) == TypeReference.Null) {
            // no pointers here
            return;
        }
        ReferenceVariable result = ReferenceVariableFactory.getOrCreateLocal(i.getResult(), ir);
        ReferenceVariable summary = methods.get(ir.getMethod()).getReturnNode();
        addStatement(new ReturnStatement(result, summary, ir, i));
    }

    /**
     * Handle an exception throw
     * 
     * @param i
     *            instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerThrow(SSAThrowInstruction i, IR ir, IClassHierarchy cha) {
        ReferenceVariable exception = ReferenceVariableFactory.getOrCreateLocal(i.getException(), ir);
        addAssignmentForThrownException(i, ir, exception, cha);
    }

    /**
     * Register a new method
     * 
     * @param method
     *            new method
     * @param summary
     *            nodes for formals and returns
     */
    protected void recordMethod(IMethod method, MethodSummaryNodes summary) {
        methods.put(method, summary);
    }

    /**
     * Get the formal and return nodes for the given method
     * 
     * @param resolvedCallee
     *            signature of the method to get the summary for
     * @return summary nodes for the given method
     */
    protected MethodSummaryNodes getSummaryNodes(IMethod resolvedCallee) {
        MethodSummaryNodes msn = methods.get(resolvedCallee);
        assert (msn != null) : "Missing method summary " + resolvedCallee;
        return msn;
    }

    /**
     * Get all points-to statements
     * 
     * @return set of all statements
     */
    public Set<PointsToStatement> getAllStatements() {
        return new LinkedHashSet<>(statements);
    }

    /**
     * Set the entry point for the code being analyzed
     * 
     * @param entryPoint
     *            entry point for the analyzed code
     */
    protected void setEntryPoint(IMethod entryPoint) {
        this.entryPoint = entryPoint;
    }

    /**
     * Get all methods that should be analyzed in the initial empty context
     * 
     * @return set of methods
     */
    public Set<IMethod> getInitialContextMethods() {
        Set<IMethod> ret = new LinkedHashSet<>();
        ret.add(entryPoint);
        return ret;
    }

    /**
     * If this is a static or special call then we know statically what the target of the call is and can therefore
     * resolve the method statically. If it is virtual then we need to add statements for all possible run time method
     * resolutions but may only analyze some of these depending on what the pointer analysis gives for the receiver
     * type.
     * 
     * @param inv
     *            method invocation to resolve methods for
     * @param cha
     *            class hierarchy to use for method resolution
     * @return Set of methods the invocation could call
     */
    protected static Set<IMethod> resolveMethodsForInvocation(SSAInvokeInstruction inv, WalaAnalysisUtil util) {
        Set<IMethod> targets = null;
        if (inv.isStatic()) {
            IMethod resolvedMethod = util.getClassHierarchy().resolveMethod(inv.getDeclaredTarget());
            if (resolvedMethod != null) {
                targets = Collections.singleton(resolvedMethod);
            }
        } else if (inv.isSpecial()) {
            IMethod resolvedMethod = util.getClassHierarchy().resolveMethod(inv.getDeclaredTarget());
            if (resolvedMethod != null) {
                targets = Collections.singleton(resolvedMethod);
            }
        } else if (inv.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                                        || inv.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            targets = util.getClassHierarchy().getPossibleTargets(inv.getDeclaredTarget());
        } else {
            throw new UnsupportedOperationException("Unhandled invocation code: " + inv.getInvocationCode() + " for "
                                            + PrettyPrinter.methodString(inv.getDeclaredTarget()));
        }
        if (targets == null || targets.isEmpty()) {
            // XXX HACK These methods seem to be using non-existant TreeMap methods and fields
            // Let's hope they are never really called
            return Collections.emptySet();
        }
        return targets;
    }

    /**
     * Add a new statement to the registrar
     * 
     * @param s
     *            statement to add
     */
    private void addStatement(PointsToStatement s) {
        statements.add(s);
        IMethod m = s.getCode().getMethod();
        Set<PointsToStatement> ss = statementsForMethod.get(m);
        if (ss == null) {
            ss = new LinkedHashSet<>();
            statementsForMethod.put(m, ss);
        }
        ss.add(s);
        if (statements.size() % 1000000 == 0) {
            System.err.println(statements.size() + " statements");
        }
    }

    /**
     * Add a new statement to the registrar
     * 
     * @param s
     *            statement to add
     * @param nativeMethod
     *            method we are adding the statement for
     */
    private void addStatementForNative(PointsToStatement s, IMethod nativeMethod) {
        statements.add(s);
        Set<PointsToStatement> ss = statementsForMethod.get(nativeMethod);
        if (ss == null) {
            ss = new LinkedHashSet<>();
            statementsForMethod.put(nativeMethod, ss);
        }
        ss.add(s);
        if (statements.size() % 1000000 == 0) {
            System.err.println(statements.size() + " statements");
        }
    }

    /**
     * Get all the statements for a particular method
     * 
     * @param m
     *            method to get the statements for
     * @return set of points-to statements for <code>m</code>
     */
    public Set<PointsToStatement> getStatementsForMethod(IMethod m) {
        Set<PointsToStatement> ret = statementsForMethod.get(m);
        if (ret != null) {
            return ret;

        }
        return Collections.emptySet();
    }

    /**
     * Add points-to statements for a String constant
     * 
     * @param valueNumber
     *            value number for the constant
     * @param ir
     *            IR containing the constant
     * @param i
     *            instruction using the string literal
     * @param stringClass
     *            representation of the class {@link java.lang.String}
     * @param stringValueClass
     *            representation of the byte array type
     */
    protected void addStatementsForStringLit(int valueNumber, IR ir, SSAInstruction i, IClass stringClass,
                                    IClass stringValueClass) {
        ReferenceVariable newStringLit = ReferenceVariableFactory.getOrCreateLocal(valueNumber, ir);
        if (handledStringLit.contains(newStringLit)) {
            // Already handled this allocation
            return;
        }
        handledStringLit.add(newStringLit);

        // v = new String
        addStatement(NewStatement.newStatementForStringLiteral(newStringLit, ir, i, stringClass));
        for (IField f : stringClass.getAllFields()) {
            if (f.getName().toString().equals("value")) {
                // This is the value field of the String
                ReferenceVariable stringValue = ReferenceVariableFactory.getOrCreateStringLitField(valueNumber, i, ir);
                addStatement(NewStatement.newStatementForStringField(stringValue, ir, i, stringValueClass));
                addStatement(new LocalToFieldStatement(f.getReference(), newStringLit, stringValue, ir, i));
            }
        }

    }

    protected final void addStatementsForGeneratedExceptions(SSAInstruction i, IR ir, IClassHierarchy cha) {
        for (TypeReference exType : PreciseExceptionResults.implicitExceptions(i)) {
            ReferenceVariable ex = ReferenceVariableFactory.getOrCreateImplicitExceptionNode(exType, i, ir);
            IClass exClass = cha.lookupClass(exType);
            assert exClass != null : "No class found for " + PrettyPrinter.typeString(exType);

            // System.err.println("GENERATED: " +
            // PrettyPrinter.parseType(exType) + " for "
            // + PrettyPrinter.instructionString(i, ir) + " in "
            // + PrettyPrinter.parseMethod(ir.getMethod()));
            addStatement(NewStatement.newStatementForGeneratedException(ex, exClass, ir, i));
            addAssignmentForThrownException(i, ir, ex, cha);
        }
    }

    /**
     * Add an assignment from the a thrown exception to any catch block or exit block exception that exception could
     * reach
     * 
     * @param i
     *            instruction throwing the exception
     * @param ir
     *            code containing the instruction that throws
     * @param thrown
     *            reference variable representing the value of the exception
     */
    private final void addAssignmentForThrownException(SSAInstruction i, IR ir, ReferenceVariable thrown,
                                    IClassHierarchy cha) {
        Set<IClass> notType = new LinkedHashSet<>();

        ISSABasicBlock bb = ir.getBasicBlockForInstruction(i);
        for (ISSABasicBlock succ : ir.getControlFlowGraph().getExceptionalSuccessors(bb)) {
            ReferenceVariable caught;
            if (succ.isCatchBlock()) {
                SSAGetCaughtExceptionInstruction catchIns = (SSAGetCaughtExceptionInstruction) succ.iterator().next();
                caught = ReferenceVariableFactory.getOrCreateLocal(catchIns.getException(), ir);
                Iterator<TypeReference> caughtTypes = bb.getCaughtExceptionTypes();
                while (caughtTypes.hasNext()) {
                    notType.add(cha.lookupClass(caughtTypes.next()));
                }
            } else {
                assert succ.isExitBlock() : "Exceptional successor should be catch block or exit block.";
                caught = getSummaryNodes(ir.getMethod()).getException();
            }
            addStatement(new ExceptionAssignmentStatement(thrown, caught, i, ir, notType));
        }
    }

    /**
     * Add points-to statements using a simple signature for native methods, where the return value and exception are
     * newly allocated objects.
     * <p>
     * this is unsound if the method modifies an input, returns an input, or has heap side effects
     * 
     * @param m
     *            native method
     * @param summaryNodes
     *            Method summary nodes for the
     * @param ir
     *            IR containing the invocation
     * @param nativeCall
     *            invocation of the native method
     * @param cha
     *            class hierarchy
     */
    protected void addStatementsForNative(IMethod m, MethodSummaryNodes summaryNodes, IR ir,
                                    SSAInvokeInstruction nativeCall, IClassHierarchy cha) {

        TypeReference returnType = m.getReturnType();
        if (!returnType.isPrimitiveType()) {
            // Add a new allocation for the return and an assignment into the
            // method summary return node
            IClass returnClass = cha.lookupClass(m.getReturnType());
            addStatementForNative(NewStatement.newStatementForNativeExit(summaryNodes.getReturnNode(), ir, nativeCall,
                                            returnClass, ExitType.NORMAL, m), m);
        }

        // Add a new allocation for the exception and an assignment into the
        // method summary exception node
        IClass exClass = cha.lookupClass(TypeReference.JavaLangThrowable);
        addStatementForNative(NewStatement.newStatementForNativeExit(summaryNodes.getException(), ir, nativeCall,
                                        exClass, ExitType.EXCEPTIONAL, m), m);

        // TODO for native could maybe add pointer from the formals to the
        // return if the types line up would then need to add local to local for
        // the formals

        // TODO for native how can we simulate a call to the <init> for the
        // newly allocated object?
    }
}
