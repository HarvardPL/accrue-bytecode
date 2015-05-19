package analysis.pointer.registrar;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import signatures.Signatures;
import types.TypeRepository;
import util.InstructionType;
import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.StringAndReflectiveUtil;
import analysis.dataflow.flowsensitizer.EscapedStringBuilderVariable;
import analysis.dataflow.flowsensitizer.StringBuilderFlowSensitizer;
import analysis.dataflow.flowsensitizer.StringBuilderFlowSensitizer.Solution;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.duplicates.RemoveDuplicateStatements;
import analysis.pointer.duplicates.RemoveDuplicateStatements.VariableIndex;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.strings.StringLikeVariable;
import analysis.pointer.statements.CallStatement;
import analysis.pointer.statements.ForNameCallStatement;
import analysis.pointer.statements.GetPropertyStatement;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.NewStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StatementFactory;
import analysis.pointer.statements.StringLiteralStatement;
import analysis.pointer.statements.StringStatement;

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
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * This class manages the registration of new points-to graph statements, which are then processed by the pointer
 * analysis
 */
public class StatementRegistrar {

    /**
     * Map from method signature to nodes representing formals and returns
     */
    private final Map<IMethod, MethodSummaryNodes> methods;
    /**
     * Map from method signature to nodes representing string formals and returns
     */
    private final Map<IMethod, MethodStringSummary> methodStringSummaries;
    /**
     * Entry point for the code being analyzed
     */
    private final IMethod entryPoint;
    /**
     * Map from method to the points-to statements generated from instructions in that method
     */
    private final Map<IMethod, Set<PointsToStatement>> statementsForMethod;
    private final Map<IMethod, Set<StringStatement>> stringStatementsForMethod;

    /**
     * The total number of statements
     */
    private int size;

    /**
     * String literals that new allocation sites have already been created for
     */
    private final Set<ReferenceVariable> handledStringLit;
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
     * If true then only one allocation will be made for any immutable wrapper class. This will reduce the size of the
     * points-to graph (and speed up the points-to analysis), but result in a loss of precision for these classes.
     * <p>
     * These classes are java.lang.String, all the primitive wrappers, and BigInteger and BigDecimal if they are not
     * subclassed.
     */
    private final boolean useSingleAllocForImmutableWrappers;

    private final boolean useSingleAllocForSwing;
    /**
     * Whether to use a signature that allocates the return type when there is no other signature
     */
    private final boolean useDefaultSignatures;

    /**
     * If the above is true and only one allocation will be made for each generated exception type. This map holds that
     * node
     */
    private final Map<TypeReference, ReferenceVariable> singletonReferenceVariables;

    /**
     * Create a single copy of java.lang.Class per type
     */
    private final Map<TypeReference, ReferenceVariable> classReferenceVariables;

    /**
     * Methods we have already added statements for
     */
    private final Set<IMethod> registeredMethods = new LinkedHashSet<>();
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

    private final Map<IMethod, FlowSensitiveStringLikeVariableFactory> stringVariableFactoryMap = new HashMap<>();

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
     * @param useSingleAllocForImmutableWrappers If true then only one allocation will be made for any immutable wrapper
     *            class. This will reduce the size of the points-to graph (and speed up the points-to analysis), but
     *            result in a loss of precision for these classes.
     * @param useSingleAllocForSwing If true then only one allocation will be made for any class in the java Swing API.
     *            This will reduce the size of the points-to graph (and speed up the points-to analysis), but result in
     *            a loss of precision for these classes.
     * @param useDefaultNativeSignatures Whether to use a signature that allocates the return type when there is no
     *            other signature
     */
    public StatementRegistrar(StatementFactory factory, boolean useSingleAllocForGenEx,
                              boolean useSingleAllocPerThrowableType, boolean useSingleAllocForPrimitiveArrays,
                              boolean useSingleAllocForStrings, boolean useSingleAllocForImmutableWrappers,
                              boolean useSingleAllocForSwing,
                              boolean useDefaultNativeSignatures) {
        this.methods = new LinkedHashMap<>();
        this.statementsForMethod = new LinkedHashMap<>();
        this.singletonReferenceVariables = new LinkedHashMap<>();
        this.classReferenceVariables = new LinkedHashMap<>();
        this.handledStringLit = new LinkedHashSet<>();
        this.stringStatementsForMethod = AnalysisUtil.createConcurrentHashMap();
        this.entryPoint = AnalysisUtil.getFakeRoot();
        this.stmtFactory = factory;
        this.useDefaultSignatures = useDefaultNativeSignatures;
        this.useSingleAllocForGenEx = useSingleAllocForGenEx || useSingleAllocPerThrowableType;
        this.methodStringSummaries = AnalysisUtil.createConcurrentHashMap();
        System.err.println("Singleton allocation site per generated exception type: " + this.useSingleAllocForGenEx);
        this.useSingleAllocForPrimitiveArrays = useSingleAllocForPrimitiveArrays;
        System.err.println("Singleton allocation site per primitive array type: "
                + this.useSingleAllocForPrimitiveArrays);
        this.useSingleAllocForStrings = useSingleAllocForStrings || useSingleAllocForImmutableWrappers;
        System.err.println("Singleton allocation site for java.lang.String: " + this.useSingleAllocForStrings);
        this.useSingleAllocPerThrowableType = useSingleAllocPerThrowableType;
        System.err.println("Singleton allocation site per java.lang.Throwable subtype: "
                + this.useSingleAllocPerThrowableType);
        this.useSingleAllocForImmutableWrappers = useSingleAllocForImmutableWrappers;
        System.err.println("Singleton allocation site per immutable wrapper type: "
                + this.useSingleAllocForImmutableWrappers);
        this.useSingleAllocForSwing = useSingleAllocForSwing;
        System.err.println("Singleton allocation site per Swing library type: " + this.useSingleAllocForSwing);
    }

    /**
     * Handle all the instructions for a given method
     *
     * @param m method to register points-to statements for
     * @return was a new method registered? (only true for non-abstract, not-yet-seen methods)
     */
    public synchronized boolean registerMethod(IMethod m) {
        if (m.isAbstract()) {
            // Don't need to register abstract methods
            return false;
        }

        if (this.registeredMethods.add(m)) {
            // we need to register the method.

            IR ir = AnalysisUtil.getIR(m);
            if (ir == null) {
                // Native method with no signature
                assert m.isNative() : "No IR for non-native method: " + PrettyPrinter.methodString(m);
                this.registerNative(m);
                return true;
            }

            TypeRepository types = new TypeRepository(ir);
            PrettyPrinter pp = new PrettyPrinter(ir);

            FlowSensitiveStringLikeVariableFactory stringVariableFactory = getOrCreateStringVariableFactory(m);

            // Add edges from formal summary nodes to the local variables representing the method parameters
            this.registerFormalAssignments(ir, this.rvFactory, pp);

            for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                for (SSAInstruction ins : bb) {
                    if (ins.toString().contains("signatures/library/java/lang/String")
                            || ins.toString().contains("signatures/library/java/lang/AbstractStringBuilder")) {
                        System.err.println("\tWARNING: handling instruction mentioning String signature " + ins
                                + " in " + m);
                    }
                    handleInstruction(ins, ir, bb, types, stringVariableFactory, pp);
                }
            }

            // now try to remove duplicates
            Set<PointsToStatement> oldStatements = this.getStatementsForMethod(m);
            int oldSize = oldStatements.size();
            OrderedPair<Set<PointsToStatement>, VariableIndex> duplicateResults = RemoveDuplicateStatements.removeDuplicates(oldStatements);
            Set<PointsToStatement> newStatements = duplicateResults.fst();
            replacedVariableMap.put(m, duplicateResults.snd());
            int newSize = newStatements.size();

            removed += (oldSize - newSize);
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
            return true;
        }
        return false;

    }

    private FlowSensitiveStringLikeVariableFactory getOrCreateStringVariableFactory(IMethod m) {
        // XXX: Not thread-safe
        FlowSensitiveStringLikeVariableFactory v = stringVariableFactoryMap.get(m);
        if (v == null) {
            TypeRepository types = new TypeRepository(AnalysisUtil.getIR(m));
            Solution sensitizerSolution = StringBuilderFlowSensitizer.analyze(m, types);
            FlowSensitiveStringLikeVariableFactory stringVariableFactory = FlowSensitiveStringLikeVariableFactory.make(m,
                                                                                                                       types,
                                                                                                                       sensitizerSolution.getDefRelation(),
                                                                                                                       sensitizerSolution.getUseRelation());
            stringVariableFactoryMap.put(m, stringVariableFactory);
            return stringVariableFactory;
        }
        else {
            return v;
        }
    }

    /**
     * Total number of statements that are removed when duplicate statements are removed
     */
    private static int removed = 0;

    /**
     * Handle a particular instruction, this dispatches on the type of the instruction
     *
     * @param stringVariableFactory handles creation of string variables
     *
     * @param pp
     * @param types2
     * @param bb2
     * @param ir2
     * @param ins
     *
     * @param info information about the instruction to handle
     */
    protected void handleInstruction(SSAInstruction i, IR ir, ISSABasicBlock bb, TypeRepository types,
                                     FlowSensitiveStringLikeVariableFactory stringVariableFactory, PrettyPrinter printer) {
        assert i.getNumberOfDefs() <= 2 : "More than two defs in instruction: " + i;

        // Add statements for any string literals in the instruction
        this.findAndRegisterStringLiterals(i, ir, this.rvFactory, stringVariableFactory, types, printer);

        // Add statements for any JVM-generated exceptions this instruction could throw (e.g. NullPointerException)
        this.findAndRegisterGeneratedExceptions(i, bb, ir, this.rvFactory, types, printer);

        List<IMethod> inits = ClassInitFinder.getClassInitializers(i);
        if (!inits.isEmpty()) {
            this.registerClassInitializers(i, ir, inits);
        }

        InstructionType type = InstructionType.forInstruction(i);
        switch (type) {
        case ARRAY_LOAD:
            // x = v[i]
            this.registerArrayLoad((SSAArrayLoadInstruction) i, ir, this.rvFactory, types, printer);
            return;
        case ARRAY_STORE:
            // v[i] = x
            this.registerArrayStore((SSAArrayStoreInstruction) i, ir, this.rvFactory, types, printer);
            return;
        case CHECK_CAST:
            // v = (Type) x
            this.registerCheckCast((SSACheckCastInstruction) i, ir, this.rvFactory,
            // XXX: Should I send this method a `stringVariableFactory`?
                                   types,
                                   printer);
            return;
        case GET_FIELD:
            // v = o.f
            this.registerGetField((SSAGetInstruction) i, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case GET_STATIC:
            // v = ClassName.f
            this.registerGetStatic((SSAGetInstruction) i, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            // procedure calls, instance initializers
            this.registerInvoke((SSAInvokeInstruction) i, bb, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case LOAD_METADATA:
            // Reflection
            this.registerLoadMetadata((SSALoadMetadataInstruction) i, ir, this.rvFactory, printer);
            return;
        case NEW_ARRAY:
            this.registerNewArray((SSANewInstruction) i, ir, this.rvFactory, types, printer);
            return;
        case NEW_OBJECT:
            // v = new Foo();
            this.registerNewObject((SSANewInstruction) i, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case PHI:
            // v = phi(x_1,x_2)
            this.registerPhiAssignment((SSAPhiInstruction) i, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case PUT_FIELD:
            // o.f = v
            this.registerPutField((SSAPutInstruction) i, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case PUT_STATIC:
            // ClassName.f = v
            this.registerPutStatic((SSAPutInstruction) i, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case RETURN:
            // return v
            this.registerReturn((SSAReturnInstruction) i, ir, this.rvFactory, stringVariableFactory, types, printer);
            return;
        case THROW:
            // throw e
            this.registerThrow((SSAThrowInstruction) i, bb, ir, this.rvFactory, types, printer);
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
        this.addStatement(stmtFactory.arrayToLocal(v, a, ir.getMethod()));
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

        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getArrayRef(), arrayType, ir.getMethod(), pp);
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getValue(), valueType, ir.getMethod(), pp);
        this.addStatement(stmtFactory.localToArrayContents(a, v, ir.getMethod(), i));
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
        ReferenceVariable v2 = rvFactory.getOrCreateLocal(i.getResult(),
                                                          i.getDeclaredResultTypes()[0],
                                                          ir.getMethod(),
                                                          pp);
        ReferenceVariable v1 = rvFactory.getOrCreateLocal(i.getVal(), valType, ir.getMethod(), pp);
        this.addStatement(stmtFactory.localToLocalFiltered(v2, v1, ir.getMethod()));
    }

    /**
     * v = o.f
     *
     * @param stringVariableFactory
     */
    private void registerGetField(SSAGetInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                  FlowSensitiveStringLikeVariableFactory stringVariableFactory, TypeRepository types,
                                  PrettyPrinter pp) {
        TypeReference resultType = i.getDeclaredFieldType();
        // TODO If the class can't be found then WALA sets the type to object (why can't it be found?)
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
        this.addStatement(stmtFactory.fieldToLocal(v, o, f, ir.getMethod()));

        if (StringAndReflectiveUtil.isStringBuilderType(resultType)) {
            // it escaped
        }
        else if (StringAndReflectiveUtil.isStringType(resultType)) {
            StringLikeVariable svv = stringVariableFactory.getOrCreateLocalDef(i, i.getDef());
            this.addStringStatement(stmtFactory.fieldToLocalString(svv, o, f, ir.getMethod()));
        }
    }

    /**
     * v = ClassName.f
     *
     * @param stringVariableFactory
     */
    private void registerGetStatic(SSAGetInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                   FlowSensitiveStringLikeVariableFactory stringVariableFactory, TypeRepository types,
                                   PrettyPrinter pp) {
        TypeReference resultType = i.getDeclaredFieldType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        if (resultType.isPrimitiveType()) {
            // No pointers here
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pp);
        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        this.addStatement(stmtFactory.staticFieldToLocal(v, f, ir.getMethod()));

        if (StringAndReflectiveUtil.isStringBuilderType(resultType)) {
            // it escaped
        }
        else if (StringAndReflectiveUtil.isStringType(resultType)) {
            StringLikeVariable svv = stringVariableFactory.getOrCreateLocalDef(i, i.getDef());
            StringLikeVariable svf = stringVariableFactory.getOrCreateStaticField(i.getDeclaredField());
            this.addStringStatement(stmtFactory.staticFieldToLocalString(svv, svf, i.getDeclaredField()
                                                                                    .getDeclaringClass()
                                                                                    .toString(), ir.getMethod()));
        }
    }

    /**
     * o.f = v
     *
     * @param stringVariableFactory
     */
    private void registerPutField(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                  FlowSensitiveStringLikeVariableFactory stringVariableFactory, TypeRepository types,
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
        this.addStatement(stmtFactory.localToField(o, f, v, ir.getMethod(), i));

        if (StringAndReflectiveUtil.isStringBuilderType(valueType)) {
            // it escaped
        }
        else if (StringAndReflectiveUtil.isStringType(valueType)) {
            StringLikeVariable svvDef = stringVariableFactory.getOrCreateLocalDef(i, i.getVal());
            StringLikeVariable svvUse = stringVariableFactory.getOrCreateLocalUse(i, i.getVal());
            this.addStringStatement(stmtFactory.localToFieldString(svvDef, svvUse, o, f, ir.getMethod(), i));
        }
    }

    /**
     * ClassName.f = v
     *
     * @param stringVariableFactory
     */
    private void registerPutStatic(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                   FlowSensitiveStringLikeVariableFactory stringVariableFactory, TypeRepository types,
                                   PrettyPrinter pp) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType()) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), valueType, ir.getMethod(), pp);
        this.addStatement(stmtFactory.localToStaticField(f, v, ir.getMethod(), i));

        if (StringAndReflectiveUtil.isStringBuilderType(valueType)) {
            // it escaped
        }
        else if (StringAndReflectiveUtil.isStringLikeType(valueType)
                && StringAndReflectiveUtil.isStringLikeType(i.getDeclaredFieldType())) {
            StringLikeVariable svf = stringVariableFactory.getOrCreateStaticField(i.getDeclaredField());
            StringLikeVariable svvuse = stringVariableFactory.getOrCreateLocalUse(i, i.getVal());
            this.addStringStatement(stmtFactory.localToStaticFieldString(svf, svvuse, ir.getMethod()));
        }
    }

    /**
     * A virtual, static, special, or interface invocation
     *
     * @param bb
     * @param stringVariableFactory
     */
    protected void registerInvoke(SSAInvokeInstruction i, ISSABasicBlock bb, IR ir, ReferenceVariableFactory rvFactory,
                                  FlowSensitiveStringLikeVariableFactory stringVariableFactory, TypeRepository types,
                                  PrettyPrinter pp) {
        assert i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1;

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
            }
            else {
                actuals.add(rvFactory.getOrCreateLocal(i.getUse(j), actualType, ir.getMethod(), pp));
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
            receiver = rvFactory.getOrCreateLocal(i.getReceiver(), receiverType, ir.getMethod(), pp);
        }

        if (PointsToAnalysis.outputLevel >= 2) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, ir.getMethod());
            if (resolvedMethods.isEmpty()) {
                System.err.println("No resolved methods for " + pp.instructionString(i) + " method: "
                        + PrettyPrinter.methodString(i.getDeclaredTarget()) + " caller: "
                        + PrettyPrinter.methodString(ir.getMethod()));
            }
        }

        // //////////// Exceptions ////////////

        TypeReference exType = types.getType(i.getException());
        ReferenceVariable exception = rvFactory.getOrCreateLocal(i.getException(), exType, ir.getMethod(), pp);
        this.registerThrownException(bb, ir, exception, rvFactory, types, pp);

        // //////////// Resolve methods add statements ////////////

        if (i.isStatic()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, ir.getMethod());
            if (resolvedMethods.isEmpty()) {
                return;
            }
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee);
            this.addStatement(stmtFactory.staticCall(i.getCallSite(),
                                                     ir.getMethod(),
                                                     resolvedCallee,
                                                     result,
                                                     actuals,
                                                     exception,
                                                     calleeSummary));

            if (ForNameCallStatement.isForNameCall(i)) {
                List<StringLikeVariable> svarguments = new ArrayList<>(i.getNumberOfParameters());
                for (int j = 0; j < i.getNumberOfParameters(); ++j) {
                    svarguments.add(stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j)));
                }
                this.addStatement(stmtFactory.forNameCall(i.getCallSite(),
                                                          ir.getMethod(),
                                                          i.getDeclaredTarget(),
                                                          result,
                                                          svarguments));

            }
            else if (GetPropertyStatement.isGetPropertyCall(i)) {
                List<StringLikeVariable> svarguments = new ArrayList<>(i.getNumberOfParameters());
                for (int j = 0; j < i.getNumberOfParameters(); ++j) {
                    svarguments.add(stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j)));
                }
                StringLikeVariable svresult = stringVariableFactory.getOrCreateLocalUse(i, i.getReturnValue(0));
                this.addStringStatement(stmtFactory.getPropertyCall(i.getCallSite(),
                                                                    ir.getMethod(),
                                                                    i.getDeclaredTarget(),
                                                                    svresult,
                                                                    svarguments));
            }
            else if (StringAndReflectiveUtil.isValueOf(i.getDeclaredTarget())
                    && StringAndReflectiveUtil.isStringLikeType(types.getType(i.getUse(0)))) {
                StringLikeVariable left = stringVariableFactory.getOrCreateLocalDef(i, i.getDef());
                StringLikeVariable right = stringVariableFactory.getOrCreateLocalUse(i, i.getUse(0));
                this.addStringStatement(stmtFactory.localToLocalString(left, right, ir.getMethod(), i));
            }
            else if (!AnalysisUtil.getClassHierarchy().resolveMethod(i.getDeclaredTarget()).isNative()) {
                this.createStaticOrSpecialMethodCallString(i, ir.getMethod(), stringVariableFactory, resolvedCallee);
            }
        }
        else if (i.isSpecial()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, ir.getMethod());
            if (resolvedMethods.isEmpty()) {
                if (PointsToAnalysis.outputLevel > 1) {
                    System.err.println("No methods found for " + i);
                }
                // No methods found!
                return;
            }
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee);
            this.addStatement(stmtFactory.specialCall(i.getCallSite(),
                                                      ir.getMethod(),
                                                      resolvedCallee,
                                                      result,
                                                      receiver,
                                                      actuals,
                                                      exception,
                                                      calleeSummary));
            IMethod im = StringAndReflectiveUtil.methodReferenceToIMethod(i.getDeclaredTarget());
            if (StringAndReflectiveUtil.isStringInit0Method(im)
                    || StringAndReflectiveUtil.isStringBuilderInit0Method(im)) {
                StringLikeVariable svreceiverDef = stringVariableFactory.getOrCreateLocalDef(i, i.getReceiver());

                this.addStringStatement(stmtFactory.stringInit0(i.getCallSite(), ir.getMethod(), svreceiverDef));
            }
            else if (StringAndReflectiveUtil.isStringBuilderInit1Method(im)) {
                StringLikeVariable svreceiverDef = stringVariableFactory.getOrCreateLocalDef(i, i.getReceiver());
                StringLikeVariable argument = stringVariableFactory.getOrCreateLocalUse(i, i.getUse(1));

                this.addStringStatement(stmtFactory.localToLocalString(svreceiverDef, argument, ir.getMethod(), i));
            }
            else if (!im.isNative()) {
                this.createStaticOrSpecialMethodCallString(i, ir.getMethod(), stringVariableFactory, resolvedCallee);
            }

        }
        else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            if (ir.getSymbolTable().isNullConstant(i.getReceiver())) {
                // Sometimes the receiver is a null constant
                return;
            }
            if (StringAndReflectiveUtil.isStringMethod(i.getDeclaredTarget())) {
                StringLikeVariable svresult = stringVariableFactory.getOrCreateLocalDef(i, i.getReturnValue(0));
                StringLikeVariable svreceiverUse = stringVariableFactory.getOrCreateLocalUse(i, i.getReceiver());
                StringLikeVariable svreceiverDef = stringVariableFactory.getOrCreateLocalDef(i, i.getReceiver());
                List<StringLikeVariable> svarguments = new ArrayList<>(i.getNumberOfParameters());
                for (int j = 0; j < i.getNumberOfParameters(); ++j) {
                    svarguments.add(stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j)));
                }
                this.addStringStatement(stmtFactory.stringMethodCall(i.getCallSite(),
                                                                     ir.getMethod(),
                                                                     i.getDeclaredTarget(),
                                                                     svresult,
                                                                     svreceiverUse,
                                                                     svreceiverDef,
                                                                     svarguments,
                                                                     stringVariableFactory));
            }
            else if (StringAndReflectiveUtil.isGetNameMethod(i.getDeclaredTarget())) {
                StringLikeVariable svresult = stringVariableFactory.getOrCreateLocalDef(i, i.getReturnValue(0));
                this.addStringStatement(stmtFactory.getNameCall(ir.getMethod(), receiver, svresult));
            }
            else if (!AnalysisUtil.getClassHierarchy().resolveMethod(i.getDeclaredTarget()).isNative()) {
                this.createVirtualMethodCallString(i, ir, stringVariableFactory, receiver, exception);
            }

            this.addStatement(stmtFactory.virtualCall(i.getCallSite(),
                                                      ir.getMethod(),
                                                      i.getDeclaredTarget(),
                                                      result,
                                                      receiver,
                                                      actuals,
                                                      exception));
        }
        else {
            throw new UnsupportedOperationException("Unhandled invocation code: " + i.getInvocationCode() + " for "
                    + PrettyPrinter.methodString(i.getDeclaredTarget()));
        }

    }

    // We need the exception to disambiguate duplicated virtual method calls
    private void createVirtualMethodCallString(SSAInvokeInstruction i, IR ir,
                                               FlowSensitiveStringLikeVariableFactory stringVariableFactory,
                                               ReferenceVariable receiver, ReferenceVariable exception) {
        StringLikeVariable returnToVariable;
        if (StringAndReflectiveUtil.isStringType(i.getDeclaredResultType())) {
            returnToVariable = stringVariableFactory.getOrCreateLocalDef(i, i.getReturnValue(0));
            assert returnToVariable != null;
        }
        else {
            returnToVariable = null;
        }

        MethodReference declaredTarget = i.getDeclaredTarget();
        ArrayList<OrderedPair<StringLikeVariable, Integer>> stringArgumentAndParameters = new ArrayList<>();
        // the (1 + ...) is for the `this` argument
        assert (i.getNumberOfParameters() == 1 + declaredTarget.getNumberOfParameters()) // normal
        : "args == 1 + params : " + i.getNumberOfParameters() + " == 1 + " + declaredTarget.getNumberOfParameters()
                + " ; " + i;

        for (int j = 1; j < i.getNumberOfParameters(); ++j) {
            // we use j-1 here because MethodReference objects do not count the `this` argument as a parmater.
            if (StringAndReflectiveUtil.isStringType(declaredTarget.getParameterType(j - 1))) {
                StringLikeVariable argument = stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j));
                // we use j here, not j-1, because IMethod's will include the `this` parameter,
                // unlike a MethodReference, which does not include it.
                OrderedPair<StringLikeVariable, Integer> pair = new OrderedPair<>(argument, j);
                stringArgumentAndParameters.add(pair);
            }
        }
        this.addStringStatement(stmtFactory.virtualMethodCallString(ir.getMethod(),
                                                                    stringArgumentAndParameters,
                                                                    returnToVariable,
                                                                    declaredTarget,
                                                                    receiver,
                                                                    exception));
    }

    private void createStaticOrSpecialMethodCallString(SSAInvokeInstruction i, IMethod caller,
                                                       FlowSensitiveStringLikeVariableFactory stringVariableFactory,
                                                       IMethod resolvedCallee) {
        MethodStringSummary summary = this.findOrCreateStringMethodSummary(resolvedCallee);
        StringLikeVariable formalReturn;
        StringLikeVariable actualReturn;
        if (summary.getRet() == null || i.getNumberOfReturnValues() == 0
                || !StringAndReflectiveUtil.isStringLikeType(resolvedCallee.getReturnType())) {
            formalReturn = null;
            actualReturn = null;
        }
        else {
            formalReturn = summary.getRet();
            actualReturn = stringVariableFactory.getOrCreateLocalDef(i, i.getReturnValue(0));
            assert formalReturn != null;
            assert actualReturn != null;
        }

        // Specials:
        //  - init (becuase we know what the receiver is),
        //  - private (because the receiver is obvious from the class definition), and
        //  - super (because the super is declared in the class definition)
        //
        // resolved IMethod's (unlike MethodReferences) include `this`  in getNumberOfParameters()

        ArrayList<OrderedPair<StringLikeVariable, StringLikeVariable>> stringArgumentAndParameters = new ArrayList<>();
        assert i.getNumberOfParameters() == resolvedCallee.getNumberOfParameters() : "args == params : "
                + i.getNumberOfParameters() + " == " + resolvedCallee.getNumberOfParameters() + " ; " + i;

        // For specials the getNumberOfUses will include `this`, for static methods
        // the getNumberOfUses will not include `this`. MethodStringSummary objects use the same
        // convention for what "Parameter" means.
        for (int j = 0; j < i.getNumberOfUses(); ++j) {
            if (StringAndReflectiveUtil.isStringType(resolvedCallee.getParameterType(j))) {
                StringLikeVariable argument = stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j));
                StringLikeVariable parameter = summary.getFormals().get(j);
                assert argument != null;
                assert parameter != null;
                OrderedPair<StringLikeVariable, StringLikeVariable> pair = new OrderedPair<>(argument, parameter);
                stringArgumentAndParameters.add(pair);
            }
        }

        this.addStringStatement(stmtFactory.staticOrSpecialMethodCallString(i,
                                                                            caller,
                                                                            stringArgumentAndParameters,
                                                                            formalReturn,
                                                                            actualReturn,
                                                                            resolvedCallee));
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
        if (useSingleAllocForPrimitiveArrays && resultType.getArrayElementType().isPrimitiveType()) {
            ReferenceVariable rv = getOrCreateSingleton(resultType);
            this.addStatement(stmtFactory.localToLocal(a, rv, ir.getMethod()));
        }
        else {
            this.addStatement(stmtFactory.newForNormalAlloc(a,
                                                            klass,
                                                            ir.getMethod(),
                                                            i.getNewSite().getProgramCounter(),
                                                            pp.getLineNumber(i)));
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
            this.addStatement(stmtFactory.newForInnerArray(innerArray, arrayklass, ir.getMethod()));

            // Add field assign from the inner array to the array contents field of the outer array
            this.addStatement(stmtFactory.multidimensionalArrayContents(outerArray, innerArray, ir.getMethod()));

            // The array on the next iteration will be contents of this one
            outerArray = innerArray;
        }
    }

    /**
     * v = new TypeName
     * <p>
     * Handle an allocation of the form: "new Foo". Note that this is only the allocation not the constructor call.
     *
     * @param stringVariableFactory
     */
    private void registerNewObject(SSANewInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                   FlowSensitiveStringLikeVariableFactory stringVariableFactory, TypeRepository types,
                                   PrettyPrinter pp) {
        // all "new" instructions are assigned to a local
        TypeReference resultType = i.getConcreteType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName()) : resultType + " != "
                + types.getType(i.getDef()) + " in " + ir.getMethod();
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pp);

        TypeReference allocType = i.getNewSite().getDeclaredType();
        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(allocType);
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        if (useSingleAllocPerThrowableType && TypeRepository.isAssignableFrom(AnalysisUtil.getThrowableClass(), klass)) {
            // the newly allocated object is throwable, and we only want one allocation per throwable type
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod()));

        }
        else if (useSingleAllocForImmutableWrappers && Signatures.isImmutableWrapperType(allocType)) {
            // The newly allocated object is an immutable wrapper class, and we only want one allocation site for each type
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod()));
        }
        else if (useSingleAllocForStrings && TypeRepository.isAssignableFrom(AnalysisUtil.getStringClass(), klass)) {
            // the newly allocated object is a string, and we only want one allocation for strings
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod()));

        }
        else if (useSingleAllocForSwing
                && (klass.toString().contains("Ljavax/swing/") || klass.toString().contains("Lsun/swing/") || klass.toString()
                                                                                                                   .contains("Lcom/sun/java/swing"))) {
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod()));
        }
        else if (klass.toString().contains("swing") && !printedSwingWarning) {
            printedSwingWarning = true;
            System.err.println("USES Java Swing library. May want to use option -useSingleAllocForSwing.");
        }
        else {
            this.addStatement(stmtFactory.newForNormalAlloc(result,
                                                            klass,
                                                            ir.getMethod(),
                                                            i.getNewSite().getProgramCounter(),
                                                            pp.getLineNumber(i)));
        }

        if (StringAndReflectiveUtil.isStringBuilderType(resultType) || StringAndReflectiveUtil.isStringType(resultType)) {
            StringLikeVariable sv = stringVariableFactory.getOrCreateLocalDef(i, i.getDef());
            if (!(sv instanceof EscapedStringBuilderVariable)) {
                this.addStringStatement(stmtFactory.newString(sv, ir.getMethod()));
            }
        }
    }

    /**
     * Print a warning when a class in the swing library is encountered and we are not using a single alloc for these
     * classes
     */
    private boolean printedSwingWarning = false;

    /**
     * x = phi(x_1, x_2, ...)
     *
     * @param stringVariableFactory
     */
    private void registerPhiAssignment(SSAPhiInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                       FlowSensitiveStringLikeVariableFactory stringVariableFactory,
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
        this.addStatement(stmtFactory.phiToLocal(assignee, uses, ir.getMethod()));

        if (StringAndReflectiveUtil.isStringType(phiType) || StringAndReflectiveUtil.isStringBuilderType(phiType)) {
            boolean everyVariableIsEscaped = true;
            StringLikeVariable svassignee = stringVariableFactory.getOrCreateLocalDef(i, i.getDef());
            everyVariableIsEscaped &= svassignee instanceof EscapedStringBuilderVariable;
            List<StringLikeVariable> svuses = new ArrayList<>(i.getNumberOfUses());
            for (int j = 0; j < i.getNumberOfUses(); ++j) {
                if (!types.getType(i.getUse(j)).equals(TypeReference.findOrCreate(ClassLoaderReference.Primordial,
                                                                                  "null"))) {
                    StringLikeVariable temp = stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j));
                    everyVariableIsEscaped &= temp instanceof EscapedStringBuilderVariable;
                    svuses.add(temp);
                }
            }
            if (!everyVariableIsEscaped) {
                this.addStringStatement(stmtFactory.phiToLocalString(svassignee, svuses, ir.getMethod(), pp));
            }
        }
    }

    /**
     * Load-metadata is used for .class access
     */
    private void registerLoadMetadata(SSALoadMetadataInstruction i, IR ir,
                                      ReferenceVariableFactory rvFactory, PrettyPrinter pprint) {
        // This is a call like Object.class that returns a Class object, until we handle reflection just allocate a singleton
        if (!i.getType().equals(TypeReference.JavaLangClass)) {
            throw new RuntimeException("Load metadata with a non-class target " + i);
        }

        // Allocation of a singleton java.lang.Class object
        ReferenceVariable rv = getOrCreateSingletonClassType((TypeReference) i.getToken());
        ReferenceVariable left = rvFactory.getOrCreateLocal(i.getDef(),
                                                            TypeReference.JavaLangClass,
                                                            ir.getMethod(),
                                                            pprint);
        this.addStatement(stmtFactory.localToLocal(left, rv, ir.getMethod()));
    }

    /**
     * return v
     *
     * @param stringVariableFactory
     */
    private void registerReturn(SSAReturnInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                FlowSensitiveStringLikeVariableFactory stringVariableFactory, TypeRepository types,
                                PrettyPrinter pp) {
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
        ReferenceVariable summary = this.findOrCreateMethodSummary(ir.getMethod()).getReturn();
        this.addStatement(stmtFactory.returnStatement(v, summary, ir.getMethod(), i));

        if (StringAndReflectiveUtil.isStringBuilderType(ir.getMethod().getReturnType())) {
            // it escapes
        }
        else if (StringAndReflectiveUtil.isStringType(ir.getMethod().getReturnType())) {
            StringLikeVariable sv = stringVariableFactory.getOrCreateLocalUse(i, i.getResult());
            StringLikeVariable formalReturn = this.findOrCreateStringMethodSummary(ir.getMethod()).getRet();
            this.addStringStatement(stmtFactory.localToLocalString(formalReturn, sv, ir.getMethod(), i));
        }
    }

    /**
     * throw v
     *
     * @param bb
     * @param stringVariableFactory
     */
    private void registerThrow(SSAThrowInstruction i, ISSABasicBlock bb, IR ir, ReferenceVariableFactory rvFactory,
                               TypeRepository types, PrettyPrinter pp) {
        TypeReference throwType = types.getType(i.getException());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getException(), throwType, ir.getMethod(), pp);
        this.registerThrownException(bb, ir, v, rvFactory, types, pp);
    }

    /**
     * Get the method summary nodes for the given method, create if necessary
     *
     * @param method method to get summary nodes for
     */
    public MethodSummaryNodes findOrCreateMethodSummary(IMethod method) {
        MethodSummaryNodes msn = this.methods.get(method);
        if (msn == null) {
            msn = new MethodSummaryNodes(method);
            MethodSummaryNodes ex = this.methods.put(method, msn);
            if (ex != null) {
                msn = ex;
            }
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
        ret.add(this.entryPoint);
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
    static Set<IMethod> resolveMethodsForInvocation(SSAInvokeInstruction inv, IMethod caller) {
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
            if (PointsToAnalysis.outputLevel > 0) {
                System.err.println("WARNING Unable to resolve " + PrettyPrinter.methodString(inv.getDeclaredTarget()));
                if (PointsToAnalysis.outputLevel > 0) {
                    PrettyPrinter pp = new PrettyPrinter(AnalysisUtil.getIR(caller));
                    System.err.println("\tIN : " + PrettyPrinter.methodString(pp.getIR().getMethod()) + " line: "
                            + pp.getLineNumber(inv));
                    System.err.println("\tFOR: " + inv);
                }
            }
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
            ss = new LinkedHashSet<>();//            new LinkedHashSet<>();
            Set<PointsToStatement> ex = this.statementsForMethod.put(m, ss);
            if (ex != null) {
                ss = ex;
            }
        }
        assert !ss.contains(s) : "STATEMENT: " + s + " was already added";
        if (ss.add(s)) {
            this.size++;
        }

        if ((this.size + StatementRegistrar.removed) % 100000 == 0) {
            System.err.println("REGISTERED: " + (this.size + StatementRegistrar.removed) + ", removed: "
                    + StatementRegistrar.removed + " effective: " + this.size);
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

    protected void addStringStatement(StringStatement s) {
        IMethod m = s.getMethod();
        Set<StringStatement> set = this.stringStatementsForMethod.get(m);
        if (set == null) {
            set = new LinkedHashSet<>();
            this.stringStatementsForMethod.put(m, set);
        }
        boolean changedp = this.stringStatementsForMethod.get(m).add(s);
        assert changedp : "STATEMENT: " + s + " was already added";
        if (this.stringStatementsForMethod.size() % 100000 == 0) {
            System.err.println("REGISTERED: " + this.stringStatementsForMethod.size() + " string statements");
        }
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
     * Get all the string statements for a particular method
     *
     * @param m method to get the statements for
     * @return set of points-to statements for <code>m</code>
     */
    public Set<StringStatement> getStringStatementsForMethod(IMethod m) {
        if (this.stringStatementsForMethod.containsKey(m)) {
            return this.stringStatementsForMethod.get(m);
        }
        else {
            return AnalysisUtil.createConcurrentSet();
        }
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
     * Look for String literals in the instruction and create allocation sites for them
     *
     * @param i instruction to create string literals for
     * @param ir code containing the instruction
     * @param types TODO
     * @param stringClass WALA representation of the java.lang.String class
     */
    private void findAndRegisterStringLiterals(SSAInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                               FlowSensitiveStringLikeVariableFactory stringVariableFactory,
                                               TypeRepository types, PrettyPrinter pp) {
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int use = i.getUse(j);
            if (ir.getSymbolTable().isStringConstant(use)) {
                ReferenceVariable newStringLit = rvFactory.getOrCreateLocal(use,
                                                                            TypeReference.JavaLangString,
                                                                            ir.getMethod(),
                                                                            pp);
                if (this.handledStringLit.contains(newStringLit)) {
                    // Already handled this allocation
                    return;
                }
                this.handledStringLit.add(newStringLit);

                // The fake root method always allocates a String so the clinit has already been called, even if we are
                // flow sensitive

                // add points to statements to simulate the allocation
                this.registerStringLiteral(newStringLit, use, ir.getMethod(), pp);

                String stringLiteralString = ir.getSymbolTable().getStringValue(use);
                StringLikeVariable stringLiteralVariable = stringVariableFactory.getOrCreateLocalUse(i, use);
                this.addStringStatement(new StringLiteralStatement(ir.getMethod(),
                                                                   stringLiteralVariable,
                                                                   stringLiteralString));
            }
        }

    }

    /**
     * Add points-to statements for a String constant
     *
     * @param stringLit reference variable for the string literal being handled
     * @param local local variable value number for the literal
     * @param m Method where the literal is created
     */
    private void registerStringLiteral(ReferenceVariable stringLit, int local, IMethod m, PrettyPrinter pp) {
        if (useSingleAllocForStrings) {
            // v = string
            ReferenceVariable rv = getOrCreateSingleton(AnalysisUtil.getStringClass().getReference());
            this.addStatement(stmtFactory.localToLocal(stringLit, rv, m));
        }
        else {
            // v = new String
            this.addStatement(stmtFactory.newForStringLiteral(pp.valString(local), stringLit, m));
            for (IField f : AnalysisUtil.getStringClass().getAllFields()) {
                if (f.getName().toString().equals("value")) {
                    // This is the value field of the String
                    ReferenceVariable stringValue = rvFactory.createStringLitField();
                    this.addStatement(stmtFactory.newForStringField(stringValue, m));
                    this.addStatement(new LocalToFieldStatement(stringLit, f.getReference(), stringValue, m));
                }
            }
        }
    }

    /**
     * Add points-to statements for any generated exceptions thrown by the given instruction
     *
     * @param i instruction that may throw generated exceptions
     * @param bb
     * @param ir code containing the instruction
     * @param rvFactory factory for creating new reference variables
     */
    private final void findAndRegisterGeneratedExceptions(SSAInstruction i, ISSABasicBlock bb, IR ir,
                                                          ReferenceVariableFactory rvFactory, TypeRepository types,
                                                          PrettyPrinter pp) {
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

                this.addStatement(stmtFactory.newForGeneratedException(ex, exClass, ir.getMethod()));
            }
            this.registerThrownException(bb, ir, ex, rvFactory, types, pp);
        }
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
            ReferenceVariable existing = this.singletonReferenceVariables.put(varType, rv);
            if (existing != null) {
                rv = existing;
            }

            IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(varType);
            assert klass != null : "No class found for " + PrettyPrinter.typeString(varType);

            // We present that the allocation for this object occurs in the entry point.
            NewStatement stmt = stmtFactory.newForGeneratedObject(rv,
                                                                  klass,
                                                                  this.entryPoint,
                                                                  PrettyPrinter.typeString(varType));
            this.addStatement(stmt);

        }
        return rv;
    }

    /**
     * Get or create a singleton reference variable for a java.lang.Class object based on the generic type.
     *
     * @param varType type we want a singleton java.lang.Class for
     */
    private ReferenceVariable getOrCreateSingletonClassType(TypeReference varType) {
        ReferenceVariable rv = this.classReferenceVariables.get(varType);
        if (rv == null) {
            rv = rvFactory.createClassReferenceVariable(varType);
            ReferenceVariable existing = this.classReferenceVariables.put(varType, rv);
            if (existing != null) {
                rv = existing;
            }

            IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(varType);
            assert klass != null : "No class found for " + PrettyPrinter.typeString(varType);

            // We present that the allocation for this object occurs in the entry point.
            NewStatement stmt = stmtFactory.newForGeneratedObject(rv,
                                                                  AnalysisUtil.getClassClass(),
                                                                  this.entryPoint,
                                                                  "java.lang.Class<"
                                                                          + PrettyPrinter.typeString(varType)
                                                                          + ">");
            this.addStatement(stmt);

        }
        return rv;
    }

    /**
     * Add an assignment from the a thrown exception to any catch block or exit block exception that exception could
     * reach
     *
     * @param bb Basic block containing the instruction that throws the exception
     * @param ir code containing the instruction that throws
     * @param thrown reference variable representing the value of the exception
     * @param types type information about local variables
     * @param pp pretty printer for the appropriate method
     */
    private final void registerThrownException(ISSABasicBlock bb, IR ir, ReferenceVariable thrown,
                                               ReferenceVariableFactory rvFactory, TypeRepository types,
                                               PrettyPrinter pp) {

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
                    this.addStatement(StatementFactory.exceptionAssignment(thrown, caught, notType, ir.getMethod()));
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
                // uncomment to not propagate errors this is probably unsound
                // notType.add(AnalysisUtil.getErrorClass());
                caught = this.findOrCreateMethodSummary(ir.getMethod()).getException();
                this.addStatement(StatementFactory.exceptionAssignment(thrown, caught, notType, ir.getMethod()));
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
    void registerClassInitializers(SSAInstruction trigger, IR containingCode, List<IMethod> clinits) {
        this.addStatement(stmtFactory.classInit(clinits, containingCode.getMethod(), trigger));
    }

    /**
     * Add statements for the generated allocation of an exception or return object of a given type for a native method
     * with no signature.
     *
     * @param m native method
     * @param type allocated type
     * @param summary summary reference variable for method exception or return
     */
    private void registerAllocationForNative(IMethod m, TypeReference type, ReferenceVariable summary) {
        IClass allocatedClass = AnalysisUtil.getClassHierarchy().lookupClass(type);
        this.addStatement(stmtFactory.newForNative(summary, allocatedClass, m));
    }

    private void registerNative(IMethod m) {
        if (m.getReference().equals(CallStatement.CLONE) && !AnalysisUtil.disableObjectClone) {
            // Object.clone() is handled in CallStatement.processObjectOrArrayClone
            return;
        }
        if (!useDefaultSignatures) {
            // Don't create signatures for native methods without them
            return;
        }
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(m);
        if (!m.getReturnType().isPrimitiveType()) {
            // Allocation of return value
            this.registerAllocationForNative(m, m.getReturnType(), methodSummary.getReturn());
        }

        boolean containsRTE = false;
        try {
            TypeReference[] exceptions = m.getDeclaredExceptions();
            if (exceptions != null) {
                for (TypeReference exType : exceptions) {
                    // Allocation of exception of a particular type
                    ReferenceVariable ex = rvFactory.createNativeException(exType, m);
                    this.addStatement(StatementFactory.exceptionAssignment(ex,
                                                                           methodSummary.getException(),
                                                                           Collections.<IClass> emptySet(),
                                                                           m));
                    this.registerAllocationForNative(m, exType, ex);
                    containsRTE |= exType.equals(TypeReference.JavaLangRuntimeException);
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
            ReferenceVariable ex = rvFactory.createNativeException(TypeReference.JavaLangRuntimeException, m);
            this.addStatement(StatementFactory.exceptionAssignment(ex,
                                                                   methodSummary.getException(),
                                                                   Collections.<IClass> emptySet(),
                                                                   m));
            this.registerAllocationForNative(m, TypeReference.JavaLangRuntimeException, methodSummary.getException());
        }
    }

    private void registerFormalAssignments(IR ir, ReferenceVariableFactory rvFactory, PrettyPrinter pp) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(ir.getMethod());
        for (int i = 0; i < ir.getNumberOfParameters(); i++) {
            TypeReference paramType = ir.getParameterType(i);
            if (paramType.isPrimitiveType()) {
                // No statements for primitives
                continue;
            }
            int paramNum = ir.getParameter(i);
            ReferenceVariable param = rvFactory.getOrCreateLocal(paramNum, paramType, ir.getMethod(), pp);
            this.addStatement(stmtFactory.localToLocal(param, methodSummary.getFormal(i), ir.getMethod()));
        }
    }

    public MethodStringSummary findOrCreateStringMethodSummary(IMethod method) {
        MethodStringSummary summary = this.methodStringSummaries.get(method);
        if (summary == null) {
            IR ir = AnalysisUtil.getIR(method);
            if (ir == null) {
                /* we shouldn't ever need a string method summary for a native method */
                /* summary = MethodStringSummary.makeNative(method); */
                throw new RuntimeException("We shouldn't ever need a string method summary for a native method "
                        + method);
            }
            else {
                summary = MethodStringSummary.make(method, ir);
            }
            MethodStringSummary previous = this.methodStringSummaries.put(method, summary);
            return previous == null ? summary : previous;
        }
        return summary;
    }

    /**
     * Map from local variable to reference variable. This is not complete until the statement registration pass has
     * completed.
     *
     * @return map from local variable to unique reference variable
     */
    public ReferenceVariableCache getRvCache() {
        return this.rvFactory.getRvCache(replacedVariableMap, methods);
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
         * @param pp Pretty printer for local variables and instructions in enclosing method
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

    public IMethod getEntryPoint() {
        return this.entryPoint;
    }

    public boolean shouldUseSingleAllocForGenEx() {
        return useSingleAllocForGenEx;
    }

    /**
     * Whether to use a single allocation site for allocations of the given class
     *
     * @param klass class being allocated
     * @return true if a single allocation site should be used
     */
    public boolean useSingletonForClass(IClass klass) {
        if (useSingleAllocForPrimitiveArrays && klass.isArrayClass()
                && klass.getReference().getArrayElementType().isPrimitiveType()) {
            return true;
        }
        if (useSingleAllocPerThrowableType && TypeRepository.isAssignableFrom(AnalysisUtil.getThrowableClass(), klass)) {
            return true;
        }
        else if (useSingleAllocForImmutableWrappers && Signatures.isImmutableWrapperType(klass.getReference())) {
            return true;
        }
        else if (useSingleAllocForStrings && TypeRepository.isAssignableFrom(AnalysisUtil.getStringClass(), klass)) {
            return true;
        }
        else if (useSingleAllocForSwing
                && (klass.toString().contains("Ljavax/swing/") || klass.toString().contains("Lsun/swing/") || klass.toString()
                                                                                                                   .contains("Lcom/sun/java/swing"))) {
            return true;
        }
        return false;
    }

    /**
     * If the class is has a single allocation site then return the singleton reference variable representing a newly
     * allocated object of that type. Otherwise return null.
     *
     * @param klass class to check
     * @return the singleton reference variable or null if the class is not a singleton
     */
    public ReferenceVariable getSingletonForClass(IClass klass) {
        return this.singletonReferenceVariables.get(klass.getReference());
    }
}
