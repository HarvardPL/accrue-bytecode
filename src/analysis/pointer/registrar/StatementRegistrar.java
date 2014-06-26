package analysis.pointer.registrar;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
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
import util.InstructionType;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StatementFactory;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
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
public final class StatementRegistrar {

    /**
     * Map from method signature to nodes representing formals and returns
     */
    private final Map<IMethod, MethodSummaryNodes> methods;
    /**
     * Entry point for the code being analyzed
     */
    private final IMethod entryPoint;
    /**
     * Map from method to the points-to statements generated from instructions in that method
     */
    private final Map<IMethod, Set<PointsToStatement>> statementsForMethod;
    /**
     * String literals that new allocation sites have already been created for
     */
    private final Set<ReferenceVariable> handledStringLit;
    /**
     * If true then only one allocation will be made for each generated exception type. This will reduce the size of the
     * points-to graph (and speed up the points-to analysis), but result in a loss of precision for such exceptions.
     */
    public static final boolean SINGLETON_GENERATED_EXCEPTIONS = true;
    /**
     * If the above is true and only one allocation will be made for each generated exception type. This map holds that
     * node
     */
    private final Map<ImplicitEx, ReferenceVariable> singletonExceptions;
    /**
     * Methods we have already added statements for
     */
    private final Set<IMethod> visitedMethods = new LinkedHashSet<>();
    /**
     * Factory for finding and creating reference variable (local variable and static fields)
     */
    private final ReferenceVariableFactory rvFactory = new ReferenceVariableFactory();

    /**
     * Class that manages the registration of points-to statements. These describe how certain expressions modify the
     * points-to graph.
     */
    public StatementRegistrar() {
        this.methods = new LinkedHashMap<>();
        this.statementsForMethod = new HashMap<>();
        this.singletonExceptions = new HashMap<>();
        this.handledStringLit = new HashSet<>();
        this.entryPoint = AnalysisUtil.getFakeRoot();
    }

    /**
     * Handle all the instructions for a given method
     * 
     * @param m
     *            method to register points-to statements for
     */
    public void registerMethod(IMethod m) {
        for (InstructionInfo info : getFromMethod(m)) {
            handle(info);
        }
    }

    /**
     * Get points-to statements for the given method if this method has not already been processed. (Does not
     * recursively get statements for callees.)
     * 
     * @param m
     *            method to get instructions for
     * @return set of new instructions, empty if the method is abstract, or has already been processed
     */
    Set<InstructionInfo> getFromMethod(IMethod m) {

        if (visitedMethods.contains(m)) {
            if (PointsToAnalysis.outputLevel >= 2) {
                System.err.println("\tAlready added " + PrettyPrinter.methodString(m));
            }
            return Collections.emptySet();
        }
        if (m.isAbstract()) {
            if (PointsToAnalysis.outputLevel >= 2) {
                System.err.println("No need to analyze abstract methods: " + m.getSignature());
            }
            return Collections.emptySet();
        }

        IR ir = AnalysisUtil.getIR(m);
        if (ir == null) {
            // Native method with no signature
            assert m.isNative() : "No IR for non-native method: " + PrettyPrinter.methodString(m);
            registerNative(m, rvFactory);
            return Collections.emptySet();
        }
        visitedMethods.add(m);

        TypeRepository types = new TypeRepository(ir);
        PrettyPrinter pp = new PrettyPrinter(ir);

        // Add edges from formal summary nodes to the local variables representing the method parameters
        registerFormalAssignments(ir, rvFactory, pp);

        Set<InstructionInfo> newInstructions = new LinkedHashSet<>();
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction ins : bb) {
                newInstructions.add(new InstructionInfo(ins, ir, bb, types, pp));
            }
        }

        if (PointsToAnalysis.outputLevel >= 1) {
            System.err.println("HANDLED: " + PrettyPrinter.methodString(m));
            CFGWriter.writeToFile(ir);
            System.err.println();
        }

        if (PointsToAnalysis.outputLevel >= 6) {
            try (Writer writer = new StringWriter()) {
                PrettyPrinter.writeIR(ir, writer, "\t", "\n");
                System.err.print(writer.toString());
            } catch (IOException e) {
                throw new RuntimeException();
            }
        }
        return newInstructions;
    }

    /**
     * Handle a particular instruction, this dispatches on the type of the instruction
     * 
     * @param info
     *            information about the instruction to handle
     */
    void handle(InstructionInfo info) {
        SSAInstruction i = info.instruction;
        IR ir = info.ir;
        ISSABasicBlock bb = info.basicBlock;
        TypeRepository types = info.typeRepository;
        PrettyPrinter printer = info.prettyPrinter;

        assert i.getNumberOfDefs() <= 2 : "More than two defs in instruction: " + i;

        // Add statements for any string literals in the instruction
        findAndRegisterStringLiterals(i, ir, rvFactory, printer);

        // Add statements for any JVM-generated exceptions this instruction could throw (e.g. NullPointerException)
        findAndRegisterGeneratedExceptions(i, bb, ir, rvFactory, types, printer);

        List<IMethod> inits = ClassInitFinder.getClassInitializers(info.instruction);
        if (!inits.isEmpty()) {
            registerClassInitializers(i, ir, inits);
        }

        InstructionType type = InstructionType.forInstruction(i);
        switch (type) {
        case ARRAY_LOAD:
            // x = v[i]
            registerArrayLoad((SSAArrayLoadInstruction) i, ir, rvFactory, types, printer);
            return;
        case ARRAY_STORE:
            // v[i] = x
            registerArrayStore((SSAArrayStoreInstruction) i, ir, rvFactory, types, printer);
            return;
        case CHECK_CAST:
            // v = (Type) x
            registerCheckCast((SSACheckCastInstruction) i, ir, rvFactory, types, printer);
            return;
        case GET_FIELD:
            // v = o.f
            registerGetField((SSAGetInstruction) i, ir, rvFactory, types, printer);
            return;
        case GET_STATIC:
            // v = ClassName.f
            registerGetStatic((SSAGetInstruction) i, ir, rvFactory, types, printer);
            return;
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            // procedure calls, instance initializers
            registerInvoke((SSAInvokeInstruction) i, bb, ir, rvFactory, types, printer);
            return;
        case LOAD_METADATA:
            // Reflection
            registerReflection((SSALoadMetadataInstruction) i, ir, rvFactory, types, printer);
            return;
        case NEW_ARRAY:
            registerNewArray((SSANewInstruction) i, ir, rvFactory, types, printer);
            return;
        case NEW_OBJECT:
            // v = new Foo();
            registerNewObject((SSANewInstruction) i, ir, rvFactory, types, printer);
            return;
        case PHI:
            // v = phi(x_1,x_2)
            registerPhiAssignment((SSAPhiInstruction) i, ir, rvFactory, types, printer);
            return;
        case PUT_FIELD:
            // o.f = v
            registerPutField((SSAPutInstruction) i, ir, rvFactory, types, printer);
            return;
        case PUT_STATIC:
            // ClassName.f = v
            registerPutStatic((SSAPutInstruction) i, ir, rvFactory, types, printer);
            return;
        case RETURN:
            // return v
            registerReturn((SSAReturnInstruction) i, ir, rvFactory, types, printer);
            return;
        case THROW:
            // throw e
            registerThrow((SSAThrowInstruction) i, bb, ir, rvFactory, types, printer);
            return;
        case ARRAY_LENGTH: // primitive op with generated exception
        case BINARY_OP: // primitive op
        case BINARY_OP_EX: // primitive op with generated exception
        case COMPARISON: // primitive op
        case CONDITIONAL_BRANCH: // computes primitive and branches
        case CONVERSION: // primitive op
        case GET_CAUGHT_EXCEPTION: // handled in PointsToStatement#checkThrown
        case GOTO: // control flow
        case INSTANCE_OF: // results in a primitive
        case MONITOR: // generated exception already taken care of
        case SWITCH: // only switch on int
        case UNARY_NEG_OP: // primitive op
            break;
        }
    }

    /**
     * v = a[j], load from an array
     */
    private void registerArrayLoad(SSAArrayLoadInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        TypeReference arrayType = types.getType(i.getArrayRef());
        TypeReference baseType = arrayType.getArrayElementType();
        assert baseType.equals(types.getType(i.getDef()));
        if (baseType.isPrimitiveType()) {
            // Assigning to a primitive
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), baseType, ir.getMethod(), pp);
        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getArrayRef(), arrayType, ir.getMethod(), pp);
        addStatement(StatementFactory.arrayToLocal(v, a, baseType, ir.getMethod()));
    }

    /**
     * a[j] = v, store into an array
     */
    private void registerArrayStore(SSAArrayStoreInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        TypeReference valueType = types.getType(i.getValue());
        if (valueType.isPrimitiveType()) {
            // Assigning from a primitive or assigning null (also no effect on points-to graph)
            return;
        }

        TypeReference arrayType = types.getType(i.getArrayRef());
        TypeReference baseType = arrayType.getArrayElementType();

        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getArrayRef(), arrayType, ir.getMethod(), pp);
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getValue(), valueType, ir.getMethod(), pp);
        addStatement(StatementFactory.localToArrayContents(a, v, baseType, ir.getMethod(), i));
    }

    /**
     * v2 = (TypeName) v1
     */
    private void registerCheckCast(SSACheckCastInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        TypeReference valType = types.getType(i.getVal());
        if (valType == TypeReference.Null) {
            // the cast value is null so no effect on pointer analysis
            // note that cast to/from primitives are a different instruction (SSAConversionInstruction)
            return;
        }

        // This has the same effect as a copy, v = x (except for the exception it could throw, handled elsewhere)
        ReferenceVariable v2 = rvFactory.getOrCreateLocal(i.getResult(), i.getDeclaredResultTypes()[0], ir.getMethod(),
                                        pp);
        ReferenceVariable v1 = rvFactory.getOrCreateLocal(i.getVal(), valType, ir.getMethod(), pp);
        addStatement(StatementFactory.localToLocal(v2, v1, ir.getMethod()));
    }

    /**
     * v = o.f
     */
    private void registerGetField(SSAGetInstruction i, IR ir, ReferenceVariableFactory rvFactory, TypeRepository types,
                                    PrettyPrinter pp) {
        TypeReference resultType = i.getDeclaredFieldType();
        // TODO If the class can't be found then WALA set the type to object (why can't it be found?)
        assert resultType.getName().equals(types.getType(i.getDef()).getName())
                                        || types.getType(i.getDef()).equals(TypeReference.JavaLangObject);
        if (resultType.isPrimitiveType()) {
            // No pointers here
            return;
        }

        TypeReference receiverType = types.getType(i.getRef());

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pp);
        ReferenceVariable o = rvFactory.getOrCreateLocal(i.getRef(), receiverType, ir.getMethod(), pp);
        FieldReference f = i.getDeclaredField();
        addStatement(StatementFactory.fieldToLocal(v, o, f, ir.getMethod()));
    }

    /**
     * v = ClassName.f
     */
    private void registerGetStatic(SSAGetInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        TypeReference resultType = i.getDeclaredFieldType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        if (resultType.isPrimitiveType()) {
            // No pointers here
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pp);
        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        addStatement(StatementFactory.staticFieldToLocal(v, f, ir.getMethod()));
    }

    /**
     * o.f = v
     */
    private void registerPutField(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory, TypeRepository types,
                                    PrettyPrinter pp) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType()) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        TypeReference receiverType = types.getType(i.getRef());

        ReferenceVariable o = rvFactory.getOrCreateLocal(i.getRef(), valueType, ir.getMethod(), pp);
        FieldReference f = i.getDeclaredField();
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), receiverType, ir.getMethod(), pp);
        addStatement(StatementFactory.localToField(o, f, v, ir.getMethod(), i));
    }

    /**
     * ClassName.f = v
     */
    private void registerPutStatic(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType()) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), valueType, ir.getMethod(), pp);
        addStatement(StatementFactory.localToStaticField(f, v, ir.getMethod(), i));

    }

    /**
     * A virtual, static, special, or interface invocation
     * 
     * @param bb
     */
    private void registerInvoke(SSAInvokeInstruction i, ISSABasicBlock bb, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        assert (i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1);

        // //////////// Result ////////////

        ReferenceVariable result = null;
        if (i.getNumberOfReturnValues() > 0) {
            TypeReference returnType = types.getType(i.getReturnValue(0));
            if (!returnType.isPrimitiveType()) {
                result = rvFactory.getOrCreateLocal(i.getReturnValue(0), returnType, ir.getMethod(), pp);
            }
        }

        // //////////// Actual arguments ////////////

        List<ReferenceVariable> actuals = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            TypeReference actualType = types.getType(i.getUse(j));
            if (actualType.isPrimitiveType()) {
                actuals.add(null);
            } else {
                actuals.add(rvFactory.getOrCreateLocal(i.getUse(j), actualType, ir.getMethod(), pp));
            }
        }

        // //////////// Receiver ////////////

        // Get the receiver if it is not a static call
        // TODO the second condition is used because sometimes the receiver is a null constant
        // This is usually due to something like <code>o = null; if (o != null) { o.foo(); }</code>,
        // note that the o.foo() is dead code
        // see SocketAdapter$SocketInputStream.read(ByteBuffer), the call to sk.cancel() near the end
        ReferenceVariable receiver = null;
        if (!i.isStatic() && !ir.getSymbolTable().isNullConstant(i.getReceiver())) {
            TypeReference receiverType = types.getType(i.getReceiver());
            receiver = rvFactory.getOrCreateLocal(i.getReceiver(), receiverType, ir.getMethod(), pp);
        }

        if (PointsToAnalysis.outputLevel >= 2) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            if (resolvedMethods.isEmpty()) {
                System.err.println("No resolved methods for " + pp.instructionString(i) + " method: "
                                                + PrettyPrinter.methodString(i.getDeclaredTarget()) + " caller: "
                                                + PrettyPrinter.methodString(ir.getMethod()));
            }
        }

        // //////////// Exceptions ////////////

        TypeReference exType = types.getType(i.getException());
        ReferenceVariable exception = rvFactory.getOrCreateLocal(i.getException(), exType, ir.getMethod(), pp);
        registerThrownException(bb, ir, exception, rvFactory, types, pp);

        // //////////// Resolve methods add statements ////////////

        if (i.isStatic()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            if (resolvedMethods.isEmpty()) {
                System.err.println("No method found for " + PrettyPrinter.methodString(i.getDeclaredTarget()));
                return;
            }
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = findOrCreateMethodSummary(resolvedCallee, rvFactory);
            addStatement(StatementFactory.staticCall(i.getCallSite(), ir.getMethod(), resolvedCallee, result, actuals,
                                            exception, calleeSummary));
        } else if (i.isSpecial()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = findOrCreateMethodSummary(resolvedCallee, rvFactory);
            addStatement(StatementFactory.specialCall(i.getCallSite(), ir.getMethod(), resolvedCallee, result,
                                            receiver, actuals, exception, calleeSummary));
        } else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                                        || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            if (ir.getSymbolTable().isNullConstant(i.getReceiver())) {
                // Similar to the check above sometimes the receiver is a null constant
                return;
            }
            addStatement(StatementFactory.virtualCall(i.getCallSite(), ir.getMethod(), i.getDeclaredTarget(), result,
                                            receiver, actuals, exception, rvFactory));
        } else {
            throw new UnsupportedOperationException("Unhandled invocation code: " + i.getInvocationCode() + " for "
                                            + PrettyPrinter.methodString(i.getDeclaredTarget()));
        }
    }

    /**
     * a = new TypeName[j][k][l]
     * <p>
     * Note that this is only the allocation not the initialization if there is any.
     */
    private void registerNewArray(SSANewInstruction i, IR ir, ReferenceVariableFactory rvFactory, TypeRepository types,
                                    PrettyPrinter pp) {
        // all "new" instructions are assigned to a local
        TypeReference resultType = i.getConcreteType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pp);

        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        addStatement(StatementFactory.newForNormalAlloc(a, klass, ir.getMethod(), i.getNewSite().getProgramCounter()));

        // Handle arrays with multiple dimensions
        ReferenceVariable outerArray = a;
        for (int dim = 1; dim < i.getNumberOfUses(); dim++) {
            // Create reference variable for inner array
            TypeReference innerType = outerArray.getExpectedType().getArrayElementType();
            int pc = i.getNewSite().getProgramCounter();
            ReferenceVariable innerArray = rvFactory.createInnerArray(dim, pc, innerType, ir.getMethod());

            // Add an allocation for the contents
            IClass arrayklass = AnalysisUtil.getClassHierarchy().lookupClass(innerType);
            assert arrayklass != null : "No class found for " + PrettyPrinter.typeString(innerType);
            addStatement(StatementFactory.newForInnerArray(innerArray, arrayklass, ir.getMethod()));

            // Add field assign from the inner array to the array contents field of the outer array
            addStatement(StatementFactory.multidimensionalArrayContents(outerArray, innerArray, ir.getMethod()));

            // The array on the next iteration will be contents of this one
            outerArray = innerArray;
        }
    }

    /**
     * v = new TypeName
     * <p>
     * Handle an allocation of the form: "new Foo". Note that this is only the allocation not the constructor call.
     */
    private void registerNewObject(SSANewInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        // all "new" instructions are assigned to a local
        TypeReference resultType = i.getConcreteType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pp);

        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        addStatement(StatementFactory.newForNormalAlloc(result, klass, ir.getMethod(), i.getNewSite()
                                        .getProgramCounter()));
    }

    /**
     * x = phi(x_1, x_2, ...)
     */
    private void registerPhiAssignment(SSAPhiInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        TypeReference phiType = types.getType(i.getDef());
        if (phiType.isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = rvFactory.getOrCreateLocal(i.getDef(), phiType, ir.getMethod(), pp);
        List<ReferenceVariable> uses = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int arg = i.getUse(j);
            TypeReference argType = types.getType(arg);
            if (argType != TypeReference.Null) {
                assert !argType.isPrimitiveType() : "arg type: " + PrettyPrinter.typeString(argType)
                                                + " for phi type: " + PrettyPrinter.typeString(phiType);

                ReferenceVariable x_i = rvFactory.getOrCreateLocal(arg, phiType, ir.getMethod(), pp);
                uses.add(x_i);
            }
        }

        if (uses.isEmpty()) {
            // All entries to the phi are null literals, no effect on pointer analysis
            return;
        }
        addStatement(StatementFactory.phiToLocal(assignee, uses, ir.getMethod()));
    }

    /**
     * Load-metadata is used for reflective operations
     */
    @SuppressWarnings("unused")
    private void registerReflection(SSALoadMetadataInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        // TODO statement registrar not handling reflection yet
    }

    /**
     * return v
     */
    private void registerReturn(SSAReturnInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        if (i.returnsVoid()) {
            // no pointers here
            return;
        }

        TypeReference valType = types.getType(i.getResult());
        if (valType.isPrimitiveType()) {
            // returning a primitive or "null"
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getResult(), valType, ir.getMethod(), pp);
        ReferenceVariable summary = findOrCreateMethodSummary(ir.getMethod(), rvFactory).getReturn();
        addStatement(StatementFactory.returnStatement(v, summary, ir.getMethod(), i));
    }

    /**
     * throw v
     * 
     * @param bb
     */
    private void registerThrow(SSAThrowInstruction i, ISSABasicBlock bb, IR ir, ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pp) {
        TypeReference throwType = types.getType(i.getException());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getException(), throwType, ir.getMethod(), pp);
        registerThrownException(bb, ir, v, rvFactory, types, pp);
    }

    /**
     * Get the method summary nodes for the given method, create if necessary
     * 
     * @param method
     *            method to get summary nodes for
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
    static Set<IMethod> resolveMethodsForInvocation(SSAInvokeInstruction inv) {
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

        IMethod m = s.getMethod();
        Set<PointsToStatement> ss = statementsForMethod.get(m);
        if (ss == null) {
            ss = new LinkedHashSet<>();
            statementsForMethod.put(m, ss);
        }
        assert !ss.contains(s) : "STATEMENT: " + s + " was already added";
        ss.add(s);

        int num = size();
        if (num % 10000 == 0) {
            System.err.println("REGISTERED: " + num);
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
     * Get the number of statements in the registrar
     * 
     * @return number of registered statements
     */
    public int size() {
        int total = 0;
        for (IMethod m : statementsForMethod.keySet()) {
            total += statementsForMethod.get(m).size();
        }
        return total;
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
     * Set of all methods that have been registered
     * 
     * @return set of methods
     */
    public Set<IMethod> getRegisteredMethods() {
        return statementsForMethod.keySet();
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
    private void findAndRegisterStringLiterals(SSAInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                    PrettyPrinter pp) {
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int use = i.getUse(j);
            if (ir.getSymbolTable().isStringConstant(use)) {
                ReferenceVariable newStringLit = rvFactory.getOrCreateLocal(use, TypeReference.JavaLangString,
                                                ir.getMethod(), pp);
                if (handledStringLit.contains(newStringLit)) {
                    // Already handled this allocation
                    return;
                }
                handledStringLit.add(newStringLit);

                // The fake root method always allocates a String so the clinit has already been called, even if we are
                // flow sensitive

                // add points to statements to simulate the allocation
                registerStringLiteral(newStringLit, use, ir.getMethod(), rvFactory);
            }
        }

    }

    /**
     * Add points-to statements for a String constant
     * 
     * @param stringLit
     *            reference variable for the string literal being handled
     * @param local
     *            local variable value number for the literal
     * @param m
     *            Method where the literal is created
     */
    private void registerStringLiteral(ReferenceVariable stringLit, int local, IMethod m,
                                    ReferenceVariableFactory rvFactory) {
        // v = new String
        addStatement(StatementFactory.newForStringLiteral(stringLit, m));
        for (IField f : AnalysisUtil.getStringClass().getAllFields()) {
            if (f.getName().toString().equals("value")) {
                // This is the value field of the String
                ReferenceVariable stringValue = rvFactory.createStringLitField(local, m);
                addStatement(StatementFactory.newForStringField(stringValue, m));
                addStatement(new LocalToFieldStatement(stringLit, f.getReference(), stringValue, m));
            }
        }

    }

    /**
     * Add points-to statements for any generated exceptions thrown by the given instruction
     * 
     * @param i
     *            instruction that may throw generated exceptions
     * @param bb
     * @param ir
     *            code containing the instruction
     * @param rvFactory
     *            factory for creating new reference variables
     */
    private final void findAndRegisterGeneratedExceptions(SSAInstruction i, ISSABasicBlock bb, IR ir,
                                    ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pp) {
        for (TypeReference exType : PreciseExceptionResults.implicitExceptions(i)) {
            ReferenceVariable ex;
            if (SINGLETON_GENERATED_EXCEPTIONS) {
                ImplicitEx type = ImplicitEx.fromType(exType);
                ex = singletonExceptions.get(type);
                if (ex == null) {
                    ex = rvFactory.createSingletonException(type);

                    IClass exClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
                    assert exClass != null : "No class found for " + PrettyPrinter.typeString(exType);

                    addStatement(StatementFactory.newForGeneratedException(ex, exClass, ir.getMethod()));
                    singletonExceptions.put(type, ex);
                }
            } else {
                ex = rvFactory.createImplicitExceptionNode(ImplicitEx.fromType(exType), bb.getNumber(), ir.getMethod());

                IClass exClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
                assert exClass != null : "No class found for " + PrettyPrinter.typeString(exType);

                addStatement(StatementFactory.newForGeneratedException(ex, exClass, ir.getMethod()));
            }
            registerThrownException(bb, ir, ex, rvFactory, types, pp);
        }
    }

    /**
     * Add an assignment from the a thrown exception to any catch block or exit block exception that exception could
     * reach
     * 
     * @param bb
     *            Basic block containing the instruction that throws the exception
     * @param ir
     *            code containing the instruction that throws
     * @param thrown
     *            reference variable representing the value of the exception
     * @param types
     *            type information about local variables
     * @param pp
     *            pretty printer for the appropriate method
     */
    private final void registerThrownException(ISSABasicBlock bb, IR ir, ReferenceVariable thrown,
                                    ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pp) {
        
        IClass thrownClass = AnalysisUtil.getClassHierarchy().lookupClass(thrown.getExpectedType());
        
        Set<IClass> notType = new LinkedHashSet<>();
        for (ISSABasicBlock succ : ir.getControlFlowGraph().getExceptionalSuccessors(bb)) {
            ReferenceVariable caught;
            TypeReference caughtType;

            if (succ.isCatchBlock()) {
                // The catch instruction is the first instruction in the basic block
                SSAGetCaughtExceptionInstruction catchIns = (SSAGetCaughtExceptionInstruction) succ.iterator().next();

                Iterator<TypeReference> caughtTypes = succ.getCaughtExceptionTypes();
                caughtType = caughtTypes.next();
                if (caughtTypes.hasNext()) {
                    System.err.println("More than one catch type? in BB" + bb.getNumber());
                    Iterator<TypeReference> caughtTypes2 = succ.getCaughtExceptionTypes();
                    while (caughtTypes2.hasNext()) {
                        System.err.println(PrettyPrinter.typeString(caughtTypes2.next()));
                    }
                    CFGWriter.writeToFile(ir);
                    assert false;
                }
                assert caughtType.equals(types.getType(catchIns.getException()));

                IClass caughtClass = AnalysisUtil.getClassHierarchy().lookupClass(caughtType);
                boolean definitelyCaught = TypeRepository.isAssignableFrom(caughtClass, thrownClass);
                boolean maybeCaught = TypeRepository.isAssignableFrom(thrownClass, caughtClass);

                if (maybeCaught || definitelyCaught) {
                    caught = rvFactory.getOrCreateLocal(catchIns.getException(), caughtType, ir.getMethod(), pp);
                    addStatement(StatementFactory.exceptionAssignment(thrown, caught, notType, ir.getMethod()));
                }

                // if we have definitely caught the exception, no need to add more exception assignment statements.
                if (definitelyCaught) {
                    break;
                }

                // Add this exception to the set of types that have already been caught
                notType.add(AnalysisUtil.getClassHierarchy().lookupClass(caughtType));
            } else {
                assert succ.isExitBlock() : "Exceptional successor should be catch block or exit block.";
                // TODO do not propagate java.lang.Errors out of this class, this is possibly unsound
                notType.add(AnalysisUtil.getErrorClass());
                caught = findOrCreateMethodSummary(ir.getMethod(), rvFactory).getException();
                addStatement(StatementFactory.exceptionAssignment(thrown, caught, notType, ir.getMethod()));
            }
        }
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
    void registerClassInitializers(SSAInstruction trigger, IR containingCode, List<IMethod> clinits) {
        addStatement(StatementFactory.classInit(clinits, containingCode.getMethod(), trigger));
    }

    /**
     * Add statements for the generated allocation of an exception or return object of a given type for a native method
     * with no signature.
     * 
     * @param m
     *            native method
     * @param type
     *            allocated type
     * @param summary
     *            summary reference variable for method exception or return
     */
    private void registerAllocationForNative(IMethod m, TypeReference type, ReferenceVariable summary) {
        IClass allocatedClass = AnalysisUtil.getClassHierarchy().lookupClass(type);
        addStatement(StatementFactory.newForNative(summary, allocatedClass, m));
    }

    private void registerNative(IMethod m, ReferenceVariableFactory rvFactory) {
        if (!visitedMethods.add(m)) {
            // Already handled
            return;
        }
        MethodSummaryNodes methodSummary = findOrCreateMethodSummary(m, rvFactory);
        if (!m.getReturnType().isPrimitiveType()) {
            // Allocation of return value
            registerAllocationForNative(m, m.getReturnType(), methodSummary.getReturn());
        }

        boolean containsRTE = false;
        try {
            for (TypeReference exType : m.getDeclaredExceptions()) {
                // Allocation of exception of a particular type
                ReferenceVariable ex = rvFactory.createNativeException(exType, m);
                addStatement(StatementFactory.exceptionAssignment(ex, methodSummary.getException(),
                                                Collections.<IClass> emptySet(), m));
                registerAllocationForNative(m, exType, ex);
                containsRTE |= exType.equals(TypeReference.JavaLangRuntimeException);
            }
        } catch (UnsupportedOperationException | InvalidClassFileException e) {
            throw new RuntimeException(e);
        }
        // All methods can throw a RuntimeException

        // TODO the types for all native generated exceptions are imprecise, might want to mark them as such
        // e.g. if the actual native method would throw a NullPointerException and NullPointerException is caught in the
        // caller, but a node is only created for a RunTimeException then the catch block will be bypassed
        if (!containsRTE) {
            ReferenceVariable ex = rvFactory.createNativeException(TypeReference.JavaLangRuntimeException, m);
            addStatement(StatementFactory.exceptionAssignment(ex, methodSummary.getException(),
                                            Collections.<IClass> emptySet(), m));
            registerAllocationForNative(m, TypeReference.JavaLangRuntimeException, methodSummary.getException());
        }
    }

    private void registerFormalAssignments(IR ir, ReferenceVariableFactory rvFactory, PrettyPrinter pp) {
        MethodSummaryNodes methodSummary = findOrCreateMethodSummary(ir.getMethod(), rvFactory);
        for (int i = 0; i < ir.getNumberOfParameters(); i++) {
            TypeReference paramType = ir.getParameterType(i);
            if (paramType.isPrimitiveType()) {
                // No statements for primitives
                continue;
            }
            int paramNum = ir.getParameter(i);
            ReferenceVariable param = rvFactory.getOrCreateLocal(paramNum, paramType, ir.getMethod(), pp);
            addStatement(StatementFactory.localToLocal(param, methodSummary.getFormal(i), ir.getMethod()));
        }
    }

    /**
     * Map from local variable to reference variable. This is not complete until the statement registration pass has
     * completed.
     * 
     * @return map from local variable to unique reference variable
     */
    public ReferenceVariableCache getAllLocals() {
        return rvFactory.getAllLocals();
    }

    /**
     * Instruction together with information about the containing code
     */
    static final class InstructionInfo {
        final SSAInstruction instruction;
        final IR ir;
        final ISSABasicBlock basicBlock;
        final TypeRepository typeRepository;
        final PrettyPrinter prettyPrinter;

        /**
         * Instruction together with information about the containing code
         * 
         * @param i
         *            instruction
         * @param ir
         *            containing code
         * @param bb
         *            containing basic block
         * @param types
         *            results of type inference for the method
         * @param pp
         *            Pretty printer for local variables and instructions in enclosing method
         */
        public InstructionInfo(SSAInstruction i, IR ir, ISSABasicBlock bb, TypeRepository types, PrettyPrinter pp) {
            assert i != null;
            assert ir != null;
            assert types != null;
            assert pp != null;
            assert bb != null;

            this.instruction = i;
            this.ir = ir;
            this.typeRepository = types;
            this.prettyPrinter = pp;
            this.basicBlock = bb;
        }

        @Override
        public int hashCode() {
            return instruction.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            InstructionInfo other = (InstructionInfo) obj;
            return this.instruction.equals(other.instruction);
        }
    }
}
