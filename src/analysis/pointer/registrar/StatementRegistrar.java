package analysis.pointer.registrar;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import types.TypeRepository;
import util.InstructionType;
import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.duplicates.RemoveDuplicateStatements;
import analysis.pointer.duplicates.RemoveDuplicateStatements.VariableIndex;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.NewStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
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
public class StatementRegistrar {

    /**
     * Map from method signature to nodes representing formals and returns
     */
    private final ConcurrentMap<IMethod, MethodSummaryNodes> methods;
    /**
     * Entry point for the code being analyzed
     */
    private final IMethod entryMethod;

    /**
     * Program points that are used for special initializations at the beginning of the program. This set is the program
     * points that were added before the entry method was processed.
     */
    private final Set<ProgramPoint> entryMethodProgramPoints;

    /**
     * Map from method to the points-to statements generated from instructions in that method
     */
    private final ConcurrentMap<IMethod, Set<PointsToStatement>> statementsForMethod;
    /**
     * Map from method to the CallSiteProgramPoints that are in that method.
     */
    private final ConcurrentMap<IMethod, Set<CallSiteProgramPoint>> callSitesForMethod;

    /*
     * Program point to statement map
     */
    private final ConcurrentMap<ProgramPoint, PointsToStatement> ppToStmtMap;

    /**
     * The total number of statements
     */
    private int size;

    /**
     * String and null literals that allocation statements have already been created for
     */
    private final Set<ReferenceVariable> handledLiterals;
    /**
     * If true then only one allocation will be made for each generated exception type. This will reduce the size of the
     * points-to graph (and speed up the points-to analysis), but result in a loss of precision for such exceptions.
     */
    private final boolean useSingleAllocForGenEx;
    /**
     * If true then only one allocation will be made for each type of throwable. This will reduce the size of the
     * points-to graph (and speed up the points-to analysis), but result in a loss of precision for throwables.
     */
    private final boolean useSingleAllocPerThrowableType;

    /**
     * If true then only one allocation will be made for any kind of primitive array. Reduces precision, but improves
     * performance.
     */
    private final boolean useSingleAllocForPrimitiveArrays;

    /**
     * If true then only one allocation will be made for any string. This will reduce the size of the points-to graph
     * (and speed up the points-to analysis), but result in a loss of precision for strings.
     */
    private final boolean useSingleAllocForStrings;

    /**
     * If the above is true and only one allocation will be made for each generated exception type. This map holds that
     * node
     */
    private final ConcurrentMap<TypeReference, ReferenceVariable> singletonReferenceVariables;

    /**
     * Methods we have already added statements for
     */
    private final Set<IMethod> registeredMethods = AnalysisUtil.createConcurrentSet();
    /**
     * Factory for finding and creating reference variable (local variable and static fields)
     */
    private final ReferenceVariableFactory rvFactory = new ReferenceVariableFactory();
    /**
     * factory used to create points-to statements
     */
    private final StatementFactory stmtFactory;
    /**
     * Map from method to index mapping replaced variables to their replacements
     */
    private final Map<IMethod, VariableIndex> replacedVariableMap = new LinkedHashMap<>();

    /**
     * Class that manages the registration of points-to statements. These describe how certain expressions modify the
     * points-to graph.
     *
     * @param factory factory used to create points-to statements
     *
     * @param useSingleAllocForGenEx If true then only one allocation will be made for each generated exception type.
     *            This will reduce the size of the points-to graph (and speed up the points-to analysis), but result in
     *            a loss of precision for such exceptions.
     * @param useSingleAllocPerThrowableType If true then only one allocation will be made for each type of throwable.
     *            This will reduce the size of the points-to graph (and speed up the points-to analysis), but result in
     *            a loss of precision for throwables.
     * @param useSingleAllocForPrimitiveArrays If true then only one allocation will be made for any kind of primitive
     *            array. Reduces precision, but improves performance.
     * @param useSingleAllocForStrings If true then only one allocation will be made for any string. This will reduce
     *            the size of the points-to graph (and speed up the points-to analysis), but result in a loss of
     *            precision for strings.
     */
    public StatementRegistrar(StatementFactory factory, boolean useSingleAllocForGenEx,
                              boolean useSingleAllocPerThrowableType, boolean useSingleAllocForPrimitiveArrays,
                              boolean useSingleAllocForStrings) {
        this.methods = AnalysisUtil.createConcurrentHashMap();
        this.statementsForMethod = AnalysisUtil.createConcurrentHashMap();
        this.callSitesForMethod = AnalysisUtil.createConcurrentHashMap();
        this.ppToStmtMap = AnalysisUtil.createConcurrentHashMap();
        this.singletonReferenceVariables = AnalysisUtil.createConcurrentHashMap();
        this.handledLiterals = AnalysisUtil.createConcurrentSet();
        this.entryMethod = AnalysisUtil.getFakeRoot();
        this.entryMethodProgramPoints = AnalysisUtil.createConcurrentSet();
        this.stmtFactory = factory;
        this.useSingleAllocForGenEx = useSingleAllocForGenEx || useSingleAllocPerThrowableType;
        System.err.println("Singleton allocation site per generated exception type: " + this.useSingleAllocForGenEx);
        this.useSingleAllocForPrimitiveArrays = useSingleAllocForPrimitiveArrays;
        System.err.println("Singleton allocation site per primitive array type: " + useSingleAllocForPrimitiveArrays);
        this.useSingleAllocForStrings = useSingleAllocForStrings;
        System.err.println("Singleton allocation site for java.lang.String: " + useSingleAllocForStrings);
        this.useSingleAllocPerThrowableType = useSingleAllocPerThrowableType;
        System.err.println("Singleton allocation site per java.lang.Throwable subtype: "
                + useSingleAllocPerThrowableType);
    }

    /**
     * Handle all the instructions for a given method
     *
     * @param m method to register points-to statements for
     */
    public synchronized boolean registerMethod(IMethod m) {
        if (m.isAbstract()) {
            // Don't need to register abstract methods
            return false;
        }

        if (this.registeredMethods.add(m)) {
            try {
                // we need to register the method.

                IR ir = AnalysisUtil.getIR(m);
                if (ir == null) {
                    // Native method with no signature
                    assert m.isNative() : "No IR for non-native method: " + PrettyPrinter.methodString(m);
                    this.registerNative(m, this.rvFactory);
                    return true;
                }

                // First, run a dataflow to find the program points.
                ComputeProgramPointsDataflow df = new ComputeProgramPointsDataflow(ir, this, this.rvFactory);
                df.dataflow();

                TypeRepository types = new TypeRepository(ir);
                PrettyPrinter pprint = new PrettyPrinter(ir);

                MethodSummaryNodes methSumm = this.findOrCreateMethodSummary(m, this.rvFactory);

                // Add edges from formal summary nodes to the local variables representing the method parameters
                this.registerFormalAssignments(ir, methSumm.getEntryPP(), this.rvFactory, pprint);

                for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                    for (SSAInstruction ins : bb) {
                        if (ins.toString().contains("signatures/library/java/lang/String")
                                || ins.toString().contains("signatures/library/java/lang/AbstractStringBuilder")) {
                            System.err.println("\tWARNING: handling instruction mentioning String signature " + ins
                                    + " in " + m);
                        }
                        handleInstruction(ins, ir, bb, df, types, pprint);
                    }
                }

                // now try to remove duplicates
                Set<PointsToStatement> oldStatements = this.getStatementsForMethod(m);
                int oldSize = oldStatements.size();
                OrderedPair<Set<PointsToStatement>, VariableIndex> duplicateResults = RemoveDuplicateStatements.removeDuplicates(oldStatements);
                Set<PointsToStatement> newStatements = duplicateResults.fst();
                replacedVariableMap.put(m, duplicateResults.snd());
                int newSize = newStatements.size();

                removedStmts += (oldSize - newSize);
                this.statementsForMethod.put(m, newStatements);
                this.size += (newSize - oldSize);

                if (PointsToAnalysis.outputLevel >= 1) {
                    System.err.println("HANDLED: " + PrettyPrinter.methodString(m));
                    CFGWriter.writeToFile(ir);
                    System.err.println();
                }

                if (PointsToAnalysis.outputLevel >= 6) {
                    try (Writer writer = new StringWriter()) {
                        PrettyPrinter.writeIR(ir, writer, "\t", "\n");
                        System.err.print(writer.toString());
                    }
                    catch (IOException e) {
                        throw new RuntimeException();
                    }
                }

                int[] stats = df.cleanUpProgramPoints();
                removedProgramPoints += stats[1];
                totalProgramPoints += stats[0] - stats[1];

                for (PointsToStatement stmt : newStatements) {
                    if (stmt.programPoint() instanceof CallSiteProgramPoint) {
                        CallSiteProgramPoint pp = (CallSiteProgramPoint) stmt.programPoint();
                        assert !pp.isDiscarded();
                        Set<CallSiteProgramPoint> scpps = this.callSitesForMethod.get(m);
                        if (scpps == null) {
                            scpps = AnalysisUtil.createConcurrentSet();
                            Set<CallSiteProgramPoint> existing = this.callSitesForMethod.put(m, scpps);
                            if (existing != null) {
                                // someone else got there first.
                                scpps = existing;
                            }
                        }
                        scpps.add(pp);
                    }
                    for (ReferenceVariable def : stmt.getDefs()) {
                        if (def.hasLocalScope()) {
                            def.setLocalDef(stmt.programPoint());
                        }
                    }
                    for (ReferenceVariable use : stmt.getUses()) {
                        if (use != null && use.hasLocalScope()) {
                            use.addLocalUse(stmt.programPoint());
                        }
                    }
                }

                return true;
            }
            finally {
                // if we just registered the entry method, add the entry method program points
                if (m.equals(this.entryMethod)) {
                    ProgramPoint entryPP = getMethodSummary(m).getEntryPP();
                    for (ProgramPoint pp : this.entryMethodProgramPoints) {
                        pp.addSuccs(entryPP.succs());
                    }
                    entryPP.addSuccs(this.entryMethodProgramPoints);

                    this.entryMethodProgramPoints.clear();
                }
            }
        }
        return false;

    }

    /**
     * A listener that will get notified of newly created statements.
     */
    private StatementListener stmtListener = null;

    private static int removedStmts = 0;
    private static int removedProgramPoints = 0;
    private static int totalProgramPoints = 0;

    /**
     * Handle a particular instruction, this dispatches on the type of the instruction
     *
     * @param pp ProgramPoint to use for the statement(s) generated by i
     * @param ppForClassInit ProgramPoint to use for the class init statements, if any.
     * @param types
     * @param bb
     * @param ir
     * @param ins
     *
     * @param info information about the instruction to handle
     */
    protected void handleInstruction(SSAInstruction i, IR ir, ISSABasicBlock bb, ComputeProgramPointsDataflow df,
                                     TypeRepository types, PrettyPrinter printer) {
        assert i.getNumberOfDefs() <= 2 : "More than two defs in instruction: " + i;

        ProgramPoint pp = df.getProgramPoint(i, bb);
        // Add statements for any string literals in the instruction
        pp = this.findAndRegisterStringAndNullLiterals(i, ir, pp, this.rvFactory, printer);

        // Add statements for any JVM-generated exceptions this instruction could throw (e.g. NullPointerException)
        pp = this.findAndRegisterGeneratedExceptions(i, bb, ir, pp, this.rvFactory, types, printer, df);

        IClass reqInit = ClassInitFinder.getRequiredInitializedClasses(i);
        if (reqInit != null && !df.getInitializedClassesBeforeIns(i, bb).contains(reqInit)) {
            List<IMethod> inits = ClassInitFinder.getClassInitializersForClass(reqInit);
            if (!inits.isEmpty()) {
                // we are adding a class initializer statement.
                // Divide the pp.
                ProgramPoint newPP = pp.divide("class-init-branch");
                pp.addSucc(newPP);

                ProgramPoint ppInit = new ProgramPoint(pp.containingProcedure(), pp.getDebugInfo() + "-class-init");
                pp.addSucc(ppInit);
                ppInit.addSucc(newPP);

                this.registerClassInitializers(i, ppInit, inits);
                pp = newPP;
            }
        }

        InstructionType type = InstructionType.forInstruction(i);
        switch (type) {
        case ARRAY_LOAD:
            // x = v[i]
            this.registerArrayLoad((SSAArrayLoadInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case ARRAY_STORE:
            // v[i] = x
            this.registerArrayStore((SSAArrayStoreInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case CHECK_CAST:
            // v = (Type) x
            this.registerCheckCast((SSACheckCastInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case GET_FIELD:
            // v = o.f
            this.registerGetField((SSAGetInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case GET_STATIC:
            // v = ClassName.f
            this.registerGetStatic((SSAGetInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            // procedure calls, instance initializers
            SSAInvokeInstruction invocation = (SSAInvokeInstruction) i;
            CallSiteProgramPoint cspp = new CallSiteProgramPoint(ir.getMethod(), invocation.getCallSite());
            pp.divide(null, cspp);
            pp.addSucc(cspp);
            this.registerInvoke(invocation, bb, ir, cspp, this.rvFactory, types, printer, df);
            return;
        case LOAD_METADATA:
            // Reflection
            this.registerReflection((SSALoadMetadataInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case NEW_ARRAY:
            this.registerNewArray((SSANewInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case NEW_OBJECT:
            // v = new Foo();
            this.registerNewObject((SSANewInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case PHI:
            // v = phi(x_1,x_2)
            this.registerPhiAssignment((SSAPhiInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case PUT_FIELD:
            // o.f = v
            this.registerPutField((SSAPutInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case PUT_STATIC:
            // ClassName.f = v
            this.registerPutStatic((SSAPutInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case RETURN:
            // return v
            this.registerReturn((SSAReturnInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case THROW:
            // throw e
            this.registerThrow((SSAThrowInstruction) i, bb, ir, pp, this.rvFactory, types, printer, df);
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
    private void registerArrayLoad(SSAArrayLoadInstruction i, IR ir, ProgramPoint pp,
                                   ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pprint) {
        TypeReference arrayType = types.getType(i.getArrayRef());
        TypeReference baseType = arrayType.getArrayElementType();
        assert baseType.equals(types.getType(i.getDef()));
        if (baseType.isPrimitiveType()) {
            // Assigning to a primitive
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), baseType, ir.getMethod(), pprint);
        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getArrayRef(), arrayType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.arrayToLocal(v, a, baseType, pp));
    }

    /**
     * a[j] = v, store into an array
     */
    /**
     * @param i
     * @param ir
     * @param rvFactory
     * @param types
     * @param pprint
     */
    /**
     * @param i
     * @param ir
     * @param rvFactory
     * @param types
     * @param pprint
     */
    private void registerArrayStore(SSAArrayStoreInstruction i, IR ir, ProgramPoint pp,
                                    ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pprint) {
        TypeReference valueType = types.getType(i.getValue());
        if (valueType.isPrimitiveType()) {
            // Assigning from a primitive or assigning null (also no effect on points-to graph)
            return;
        }

        TypeReference arrayType = types.getType(i.getArrayRef());
        TypeReference baseType = arrayType.getArrayElementType();

        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getArrayRef(), arrayType, ir.getMethod(), pprint);
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getValue(), valueType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToArrayContents(a, v, baseType, pp, i));
    }

    /**
     * v2 = (TypeName) v1
     */
    private void registerCheckCast(SSACheckCastInstruction i, IR ir, ProgramPoint pp,
                                   ReferenceVariableFactory rvFactory,
                                   TypeRepository types, PrettyPrinter pprint) {
        TypeReference valType = types.getType(i.getVal());
        if (valType == TypeReference.Null) {
            // the cast value is null so no effect on pointer analysis
            // note that cast to/from primitives are a different instruction (SSAConversionInstruction)
            return;
        }

        // This has the same effect as a copy, v = x (except for the exception it could throw, handled elsewhere)
        ReferenceVariable v2 = rvFactory.getOrCreateLocal(i.getResult(),
                                                          i.getDeclaredResultTypes()[0],
                                                          ir.getMethod(),
                                                          pprint);
        ReferenceVariable v1 = rvFactory.getOrCreateLocal(i.getVal(), valType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToLocalFiltered(v2, v1, pp));
    }

    /**
     * v = o.f
     */
    private void registerGetField(SSAGetInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                  TypeRepository types,
                                  PrettyPrinter pprint) {
        TypeReference resultType = i.getDeclaredFieldType();
        // TODO If the class can't be found then WALA sets the type to object (why can't it be found?)
        assert resultType.getName().equals(types.getType(i.getDef()).getName())
                || types.getType(i.getDef()).equals(TypeReference.JavaLangObject);
        if (resultType.isPrimitiveType()) {
            // No pointers here
            return;
        }

        TypeReference receiverType = types.getType(i.getRef());

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);
        ReferenceVariable o = rvFactory.getOrCreateLocal(i.getRef(), receiverType, ir.getMethod(), pprint);
        FieldReference f = i.getDeclaredField();
        this.addStatement(stmtFactory.fieldToLocal(v, o, f, pp));
    }

    /**
     * v = ClassName.f
     */
    private void registerGetStatic(SSAGetInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                   TypeRepository types, PrettyPrinter pprint) {
        TypeReference resultType = i.getDeclaredFieldType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        if (resultType.isPrimitiveType()) {
            // No pointers here
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);
        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        this.addStatement(stmtFactory.staticFieldToLocal(v, f, pp));
    }

    /**
     * o.f = v
     */
    private void registerPutField(SSAPutInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                  TypeRepository types,
                                  PrettyPrinter pprint) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType()) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        TypeReference receiverType = types.getType(i.getRef());

        ReferenceVariable o = rvFactory.getOrCreateLocal(i.getRef(), valueType, ir.getMethod(), pprint);
        FieldReference f = i.getDeclaredField();
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), receiverType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToField(o, f, v, pp, i));
    }

    /**
     * ClassName.f = v
     */
    /**
     * @param i
     * @param ir
     * @param rvFactory
     * @param types
     * @param pprint
     */
    private void registerPutStatic(SSAPutInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                   TypeRepository types, PrettyPrinter pprint) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType()) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), valueType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToStaticField(f, v, pp, i));

    }

    /**
     * A virtual, static, special, or interface invocation
     *
     * @param bb
     */
    private void registerInvoke(SSAInvokeInstruction i, ISSABasicBlock bb, IR ir, CallSiteProgramPoint pp,
                                ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pprint,
                                ComputeProgramPointsDataflow df) {
        assert i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1;

        // //////////// Result ////////////

        ReferenceVariable result = null;
        if (i.getNumberOfReturnValues() > 0) {
            TypeReference returnType = types.getType(i.getReturnValue(0));
            if (!returnType.isPrimitiveType()) {
                result = rvFactory.getOrCreateLocal(i.getReturnValue(0), returnType, ir.getMethod(), pprint);
            }
        }

        // //////////// Actual arguments ////////////

        List<ReferenceVariable> actuals = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            TypeReference actualType = types.getType(i.getUse(j));
            if (actualType.isPrimitiveType()) {
                actuals.add(null);
            }
            else {
                actuals.add(rvFactory.getOrCreateLocal(i.getUse(j), actualType, ir.getMethod(), pprint));
            }
        }

        // //////////// Receiver ////////////

        // Get the receiver if it is not a static call
        // the second condition is used because sometimes the receiver is a null constant
        // This is usually due to something like <code>o = null; if (o != null) { o.foo(); }</code>,
        // note that the o.foo() is dead code
        // see SocketAdapter$SocketInputStream.read(ByteBuffer), the call to sk.cancel() near the end
        ReferenceVariable receiver = null;
        if (!i.isStatic() && !ir.getSymbolTable().isNullConstant(i.getReceiver())) {
            TypeReference receiverType = types.getType(i.getReceiver());
            receiver = rvFactory.getOrCreateLocal(i.getReceiver(), receiverType, ir.getMethod(), pprint);
        }

        if (PointsToAnalysis.outputLevel >= 2) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            if (resolvedMethods.isEmpty()) {
                System.err.println("No resolved methods for " + pprint.instructionString(i) + " method: "
                        + PrettyPrinter.methodString(i.getDeclaredTarget()) + " caller: "
                        + PrettyPrinter.methodString(ir.getMethod()));
            }
        }

        // //////////// Exceptions ////////////

        TypeReference exType = types.getType(i.getException());
        ReferenceVariable exception = rvFactory.getOrCreateLocal(i.getException(), exType, ir.getMethod(), pprint);
        this.registerThrownException(bb,
                                     ir,
                                     pp,
                                     exception,
                                     rvFactory,
                                     types,
                                     pprint,
                                     df,
                                     useSingleAllocPerThrowableType);

        // //////////// Resolve methods add statements ////////////

        if (i.isStatic()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            if (resolvedMethods.isEmpty()) {
                System.err.println("No method found for " + PrettyPrinter.methodString(i.getDeclaredTarget()));
                return;
            }
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee, rvFactory);
            this.addStatement(stmtFactory.staticCall(pp,
                                                     resolvedCallee,
                                                     result,
                                                     actuals,
                                                     exception,
                                                     calleeSummary));
        }
        else if (i.isSpecial()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i);
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee, rvFactory);
            this.addStatement(stmtFactory.specialCall(pp,
                                                      resolvedCallee,
                                                      result,
                                                      receiver,
                                                      actuals,
                                                      exception,
                                                      calleeSummary));
        }
        else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            if (ir.getSymbolTable().isNullConstant(i.getReceiver())) {
                // Similar to the check above sometimes the receiver is a null constant
                return;
            }
            this.addStatement(stmtFactory.virtualCall(pp,
                                                      i.getDeclaredTarget(),
                                                      result,
                                                      receiver,
                                                      actuals,
                                                      exception,
                                                      rvFactory));
        }
        else {
            throw new UnsupportedOperationException("Unhandled invocation code: " + i.getInvocationCode() + " for "
                    + PrettyPrinter.methodString(i.getDeclaredTarget()));
        }
    }

    /**
     * a = new TypeName[j][k][l]
     * <p>
     * Note that this is only the allocation not the initialization if there is any.
     */
    private void registerNewArray(SSANewInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                  TypeRepository types,
                                  PrettyPrinter pprint) {
        // all "new" instructions are assigned to a local
        TypeReference resultType = i.getConcreteType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);

        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        if (useSingleAllocForPrimitiveArrays && resultType.getArrayElementType().isPrimitiveType()) {
            ReferenceVariable rv = getOrCreateSingleton(resultType);
            this.addStatement(stmtFactory.localToLocal(a, rv, pp, false));
        }
        else {
            this.addStatement(stmtFactory.newForNormalAlloc(a, klass, pp, i.getNewSite().getProgramCounter()));
        }
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
            this.addStatement(stmtFactory.newForInnerArray(innerArray, arrayklass, pp));

            // Add field assign from the inner array to the array contents field of the outer array
            this.addStatement(stmtFactory.multidimensionalArrayContents(outerArray, innerArray, pp));

            // The array on the next iteration will be contents of this one
            outerArray = innerArray;
        }
    }

    /**
     * v = new TypeName
     * <p>
     * Handle an allocation of the form: "new Foo". Note that this is only the allocation not the constructor call.
     */
    private void registerNewObject(SSANewInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                   TypeRepository types, PrettyPrinter pprint) {
        // all "new" instructions are assigned to a local
        TypeReference resultType = i.getConcreteType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);

        TypeReference allocType = i.getNewSite().getDeclaredType();
        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(allocType);
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        if (useSingleAllocPerThrowableType
                && TypeRepository.isAssignableFrom(AnalysisUtil.getThrowableClass(), klass)) {
            // the newly allocated object is throwable, and we only want one allocation per throwable type
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, pp, false));

        }
        else if (useSingleAllocForStrings && TypeRepository.isAssignableFrom(AnalysisUtil.getStringClass(), klass)) {
            // the newly allocated object is a string, and we only want one allocation for strings
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, pp, false));

        }
        else {
            this.addStatement(stmtFactory.newForNormalAlloc(result, klass, pp, i.getNewSite()
                                                                                            .getProgramCounter()));
        }
    }

    /**
     * x = phi(x_1, x_2, ...)
     */
    private void registerPhiAssignment(SSAPhiInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                       TypeRepository types, PrettyPrinter pprint) {
        TypeReference phiType = types.getType(i.getDef());
        if (phiType.isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = rvFactory.getOrCreateLocal(i.getDef(), phiType, ir.getMethod(), pprint);
        List<ReferenceVariable> uses = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int arg = i.getUse(j);
            TypeReference argType = types.getType(arg);
            if (argType != TypeReference.Null) {
                assert !argType.isPrimitiveType() : "arg type: " + PrettyPrinter.typeString(argType)
                        + " for phi type: " + PrettyPrinter.typeString(phiType);

                ReferenceVariable x_i = rvFactory.getOrCreateLocal(arg, phiType, ir.getMethod(), pprint);
                uses.add(x_i);
            }
        }

        if (uses.isEmpty()) {
            // All entries to the phi are null literals, no effect on pointer analysis
            return;
        }
        this.addStatement(stmtFactory.phiToLocal(assignee, uses, pp));
    }

    /**
     * Load-metadata is used for reflective operations
     */
    @SuppressWarnings("unused")
    private void registerReflection(SSALoadMetadataInstruction i, IR ir, ProgramPoint pp,
                                    ReferenceVariableFactory rvFactory,
                                    TypeRepository types, PrettyPrinter pprint) {
        // TODO statement registrar not handling reflection yet
    }

    /**
     * return v
     */
    private void registerReturn(SSAReturnInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                TypeRepository types, PrettyPrinter pprint) {
        if (i.returnsVoid()) {
            // no pointers here
            return;
        }

        TypeReference valType = types.getType(i.getResult());
        if (valType.isPrimitiveType()) {
            // returning a primitive or "null"
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getResult(), valType, ir.getMethod(), pprint);
        ReferenceVariable summary = this.findOrCreateMethodSummary(ir.getMethod(), rvFactory).getReturn();
        this.addStatement(stmtFactory.returnStatement(v, summary, pp, i));
    }

    /**
     * throw v
     *
     * @param bb
     */
    private void registerThrow(SSAThrowInstruction i, ISSABasicBlock bb, IR ir, ProgramPoint pp,
                               ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pprint,
                               ComputeProgramPointsDataflow df) {
        TypeReference throwType = types.getType(i.getException());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getException(), throwType, ir.getMethod(), pprint);
        this.registerThrownException(bb, ir, pp, v, rvFactory, types, pprint, df, useSingleAllocPerThrowableType);
    }

    /**
     * Get the method summary nodes for the given method, create if necessary
     *
     * @param method method to get summary nodes for
     * @param rvFactory factory for creating new reference variables (if necessary)
     */
    public MethodSummaryNodes findOrCreateMethodSummary(IMethod method, ReferenceVariableFactory rvFactory) {
        MethodSummaryNodes msn = this.methods.get(method);
        if (msn == null) {
            msn = new MethodSummaryNodes(method, rvFactory);
            MethodSummaryNodes ex = this.methods.putIfAbsent(method, msn);
            if (ex != null) {
                msn = ex;
            }
        }
        return msn;
    }

    /*
     * Get the method summary nodes for the given method, return null if not found
     */
    public MethodSummaryNodes getMethodSummary(IMethod method) {
        return this.methods.get(method);
    }

    /*
     * Get the statement at a program point, return null is not found
     */
    public PointsToStatement getStmtAtPP(ProgramPoint pp) {
        return this.ppToStmtMap.get(pp);
    }

    /**
     * Get all methods that should be analyzed in the initial empty context
     *
     * @return set of methods
     */
    public Set<IMethod> getInitialContextMethods() {
        Set<IMethod> ret = new LinkedHashSet<>();
        ret.add(this.entryMethod);
        return ret;
    }

    /**
     * If this is a static or special call then we know statically what the target of the call is and can therefore
     * resolve the method statically. If it is virtual then we need to add statements for all possible run time method
     * resolutions but may only analyze some of these depending on what the pointer analysis gives for the receiver
     * type.
     *
     * @param inv method invocation to resolve methods for
     * @return Set of methods the invocation could call
     */
    static Set<IMethod> resolveMethodsForInvocation(SSAInvokeInstruction inv) {
        Set<IMethod> targets = null;
        if (inv.isStatic()) {
            IMethod resolvedMethod = AnalysisUtil.getClassHierarchy().resolveMethod(inv.getDeclaredTarget());
            if (resolvedMethod != null) {
                targets = Collections.singleton(resolvedMethod);
            }
        }
        else if (inv.isSpecial()) {
            IMethod resolvedMethod = AnalysisUtil.getClassHierarchy().resolveMethod(inv.getDeclaredTarget());
            if (resolvedMethod != null) {
                targets = Collections.singleton(resolvedMethod);
            }
        }
        else if (inv.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                || inv.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            targets = AnalysisUtil.getClassHierarchy().getPossibleTargets(inv.getDeclaredTarget());
        }
        else {
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
     * @param s statement to add
     */
    protected void addStatement(PointsToStatement s) {
        IMethod m = s.getMethod();
        Set<PointsToStatement> ss = this.statementsForMethod.get(m);
        if (ss == null) {
            ss = AnalysisUtil.createConcurrentSet();
            Set<PointsToStatement> ex = this.statementsForMethod.putIfAbsent(m, ss);
            if (ex != null) {
                ss = ex;
            }
        }
        assert !ss.contains(s) : "STATEMENT: " + s + " was already added";
        if (ss.add(s)) {
            // System.err.println("NEW STATEMENT:" + s);
            this.size++;
        }
        if (stmtListener != null) {
            // let the listener now a statement has been added.
            stmtListener.newStatement(s);
        }

        // handle the mapping for program points
        if (s.mayChangeFlowSensPointsToGraph()) {
            ProgramPoint pp = s.programPoint();
            PointsToStatement existing = ppToStmtMap.putIfAbsent(pp, s);
            assert (existing == null) : "More than one statement that may modify the points to graph at a program point: existing is '"
                    + existing + "' and just tried to add '" + s + "'";
        }
        else {
            // it shouldn't matter what program point we use for the statement
            s.setProgramPoint(getMethodSummary(m).getEntryPP());
        }

        if ((this.size + StatementRegistrar.removedStmts) % 100000 == 0) {
            reportStats();
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

    public void reportStats() {
        System.err.println("REGISTERED statements: " + (this.size + StatementRegistrar.removedStmts) + ", removed: "
                + StatementRegistrar.removedStmts + ", effective: " + this.size + "\n           program points:  "
                + (totalProgramPoints() + totalProgramPointsRemoved()) + ", removed: " + totalProgramPointsRemoved()
                + ", effective: " + this.totalProgramPoints() + ", with stmt that may modify graph: "
                + this.ppToStmtMap.size());

    }

    /**
     * Get the number of statements in the registrar
     *
     * @return number of registered statements
     */
    public int size() {
        int total = 0;
        for (IMethod m : this.statementsForMethod.keySet()) {
            total += this.statementsForMethod.get(m).size();
        }
        this.size = total;
        return total;
    }

    /**
     * Get all the statements for a particular method
     *
     * @param m method to get the statements for
     * @return set of points-to statements for <code>m</code>
     */
    public Set<PointsToStatement> getStatementsForMethod(IMethod m) {
        Set<PointsToStatement> ret = this.statementsForMethod.get(m);
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
        return this.statementsForMethod.keySet();
    }

    /**
     * Look for String and null literals in the instruction and create allocation sites for them
     *
     * @param i instruction to create string literals for
     * @param ir code containing the instruction
     * @param stringClass WALA representation of the java.lang.String class
     */
    private ProgramPoint findAndRegisterStringAndNullLiterals(SSAInstruction i, IR ir, ProgramPoint pp,
                                               ReferenceVariableFactory rvFactory,
                                               PrettyPrinter pprint) {
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int use = i.getUse(j);
            if (ir.getSymbolTable().isStringConstant(use)) {
                ReferenceVariable newStringLit = rvFactory.getOrCreateLocal(use,
                                                                            TypeReference.JavaLangString,
                                                                            ir.getMethod(),
                                                                            pprint);
                if (!this.handledLiterals.add(newStringLit)) {
                    // Already handled this allocation
                    continue;
                }

                // The fake root method always allocates a String so the clinit has already been called, even if we are
                // flow sensitive

                // add points to statements to simulate the allocation
                pp = this.registerStringLiteral(newStringLit, use, pp, pprint);
            }
            else if (ir.getSymbolTable().isNullConstant(use)) {
                ReferenceVariable newNullLit = rvFactory.getOrCreateLocal(use,
                                                                          TypeReference.JavaLangString,
                                                                          ir.getMethod(),
                                                                          pprint);
                if (!this.handledLiterals.add(newNullLit)) {
                    // Already handled this allocation
                    continue;
                }

                // add points to statements to simulate the allocation
                ProgramPoint newPP = pp.divide("null-lit");
                pp.addSucc(newPP);
                this.addStatement(stmtFactory.nullToLocal(newNullLit, pp));
                pp = newPP;

            }
        }
        return pp;

    }

    /**
     * Add points-to statements for a String constant
     *
     * @param stringLit reference variable for the string literal being handled
     * @param local local variable value number for the literal
     * @param ProgramPoint where the literal is created
     */
    private ProgramPoint registerStringLiteral(ReferenceVariable stringLit, int local, ProgramPoint pp,
                                               PrettyPrinter pprint) {
        if (useSingleAllocForStrings) {
            // v = string
            ReferenceVariable rv = getOrCreateSingleton(AnalysisUtil.getStringClass().getReference());
            ProgramPoint newPP = pp.divide("string-lit-alloc");
            pp.addSucc(newPP);
            this.addStatement(stmtFactory.localToLocal(stringLit, rv, pp, false));
            pp = newPP;
        }
        else {
            // v = new String
            ProgramPoint newPP = pp.divide("string-lit-alloc");
            pp.addSucc(newPP);
            this.addStatement(stmtFactory.newForStringLiteral(pprint.valString(local), stringLit, pp));
            pp = newPP;

            for (IField f : AnalysisUtil.getStringClass().getAllFields()) {
                if (f.getName().toString().equals("value")) {
                    // This is the value field of the String
                    ReferenceVariable stringValue = ReferenceVariableFactory.createStringLitField();

                    newPP = pp.divide("string-lit-alloc");
                    pp.addSucc(newPP);
                    this.addStatement(stmtFactory.newForStringField(stringValue, pp));
                    pp = newPP;

                    newPP = pp.divide("string-lit");
                    pp.addSucc(newPP);
                    this.addStatement(new LocalToFieldStatement(stringLit, f.getReference(), stringValue, pp));
                    pp = newPP;
                }
            }
        }
        return pp;
    }

    /**
     * Add points-to statements for any generated exceptions thrown by the given instruction
     *
     * @param i instruction that may throw generated exceptions
     * @param bb
     * @param ir code containing the instruction
     * @param rvFactory factory for creating new reference variables
     */
    @SuppressWarnings("unused")
    private final ProgramPoint findAndRegisterGeneratedExceptions(SSAInstruction i, ISSABasicBlock bb, IR ir,
                                                                  ProgramPoint pp,
                                                          ReferenceVariableFactory rvFactory, TypeRepository types,
 PrettyPrinter pprint,
                                                                  ComputeProgramPointsDataflow df) {
        for (TypeReference exType : PreciseExceptionResults.implicitExceptions(i)) {
            ReferenceVariable ex;
            boolean useSingleAlloc = useSingleAllocForGenEx;
            if (useSingleAlloc) {
                ex = getOrCreateSingleton(exType);
            }
            else {
                ex = rvFactory.createImplicitExceptionNode(exType, bb.getNumber(), ir.getMethod());

                IClass exClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
                assert exClass != null : "No class found for " + PrettyPrinter.typeString(exType);

                ProgramPoint newPP = pp.divide("gen-ex");
                pp.addSucc(newPP);
                this.addStatement(stmtFactory.newForGeneratedException(ex, exClass, newPP));
                pp = newPP;
            }
            ProgramPoint newPP = pp.divide("throw-ex");
            pp.addSucc(newPP);
            this.registerThrownException(bb, ir, newPP, ex, rvFactory, types, pprint, df, useSingleAlloc);
            pp = newPP;
        }
        return pp;
    }

    /**
     * Get or create a singleton reference variable based on the type.
     *
     * @param varType type we want a singleton for
     */
    private ReferenceVariable getOrCreateSingleton(TypeReference varType) {
        ReferenceVariable rv = this.singletonReferenceVariables.get(varType);
        if (rv == null) {
            rv = rvFactory.createSingletonReferenceVariable(varType);
            ReferenceVariable existing = this.singletonReferenceVariables.putIfAbsent(varType, rv);
            if (existing != null) {
                rv = existing;
            }

            IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(varType);
            assert klass != null : "No class found for " + PrettyPrinter.typeString(varType);

            // We pretend that the allocation for this object occurs in the entry point of the entire program
            ProgramPoint pp = new ProgramPoint(getEntryPoint(), "EntryMethod-pp-" + klass);
            addEntryMethodProgramPoint(pp);
            NewStatement stmt = stmtFactory.newForGeneratedObject(rv, klass, pp, PrettyPrinter.typeString(varType));



            this.addStatement(stmt);

        }
        return rv;
    }

    /**
     * Add a program point to the entry method.
     *
     * @param pp
     */
    private void addEntryMethodProgramPoint(ProgramPoint pp) {
        // get the entry method entry program point
        ProgramPoint entryPP = getMethodSummary(this.entryMethod).getEntryPP();
        if (entryPP.succs().isEmpty()) {
            // we haven't processed the entry method yet
            this.entryMethodProgramPoints.add(pp);
        }
        else {
            // add all of the succs of entryPP as succs of pp
            // and add pp as a succ to entryPP.
            // This is not great (quadratic-sized structure!) and
            // we should make it more efficient sometime.
            pp.addSuccs(entryPP.succs());
            entryPP.addSucc(pp);
        }
    }

    /**
     * Add an assignment from the a thrown exception to any catch block or exit block exception that exception could
     * reach
     *
     * @param bb Basic block containing the instruction that throws the exception
     * @param ir code containing the instruction that throws
     * @param pp Program point of the instruction that throws
     * @param thrown reference variable representing the value of the exception
     * @param types type information about local variables
     * @param pp pretty printer for the appropriate method
     */
    private final void registerThrownException(ISSABasicBlock bb, IR ir, ProgramPoint pp, ReferenceVariable thrown,
                                               ReferenceVariableFactory rvFactory, TypeRepository types,
                                               PrettyPrinter pprint, ComputeProgramPointsDataflow df, boolean useSingletonAllocForThisException) {

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
                    caught = rvFactory.getOrCreateLocal(catchIns.getException(), caughtType, ir.getMethod(), pprint);
                    caught.setLocalDef(df.getProgramPoint(catchIns, succ));
                    this.addStatement(stmtFactory.exceptionAssignment(thrown, caught, notType, pp, false, useSingletonAllocForThisException));
                }

                // if we have definitely caught the exception, no need to add more exception assignment statements.
                if (definitelyCaught) {
                    break;
                }

                // Add this exception to the set of types that have already been caught
                notType.add(AnalysisUtil.getClassHierarchy().lookupClass(caughtType));
            }
            else {
                assert succ.isExitBlock() : "Exceptional successor should be catch block or exit block.";
                // TODO do not propagate java.lang.Errors out of this class, this is possibly unsound
                // TODO uncomment to not propagate errors notType.add(AnalysisUtil.getErrorClass());
                caught = this.findOrCreateMethodSummary(ir.getMethod(), rvFactory).getException();
                this.addStatement(stmtFactory.exceptionAssignment(thrown, caught, notType, pp, true, useSingletonAllocForThisException));
            }
        }
    }

    /**
     * Add points-to statements for the given list of class initializers
     *
     * @param trigger instruction that triggered the class init
     * @param containingCode code containing the instruction that triggered
     * @param clinits class initialization methods that might need to be called in the order they need to be called
     *            (i.e. element j is a super class of element j+1)
     */
    void registerClassInitializers(SSAInstruction trigger, ProgramPoint pp, List<IMethod> clinits) {
        this.addStatement(stmtFactory.classInit(clinits, pp, trigger));
    }

    /**
     * Add statements for the generated allocation of an exception or return object of a given type for a native method
     * with no signature.
     *
     * @param m native method
     * @param type allocated type
     * @param summary summary reference variable for method exception or return
     */
    private void registerAllocationForNative(IMethod m, ProgramPoint pp, TypeReference type,
                                             ReferenceVariable summary) {
        IClass allocatedClass = AnalysisUtil.getClassHierarchy().lookupClass(type);
        this.addStatement(stmtFactory.newForNative(summary, allocatedClass, m, pp));
    }

    private void registerNative(IMethod m, ReferenceVariableFactory rvFactory) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(m, rvFactory);
        int ppCount = 0;
        ProgramPoint entryPP = methodSummary.getEntryPP();
        if (!m.getReturnType().isPrimitiveType()) {
            // Allocation of return value
            ProgramPoint pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)));
            this.registerAllocationForNative(m, pp, m.getReturnType(), methodSummary.getReturn());
            pp.addSucc(methodSummary.getNormalExitPP());
        }

        boolean containsRTE = false;
        try {
            TypeReference[] exceptions = m.getDeclaredExceptions();
            if (exceptions != null) {
                for (TypeReference exType : exceptions) {
                    // Allocation of exception of a particular type
                    ReferenceVariable ex = ReferenceVariableFactory.createNativeException(exType, m);
                    ProgramPoint pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)));
                    this.registerAllocationForNative(m, pp, exType, ex);
                    pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)));
                    this.addStatement(stmtFactory.exceptionAssignment(ex,
                                                                      methodSummary.getException(),
                                                                      Collections.<IClass> emptySet(),
                                                                      pp,
                                                                      true,
                                                                      useSingleAllocPerThrowableType));

                    containsRTE |= exType.equals(TypeReference.JavaLangRuntimeException);
                    pp.addSucc(methodSummary.getExceptionExitPP());
                }
            }
        }
        catch (UnsupportedOperationException | InvalidClassFileException e) {
            throw new RuntimeException(e);
        }
        // All methods can throw a RuntimeException

        // TODO the types for all native generated exceptions are imprecise, might want to mark them as such
        // e.g. if the actual native method would throw a NullPointerException and NullPointerException is caught in the
        // caller, but a node is only created for a RunTimeException then the catch block will be bypassed
        if (!containsRTE) {
            ReferenceVariable ex = ReferenceVariableFactory.createNativeException(TypeReference.JavaLangRuntimeException,
                                                                                  m);
            ProgramPoint pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)));
            this.registerAllocationForNative(m,
                                             pp,
                                             TypeReference.JavaLangRuntimeException,
                                             methodSummary.getException());
            pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)));
            this.addStatement(stmtFactory.exceptionAssignment(ex,
                                                              methodSummary.getException(),
                                                              Collections.<IClass> emptySet(),
                                                              pp,
                                                              true,
                                                              useSingleAllocPerThrowableType));
        }

        // connect the entry and the exit with some kind of program point.

    }

    private ProgramPoint nextProgramPoint(ProgramPoint currPP, ProgramPoint nextPP) {
        currPP.addSucc(nextPP);
        return nextPP;
    }

    private void registerFormalAssignments(IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                           PrettyPrinter pprint) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(ir.getMethod(), rvFactory);
        for (int i = 0; i < ir.getNumberOfParameters(); i++) {
            TypeReference paramType = ir.getParameterType(i);
            if (paramType.isPrimitiveType()) {
                // No statements for primitives
                continue;
            }
            int paramNum = ir.getParameter(i);
            ReferenceVariable param = rvFactory.getOrCreateLocal(paramNum, paramType, ir.getMethod(), pprint);
            this.addStatement(stmtFactory.localToLocal(param, methodSummary.getFormal(i), pp, true));
        }
    }

    /**
     * Map from local variable to reference variable. This is not complete until the statement registration pass has
     * completed.
     *
     * @return map from local variable to unique reference variable
     */
    public ReferenceVariableCache getAllLocals() {
        return this.rvFactory.getAllLocals(replacedVariableMap);
    }

    /**
     * Instruction together with information about the containing code
     */
    public static final class InstructionInfo {
        public final SSAInstruction instruction;
        public final IR ir;
        public final ISSABasicBlock basicBlock;
        public final TypeRepository typeRepository;
        public final PrettyPrinter prettyPrinter;

        /**
         * Instruction together with information about the containing code
         *
         * @param i instruction
         * @param ir containing code
         * @param bb containing basic block
         * @param types results of type inference for the method
         * @param pprint Pretty printer for local variables and instructions in enclosing method
         */
        public InstructionInfo(SSAInstruction i, IR ir, ISSABasicBlock bb, TypeRepository types, PrettyPrinter pprint) {
            assert i != null;
            assert ir != null;
            assert types != null;
            assert pprint != null;
            assert bb != null;

            this.instruction = i;
            this.ir = ir;
            this.typeRepository = types;
            this.prettyPrinter = pprint;
            this.basicBlock = bb;
        }

        @Override
        public int hashCode() {
            return this.instruction.hashCode();
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
            InstructionInfo other = (InstructionInfo) obj;
            return this.instruction.equals(other.instruction);
        }
    }

    public interface StatementListener {
        /**
         * Called when a new statement is added to the registrar.
         *
         * @param stmt
         */
        void newStatement(PointsToStatement stmt);
    }

    public void setStatementListener(StatementListener stmtListener) {
        this.stmtListener = stmtListener;
    }

    public IMethod getEntryPoint() {
        return this.entryMethod;
    }

    public int totalProgramPoints() {
        return totalProgramPoints;
    }

    public int totalProgramPointsRemoved() {
        return removedProgramPoints;
    }

    public boolean shouldUseSingleAllocForGenEx() {
        return useSingleAllocForGenEx;
    }

    public Set<CallSiteProgramPoint> getCallSitesForMethod(IMethod m) {
        Set<CallSiteProgramPoint> s = this.callSitesForMethod.get(m);
        if (s == null) {
            return Collections.emptySet();
        }
        return s;
    }

    /**
     * Write program-point successor graph to file.
     */
    public void dumpProgramPointSuccGraphToFile(String filename) {
        String file = filename;
        String fullFilename = file + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpProgramPointSuccGraph(out);
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    public Writer dumpProgramPointSuccGraph(Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]" + ";\n");

        Set<ProgramPoint> visited = new HashSet<>();
        for (MethodSummaryNodes methSum : methods.values()) {
            writeSucc(methSum.getEntryPP(), writer, visited);
        }

        writer.write("};\n");
        return writer;
    }

    private void writeSucc(ProgramPoint pp, Writer writer, Set<ProgramPoint> visited) throws IOException {
        if (!visited.contains(pp)) {
            visited.add(pp);
            for (ProgramPoint succ : pp.succs()) {
                String fromStr = escape(pp + " : ((((" + getStmtAtPP(pp) + "))))");
                String toStr = escape(succ + " : ((((" + getStmtAtPP(succ) + "))))");
                writer.write("\t\"" + fromStr + "\" -> \"" + toStr + "\";\n");
                writeSucc(succ, writer, visited);
            }
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
