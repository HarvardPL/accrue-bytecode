package analysis.pointer.registrar;

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
import util.ImplicitEx;
import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.dataflow.interprocedural.ExitType;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.AllocSiteNodeFactory;
import analysis.pointer.statements.AllocSiteNodeFactory.AllocSiteNode;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StatementFactory;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
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
    private final Set<PointsToStatement> statements;

    /**
     * Map from method signature to nodes representing formals and returns
     */
    private final Map<IMethod, MethodSummaryNodes> methods;
    /**
     * Entry point for the code being analyzed
     */
    private IMethod entryPoint;
    /**
     * Map from method to the points-to statements generated from instructions in that method
     */
    private final Map<IMethod, Set<PointsToStatement>> statementsForMethod;
    /**
     * String literals that new allocation sites have already been created for
     */
    private final Set<ReferenceVariable> handledStringLit = new HashSet<>();
    /**
     * Generated allocation nodes for native methods with no signature
     */
    private final Map<OrderedPair<IMethod, ExitType>, AllocSiteNode> nativeAllocs;
    /**
     * If true then only one allocation will be made for each generated exception type. This will reduce the size of the
     * points-to graph (and speed up the points-to analysis), but result in a loss of precision for such exceptions.
     */
    private static final boolean SINGLETON_GENERATED_EXCEPTIONS = true;

    public StatementRegistrar() {
        statements = new LinkedHashSet<>();
        methods = new LinkedHashMap<>();
        statementsForMethod = new HashMap<>();
        nativeAllocs = new HashMap<>();
    }

    /**
     * x = v[j], load from an array
     * 
     * @param i
     *            instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerArrayLoad(SSAArrayLoadInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        TypeReference baseType = TypeRepository.getType(i.getArrayRef(), ir).getArrayElementType();
        if (baseType.isPrimitiveType()) {
            // Assigning from a primitive array so result is not a pointer
            return;
        }
        ReferenceVariable array = rvFactory.getOrCreateLocal(i.getArrayRef(), ir);
        ReferenceVariable local = rvFactory.getOrCreateLocal(i.getDef(), ir);
        addStatement(StatementFactory.arrayToLocal(local, array, baseType, ir, i));
    }

    /**
     * v[j] = x, store into an array
     * 
     * @param i
     *            array store instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerArrayStore(SSAArrayStoreInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        TypeReference t = i.getElementType();
        if (t.isPrimitiveType() || TypeRepository.getType(i.getValue(), ir) == TypeReference.Null) {
            // Assigning into a primitive array so value is not a pointer, or assigning null (also not a real pointer)
            return;
        }
        ReferenceVariable array = rvFactory.getOrCreateLocal(i.getArrayRef(), ir);
        ReferenceVariable value = rvFactory.getOrCreateLocal(i.getValue(), ir);
        addStatement(StatementFactory.localToArrayContents(array, value, t, ir, i));
    }

    /**
     * v = (Type) x
     * 
     * @param i
     *            cast instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerCheckCast(SSACheckCastInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        if (TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // the cast value is null so no effect on pointer analysis
            return;
        }

        // This has the same effect as a copy, v = x (except for the exception
        // it could throw)
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getResult(), ir);
        ReferenceVariable checkedVal = rvFactory.getOrCreateLocal(i.getVal(), ir);
        addStatement(StatementFactory.localToLocal(result, checkedVal, ir, i));
    }

    /**
     * v = o.f
     * 
     * @param i
     *            instruction getting the field
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerGetField(SSAGetInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = rvFactory.getOrCreateLocal(i.getDef(), ir);
        ReferenceVariable receiver = rvFactory.getOrCreateLocal(i.getRef(), ir);
        addStatement(StatementFactory.fieldToLocal(i.getDeclaredField(), receiver, assignee, ir, i));
    }

    /**
     * v = ClassName.f
     * 
     * @param i
     *            instruction getting the field
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerGetStatic(SSAGetInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = rvFactory.getOrCreateLocal(i.getDef(), ir);
        ReferenceVariable field = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        addStatement(StatementFactory.staticFieldToLocal(assignee, field, ir, i));
    }

    /**
     * Handle an assignment into a field, o.f = v
     * 
     * @param i
     *            instruction for the assignment
     * @param ir
     *            code for the method containing the instruction
     */
    protected void registerPutField(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        if (i.getDeclaredFieldType().isPrimitiveType() || TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        FieldReference f = i.getDeclaredField();
        ReferenceVariable assignedValue = rvFactory.getOrCreateLocal(i.getVal(), ir);
        ReferenceVariable receiver = rvFactory.getOrCreateLocal(i.getRef(), ir);

        addStatement(StatementFactory.localToField(f, receiver, assignedValue, ir, i));
    }

    /**
     * Handle an assignment into a static field, ClassName.f = v
     * 
     * @param i
     *            instruction for the assignment
     * @param ir
     *            code for the method containing the instruction
     */
    protected void registerPutStatic(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        if (i.getDeclaredFieldType().isPrimitiveType() || TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        FieldReference f = i.getDeclaredField();
        ReferenceVariable assignedValue = rvFactory.getOrCreateLocal(i.getVal(), ir);
        ReferenceVariable fieldNode = rvFactory.getOrCreateStaticField(f);
        addStatement(StatementFactory.localToStaticField(fieldNode, assignedValue, ir, i));

    }

    /**
     * Handle a virtual, static, special, or interface invocation
     * 
     * @param i
     *            invoke instruction
     * @param ir
     *            code for the method containing the instruction (the caller)
     */
    protected void registerInvoke(SSAInvokeInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        assert (i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1);

        ReferenceVariable resultNode = null;
        if (i.getNumberOfReturnValues() > 0) {
            TypeReference returnType = TypeRepository.getType(i.getReturnValue(0), ir);
            if (!returnType.isPrimitiveType()) {
                resultNode = rvFactory.getOrCreateLocal(i.getReturnValue(0), ir);
            }
        }

        List<ReferenceVariable> actuals = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            if (TypeRepository.getType(i.getUse(j), ir).isPrimitiveType()) {
                actuals.add(null);
            } else {
                actuals.add(rvFactory.getOrCreateLocal(i.getUse(j), ir));
            }
        }

        ReferenceVariable exceptionNode = rvFactory.getOrCreateLocal(i.getException(), ir);

        // Get the receiver if it is not a static call
        // TODO the second condition is used because sometimes the receiver is a null constant
        // This is usually due to something like <code>o = null; if (o != null) { o.foo() }</code>,
        // note that the o.foo() is dead code
        ReferenceVariable receiver = null;
        if (!i.isStatic() && !ir.getSymbolTable().isNullConstant(i.getReceiver())) {
            receiver = rvFactory.getOrCreateLocal(i.getReceiver(), ir);
        }
        if (RegistrationUtil.outputLevel >= 2) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            if (resolvedMethods.isEmpty()) {
                System.err.println("No resolved methods for " + PrettyPrinter.instructionString(i, ir) + " method: "
                                                + PrettyPrinter.methodString(i.getDeclaredTarget()) + " caller: "
                                                + PrettyPrinter.methodString(ir.getMethod()));
            }
        }

        if (i.isStatic()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            addStatement(StatementFactory.staticCall(i.getCallSite(), resolvedMethod, actuals, resultNode,
                                            exceptionNode, ir, i, rvFactory));
        } else if (i.isSpecial()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            addStatement(StatementFactory.specialCall(i.getCallSite(), resolvedMethod, receiver, actuals, resultNode,
                                            exceptionNode, ir, i, rvFactory));
        } else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                                        || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            if (ir.getSymbolTable().isNullConstant(i.getReceiver())) {
                // Similar to the check above sometimes the receiver is a null constant
                // This is usually due to something like <code>o = null; if (o != null) { o.foo() }</code>,
                // note that the o.foo() is dead code
                // see SocketAdapter$SocketInputStream.read(ByteBuffer), the call to sk.cancel() near the end
                return;
            }
            addStatement(StatementFactory.virtualCall(i.getCallSite(), i.getDeclaredTarget(), receiver, actuals,
                                            resultNode, exceptionNode, ir, i,
                                            rvFactory));
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
     */
    protected void registerNewArray(SSANewInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        // all "new" instructions are assigned to a local
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getDef(), ir);

        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        addStatement(StatementFactory.newForNormalAlloc(result, klass, ir, i));

        // Handle arrays with multiple dimensions
        ReferenceVariable array = result;
        for (int dim = 1; dim < i.getNumberOfUses(); dim++) {
            // Create reference variable for inner array
            ReferenceVariable innerArray = rvFactory.createInnerArray(dim, array.getExpectedType()
                                            .getArrayElementType(), i, ir);
            // Add an allocation for the contents
            IClass arrayklass = AnalysisUtil.getClassHierarchy().lookupClass(innerArray.getExpectedType());
            assert arrayklass != null : "No class found for "
                                            + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
            addStatement(StatementFactory.newForNormalAlloc(innerArray, arrayklass, ir, i));

            // Add field assign from the inner array to the array contents field of the outer array
            addStatement(StatementFactory.multidimensionalArrayContents(array, innerArray, ir, i));

            // The array on the next iteration will be contents of this one
            array = innerArray;
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
    protected void registerNewObject(SSANewInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        // all "new" instructions are assigned to a local
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getDef(), ir);

        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        addStatement(StatementFactory.newForNormalAlloc(result, klass, ir, i));
    }

    /**
     * Handle an SSA phi instruction, x = phi(x_1, x_2, ...)
     * 
     * @param i
     *            phi instruction
     * @param ir
     *            code for the method containing the instruction
     */
    protected void registerPhiAssignment(SSAPhiInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        TypeReference type = TypeRepository.getType(i.getDef(), ir);
        if (type.isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = rvFactory.getOrCreateLocal(i.getDef(), ir);
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
                ReferenceVariable use = rvFactory.getOrCreateLocal(arg, ir);
                uses.add(use);
            }
        }
        if (uses.isEmpty()) {
            // All entries to the phi are null literals, no effect on pointer
            // analysis
            return;
        }
        addStatement(StatementFactory.phiToLocal(assignee, uses, ir, i));
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
    protected void registerReflection(SSALoadMetadataInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
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
    protected void registerReturn(SSAReturnInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        if (i.returnsPrimitiveType() || i.returnsVoid()
                                        || TypeRepository.getType(i.getResult(), ir) == TypeReference.Null) {
            // no pointers here
            return;
        }
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getResult(), ir);
        MethodSummaryNodes summaryNodes = findOrCreateMethodSummary(ir.getMethod(), rvFactory);
        ReferenceVariable summary = summaryNodes.getReturnNode();
        addStatement(StatementFactory.returnStatement(result, summary, ir, i));
    }

    /**
     * Handle an exception throw
     * 
     * @param i
     *            instruction
     * @param ir
     *            code for method containing the instruction
     */
    protected void registerThrow(SSAThrowInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        ReferenceVariable exception = rvFactory.getOrCreateLocal(i.getException(), ir);
        addAssignmentsForThrownException(i, ir, exception, rvFactory);
    }

    /**
     * Get the method summary nodes for the given method, create if necessary
     * 
     * @param method
     *            new method
     * @param rvFactory
     *            factory for creating new reference variables (if necessary)
     */
    public MethodSummaryNodes findOrCreateMethodSummary(IMethod method, ReferenceVariableFactory rvFactory) {
        MethodSummaryNodes msn = methods.get(method);
        if (msn == null) {
            msn = new MethodSummaryNodes(method, rvFactory);
            methods.put(method, msn);
        }
        return msn;
    }

    /**
     * Get all points-to statements
     * 
     * @return set of all statements
     */
    public Set<PointsToStatement> getAllStatements() {
        return statements;
    }

    /**
     * Set the entry point for the code being analyzed
     * 
     * @param entryPoint
     *            entry point for the analyzed code
     */
    public void setEntryPoint(IMethod entryPoint) {
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
     * @return Set of methods the invocation could call
     */
    protected static Set<IMethod> resolveMethodsForInvocation(SSAInvokeInstruction inv) {
        Set<IMethod> targets = null;
        if (inv.isStatic()) {
            IMethod resolvedMethod = AnalysisUtil.getClassHierarchy().resolveMethod(inv.getDeclaredTarget());
            if (resolvedMethod != null) {
                targets = Collections.singleton(resolvedMethod);
            }
        } else if (inv.isSpecial()) {
            IMethod resolvedMethod = AnalysisUtil.getClassHierarchy().resolveMethod(inv.getDeclaredTarget());
            if (resolvedMethod != null) {
                targets = Collections.singleton(resolvedMethod);
            }
        } else if (inv.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                                        || inv.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            targets = AnalysisUtil.getClassHierarchy().getPossibleTargets(inv.getDeclaredTarget());
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
        if (statements.contains(s)) {
            System.err.println("STATEMENT: " + s + " was already added");
            for (PointsToStatement ss : statements) {
                if (s.equals(ss)) {
                    System.err.println(s + " == " + ss);
                    s.equals(ss);
                }
            }
            assert !statements.contains(s) : "STATEMENT: " + s + " was already added";
        }
        statements.add(s);
        IMethod m = s.getCode().getMethod();
        Set<PointsToStatement> ss = statementsForMethod.get(m);
        if (ss == null) {
            ss = new LinkedHashSet<>();
            statementsForMethod.put(m, ss);
        }
        ss.add(s);
        if (statements.size() % 500000 == 0) {
            System.err.println(statements.size() + " statements");
            // if (StatementRegistrationPass.PROFILE) {
            // System.err.println("PAUSED HIT ENTER TO CONTINUE: ");
            // try {
            // System.in.read();
            // } catch (IOException e) {
            // e.printStackTrace();
            // }
            // }
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
        // if (!PrettyPrinter.methodString(m).contains("java.lang.Object.<init>()") && !m.isNative()) {
        // System.err.println("NO STATEMENTS FOUND FOR: " + PrettyPrinter.methodString(m));
        // }
        return Collections.emptySet();
    }

    /**
     * Look for String literals in the instruction and create allocation sites for them
     * 
     * @param i
     *            instruction to create string literals for
     * @param ir
     *            code containing the instruction
     * @param stringClass
     *            WALA representation of the java.lang.String class
     */
    protected void addStatementsForStringLiterals(SSAInstruction i, IR ir,
                                    ReferenceVariableFactory rvFactory) {
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int use = i.getUse(j);
            if (ir.getSymbolTable().isStringConstant(use)) {
                ReferenceVariable newStringLit = rvFactory.getOrCreateLocal(use, ir);
                if (handledStringLit.contains(newStringLit)) {
                    // Already handled this allocation
                    return;
                }
                handledStringLit.add(newStringLit);

                // The fake root method always allocates a String so the clinit has already been called, even if we are
                // flow sensitive

                // add points to statements to simulate the allocation
                addStatementsForStringLit(newStringLit, use, ir, i, rvFactory);
            }
        }

    }

    /**
     * Add points-to statements for a String constant
     * 
     * @param stringLit
     *            reference variable for the string literal being handled
     * @param local
     *            local variable value number for the constant
     * @param ir
     *            IR containing the constant
     * @param i
     *            instruction using the string literal
     */
    private void addStatementsForStringLit(ReferenceVariable stringLit, int local, IR ir, SSAInstruction i,
                                    ReferenceVariableFactory rvFactory) {
        // v = new String
        addStatement(StatementFactory.newForStringLiteral(stringLit, ir, i));
        for (IField f : AnalysisUtil.getStringClass().getAllFields()) {
            if (f.getName().toString().equals("value")) {
                // This is the value field of the String
                ReferenceVariable stringValue = rvFactory.getOrCreateStringLitField(stringValueClass.getReference(),
                                                local, i, ir);
                addStatement(StatementFactory.newForStringField(stringValue, ir, i));
                addStatement(new LocalToFieldStatement(f.getReference(), stringLit, stringValue, ir, i));
            }
        }

    }

    /**
     * Add points-to statements for any generated exceptions thrown by the given instruction
     * 
     * @param i
     *            instruction
     * @param ir
     *            code containing the instruction
     * @param rvFactory
     *            factory for creating new reference variables
     */
    protected final void addStatementsForGeneratedExceptions(SSAInstruction i, IR ir, ReferenceVariableFactory rvFactory) {
        for (TypeReference exType : PreciseExceptionResults.implicitExceptions(i)) {
            ReferenceVariable ex;
            if (SINGLETON_GENERATED_EXCEPTIONS) {
                ex = rvFactory.createSingletonException(ImplicitEx.fromType(exType));

                IClass exClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
                assert exClass != null : "No class found for " + PrettyPrinter.typeString(exType);
                addStatement(StatementFactory.newForGeneratedException(ex, exClass,
                                                AnalysisUtil.getIR(AnalysisUtil.getFakeRoot()), null));
            } else {
                ex = rvFactory.createImplicitExceptionNode(exType, i, ir);
                IClass exClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
                assert exClass != null : "No class found for " + PrettyPrinter.typeString(exType);

                addStatement(StatementFactory.newForGeneratedException(ex, exClass, ir, i));
            }
            addAssignmentsForThrownException(i, ir, ex, rvFactory);
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
    private final void addAssignmentsForThrownException(SSAInstruction i, IR ir, ReferenceVariable thrown,
                                    ReferenceVariableFactory rvFactory) {
        Set<IClass> notType = new LinkedHashSet<>();

        ISSABasicBlock bb = ir.getBasicBlockForInstruction(i);
        for (ISSABasicBlock succ : ir.getControlFlowGraph().getExceptionalSuccessors(bb)) {
            ReferenceVariable caught;
            if (succ.isCatchBlock()) {
                SSAGetCaughtExceptionInstruction catchIns = (SSAGetCaughtExceptionInstruction) succ.iterator().next();
                caught = rvFactory.getOrCreateLocal(catchIns.getException(), ir);
            } else {
                assert succ.isExitBlock() : "Exceptional successor should be catch block or exit block.";
                caught = findOrCreateMethodSummary(ir.getMethod(), rvFactory).getException();
            }
            addStatement(StatementFactory.exceptionAssignment(thrown, caught, i, ir, notType));
            Iterator<TypeReference> caughtTypes = succ.getCaughtExceptionTypes();
            while (caughtTypes.hasNext()) {
                notType.add(AnalysisUtil.getClassHierarchy().lookupClass(caughtTypes.next()));
            }
        }
    }

    /**
     * Get the generated allocation node for the class returned by a native method with no signature
     * 
     * @param resolvedNative
     *            native method
     * @return generated allocation site for the exit value to a native method with no signatures
     */
    public AllocSiteNode getReturnNodeForNative(IMethod resolvedNative) {
        ExitType type = ExitType.NORMAL;
        OrderedPair<IMethod, ExitType> key = new OrderedPair<>(resolvedNative, type);
        AllocSiteNode n = nativeAllocs.get(key);
        if (n == null) {
            // Use the declaring class as the allocator
            IClass allocatingClass = resolvedNative.getDeclaringClass();
            TypeReference retType = resolvedNative.getReturnType();
            assert !retType.isPrimitiveType() : "Primitive return: " + PrettyPrinter.methodString(resolvedNative);
            IClass allocatedClass = AnalysisUtil.getClassHierarchy().lookupClass(retType);
            n = AllocSiteNodeFactory.getAllocationNodeForNative(allocatedClass, allocatingClass, type, resolvedNative);
            nativeAllocs.put(key, n);
        }
        return n;
    }

    /**
     * Get the generated allocation node for the exception (of a given type thrown) by a native method with no signature
     * <p>
     * TODO the type here is imprecise, might want to mark it as such
     * <p>
     * e.g. if the actual native method would throw a NullPointerException and NullPointerException is caught in the
     * caller, but a node is only created for a RunTimeException then the catch block will be bypassed
     * 
     * @param resolvedNative
     *            native method
     * @param type
     *            exception type
     * @return generated allocation site for the exit value to a native method with no signatures
     */
    public AllocSiteNode getExceptionNodeForNative(IMethod resolvedNative, TypeReference exType) {
        ExitType type = ExitType.EXCEPTIONAL;
        OrderedPair<IMethod, ExitType> key = new OrderedPair<>(resolvedNative, type);
        AllocSiteNode n = nativeAllocs.get(key);
        if (n == null) {
            // Use the declaring class as the allocator
            IClass allocatingClass = resolvedNative.getDeclaringClass();
            IClass allocatedClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
            n = AllocSiteNodeFactory.getAllocationNodeForNative(allocatedClass, allocatingClass, type, resolvedNative);
            nativeAllocs.put(key, n);
        }
        return n;
    }

    /**
     * Add points-to statements for the given list of class initializers
     * 
     * @param trigger
     *            instruction that triggered the class init
     * @param containingCode
     *            code containing the instruction that triggered
     * @param clinits
     *            class initialization methods that might need to be called in the order they need to be called (i.e.
     *            element j is a super class of element j+1)
     */
    protected void addStatementsForClassInitializer(SSAInstruction trigger, IR containingCode, List<IMethod> clinits) {
        addStatement(StatementFactory.classInit(clinits, containingCode, trigger));
    }
}
