package analysis.pointer.registrar;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import signatures.Signatures;
import types.TypeRepository;
import util.InstructionType;
import util.OrderedPair;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.StringAndReflectiveUtil;
import analysis.dataflow.flowsensitizer.StringBuilderFlowSensitizer;
import analysis.dataflow.flowsensitizer.StringBuilderFlowSensitizer.Solution;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.duplicates.RemoveDuplicateStatements;
import analysis.pointer.duplicates.RemoveDuplicateStatements.VariableIndex;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.registrar.strings.StringVariable;
import analysis.pointer.statements.ForNameCallStatement;
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
    private final IMethod entryPoint;
    /**
     * Map from method to the points-to statements generated from instructions in that method
     */
    private final ConcurrentMap<IMethod, Set<PointsToStatement>> statementsForMethod;
    private final ConcurrentMap<IMethod, Set<StringStatement>> stringStatementsForMethod;
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

    private final boolean useSingleAllocForSwing = true;

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
     * @param useSingleAllocForImmutableWrappers If true then only one allocation will be made for any immutable wrapper
     *            class. This will reduce the size of the points-to graph (and speed up the points-to analysis), but
     *            result in a loss of precision for these classes.
     */
    public StatementRegistrar(StatementFactory factory, boolean useSingleAllocForGenEx,
                              boolean useSingleAllocPerThrowableType, boolean useSingleAllocForPrimitiveArrays,
                              boolean useSingleAllocForStrings, boolean useSingleAllocForImmutableWrappers) {
        this.methods = AnalysisUtil.createConcurrentHashMap();
        this.statementsForMethod = AnalysisUtil.createConcurrentHashMap();
        this.stringStatementsForMethod = AnalysisUtil.createConcurrentHashMap();
        this.singletonReferenceVariables = AnalysisUtil.createConcurrentHashMap();
        this.handledStringLit = AnalysisUtil.createConcurrentSet();
        this.entryPoint = AnalysisUtil.getFakeRoot();
        this.stmtFactory = factory;
        this.useSingleAllocForGenEx = useSingleAllocForGenEx || useSingleAllocPerThrowableType;
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
                this.registerNative(m, this.rvFactory);
                return true;
            }

            TypeRepository types = new TypeRepository(ir);
            PrettyPrinter pp = new PrettyPrinter(ir);

            debugPrint(m, ">>>>>>> Analyzing : " + m.getName());
            debugPrint(m, "+++++++\n");
            for (SSAInstruction instruction : ir.getInstructions()) {
                debugPrint(m, "  " + instruction);
            }
            debugPrint(m, "\n-------");

            StringBuilderFlowSensitizer sbfs = StringBuilderFlowSensitizer.make(true);
            Solution sensitizerSolution = sbfs.runDataFlowAnalysisAndReturnDefUseMaps(m);
            FlowSensitiveStringVariableFactory stringVariableFactory = FlowSensitiveStringVariableFactory.make(sensitizerSolution.getDefMap(),
                                                                                                               sensitizerSolution.getUseMap());

            /* Add string phi nodes for control joins */
            for (Entry<Integer, Map<Set<Integer>, Integer>> kv : sensitizerSolution.getSensitizerDependencies()
                                                                                   .entrySet()) {
                Integer varNum = kv.getKey();
                Map<Set<Integer>, Integer> dependencyMap = kv.getValue();

                for (Entry<Set<Integer>, Integer> kv2 : dependencyMap.entrySet()) {
                    Set<Integer> dependentSensitizers = kv2.getKey();
                    Integer sensitizer = kv2.getValue();

                    StringVariable sv = stringVariableFactory.getOrCreateLocalWithSubscript(varNum,
                                                                                            sensitizer,
                                                                                            m,
                                                                                            types);
                    Set<StringVariable> dependentSVs = new HashSet<>();
                    for (Integer dependentSensitizer : dependentSensitizers) {
                        dependentSVs.add(stringVariableFactory.getOrCreateLocalWithSubscript(varNum,
                                                                                             dependentSensitizer,
                                                                                             m,
                                                                                             types));
                    }
                    this.addStringStatement(stmtFactory.stringPhiNode(m, sv, dependentSVs));
                }
            }

            debugPrint(m, "<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
            // Add edges from formal summary nodes to the local variables representing the method parameters
            this.registerFormalAssignments(ir, this.rvFactory, stringVariableFactory, types, pp);

            //            Map<IMethod, Map<SSAInstruction, AnalysisRecord<T>>> flowSensitiveDataFlowResults = new StringBuilderFlowSensitizer<>(true,
            //                                                                                                                                  new IFlowSensitizedVariableMapFactory<Var, IFlowSensitizedVariableMap<StringVariable, Integer>>());

            for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
                for (SSAInstruction ins : bb) {
                    debugPrint(m, "  ins: " + ins);
                    if (ins.toString().contains("signatures/library/java/lang/String")
                            || ins.toString().contains("signatures/library/java/lang/AbstractStringBuilder")) {
                        System.err.println("\tWARNING: handling instruction mentioning String signature " + ins
                                + " in " + m);
                    }
                    handleInstruction(ins, ir, bb, types, stringVariableFactory, pp);
                }
            }
            debugPrint(m, ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");

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
            debugPrint(m, "String statements for this method are: ");
            debugPrint(m, this.stringStatementsForMethod.get(m) == null ? "{}"
                    : this.stringStatementsForMethod.get(m).toString());
            debugPrint(m, "Regular statements for this method are: ");
            debugPrint(m, this.statementsForMethod.get(m) == null ? "{}" : this.statementsForMethod.get(m).toString());
            return true;
        }
        return false;

    }

    private void debugPrint(IMethod m, String string) {
        if (m.getName().toString().equals("main")) {
            System.err.println(string);
        }
    }

    /**
     * A listener that will get notified of newly created statements.
     */
    private StatementListener stmtListener = null;
    int swingClasses = 0;

    public int getSwingClasses() {
        return this.swingClasses;
    }

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
                                     FlowSensitiveStringVariableFactory stringVariableFactory, PrettyPrinter printer) {
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
            this.registerReflection((SSALoadMetadataInstruction) i, ir, this.rvFactory, types, printer);
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
        this.addStatement(stmtFactory.arrayToLocal(v, a, baseType, ir.getMethod()));
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
        this.addStatement(stmtFactory.localToArrayContents(a, v, baseType, ir.getMethod(), i));
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
                                  FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
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

        if (StringAndReflectiveUtil.isStringType(resultType)) {
            StringVariable svv = stringVariableFactory.getOrCreateLocalDef(i, i.getDef(), ir.getMethod(), types, pp);
            this.addStringStatement(stmtFactory.fieldToLocalString(svv, o, f, ir.getMethod()));
        }
    }

    /**
     * v = ClassName.f
     *
     * @param stringVariableFactory
     */
    private void registerGetStatic(SSAGetInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                   FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
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

        if (StringAndReflectiveUtil.isStringType(resultType)) {
            StringVariable svv = stringVariableFactory.getOrCreateLocalDef(i, i.getDef(), ir.getMethod(), types, pp);
            StringVariable svf = stringVariableFactory.getOrCreateStaticField(i.getDeclaredField());
            this.addStringStatement(stmtFactory.staticFieldToLocalString(svv, svf, ir.getMethod()));
        }
    }

    /**
     * o.f = v
     *
     * @param stringVariableFactory
     */
    private void registerPutField(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                  FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
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

        if (StringAndReflectiveUtil.isStringType(valueType)) {
            StringVariable svvDef = stringVariableFactory.getOrCreateLocalDef(i, i.getVal(), ir.getMethod(), types, pp);
            StringVariable svvUse = stringVariableFactory.getOrCreateLocalUse(i, i.getVal(), ir.getMethod(), types, pp);
            this.addStringStatement(stmtFactory.localToFieldString(svvDef, svvUse, o, f, ir.getMethod()));
        }
    }

    /**
     * ClassName.f = v
     *
     * @param stringVariableFactory
     */
    private void registerPutStatic(SSAPutInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                   FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
                                   PrettyPrinter pp) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType()) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), valueType, ir.getMethod(), pp);
        this.addStatement(stmtFactory.localToStaticField(f, v, ir.getMethod(), i));

        if (StringAndReflectiveUtil.isStringType(valueType)) {
            StringVariable svf = stringVariableFactory.getOrCreateStaticField(i.getDeclaredField());
            StringVariable svv = stringVariableFactory.getOrCreateLocalUse(i, i.getVal(), ir.getMethod(), types, pp);
            this.addStringStatement(stmtFactory.localToStaticFieldString(svf, svv, ir.getMethod()));
        }
    }

    /**
     * A virtual, static, special, or interface invocation
     *
     * @param bb
     * @param stringVariableFactory
     */
    protected void registerInvoke(SSAInvokeInstruction i, ISSABasicBlock bb, IR ir, ReferenceVariableFactory rvFactory,
                                  FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
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
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee, rvFactory);
            this.addStatement(stmtFactory.staticCall(i.getCallSite(),
                                                     ir.getMethod(),
                                                     resolvedCallee,
                                                     result,
                                                     actuals,
                                                     exception,
                                                     calleeSummary));

            if (ForNameCallStatement.isForNameCall(i)) {
                List<StringVariable> svarguments = new ArrayList<>(i.getNumberOfParameters());
                for (int j = 0; j < i.getNumberOfParameters(); ++j) {
                    svarguments.add(stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j), ir.getMethod(), types, pp));
                }
                this.addStatement(stmtFactory.forNameCall(i.getCallSite(),
                                                          ir.getMethod(),
                                                          i.getDeclaredTarget(),
                                                          result,
                                                          svarguments));

            }
            else {
                List<Integer> stringArguments = getStringArgumentsForMethod(i, types);
                generateEscapeViaMethodStatements(stringArguments, i, ir.getMethod(), types, stringVariableFactory, pp);
            }
        }
        else if (i.isSpecial()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, ir.getMethod());
            if (resolvedMethods.isEmpty()) {
                // XXX No methods found!
                return;
            }
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee, rvFactory);
            this.addStatement(stmtFactory.specialCall(i.getCallSite(),
                                                      ir.getMethod(),
                                                      resolvedCallee,
                                                      result,
                                                      receiver,
                                                      actuals,
                                                      exception,
                                                      calleeSummary));
            if (StringAndReflectiveUtil.isStringInitMethod(i.getDeclaredTarget())) {
                StringVariable svreceiverDef = stringVariableFactory.getOrCreateLocalDef(i,
                                                                                         i.getReceiver(),
                                                                                         ir.getMethod(),
                                                                                         types,
                                                                                         pp);

                this.addStringStatement(stmtFactory.stringInit(i.getCallSite(), ir.getMethod(), svreceiverDef));
            }
            else {
                List<Integer> stringArguments = getStringArgumentsForMethod(i, types);
                generateEscapeViaMethodStatements(stringArguments, i, ir.getMethod(), types, stringVariableFactory, pp);
            }

        }
        else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            if (ir.getSymbolTable().isNullConstant(i.getReceiver())) {
                // Sometimes the receiver is a null constant
                return;
            }
            if (StringAndReflectiveUtil.isStringMethod(i.getDeclaredTarget())) {
                StringVariable svresult = stringVariableFactory.getOrCreateLocalDef(i,
                                                                                    i.getReturnValue(0),
                                                                                    ir.getMethod(),
                                                                                    types,
                                                                                    pp);
                StringVariable svreceiverUse = stringVariableFactory.getOrCreateLocalUse(i,
                                                                                         i.getReceiver(),
                                                                                         ir.getMethod(),
                                                                                         types,
                                                                                         pp);
                StringVariable svreceiverDef = stringVariableFactory.getOrCreateLocalDef(i,
                                                                                         i.getReceiver(),
                                                                                         ir.getMethod(),
                                                                                         types,
                                                                                         pp);
                List<StringVariable> svarguments = new ArrayList<>(i.getNumberOfParameters());
                for (int j = 0; j < i.getNumberOfParameters(); ++j) {
                    svarguments.add(stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j), ir.getMethod(), types, pp));
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
            else {
                List<Integer> stringArguments = getStringArgumentsForMethod(i, types);
                generateEscapeViaMethodStatements(stringArguments, i, ir.getMethod(), types, stringVariableFactory, pp);
            }

            this.addStatement(stmtFactory.virtualCall(i.getCallSite(),
                                                      ir.getMethod(),
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

    private List<Integer> getStringArgumentsForMethod(SSAInvokeInstruction i, TypeRepository types) {
        List<Integer> result = new ArrayList<>();

        for (int j = 0; j < i.getNumberOfParameters(); ++j) {
            if (StringAndReflectiveUtil.isStringType(types.getType(i.getUse(j)))) {
                result.add(i.getUse(j));
            }
        }

        return result;
    }

    private void generateEscapeViaMethodStatements(List<Integer> stringArguments, SSAInstruction i, IMethod method,
                                                   TypeRepository types,
                                                   FlowSensitiveStringVariableFactory stringVariableFactory,
                                                   PrettyPrinter pp) {
        for (Integer s : stringArguments) {
            ReferenceVariable rv = rvFactory.getOrCreateLocal(s, types.getType(s), method, pp);
            StringVariable svuse = stringVariableFactory.getOrCreateLocalUse(i, s, method, types, pp);
            StringVariable svdef = stringVariableFactory.getOrCreateLocalDef(i, s, method, types, pp);
            this.addStringStatement(stmtFactory.escapeViaMethodStringStatement(rv, svuse, svdef, method));
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
        if (useSingleAllocForPrimitiveArrays && resultType.getArrayElementType().isPrimitiveType()) {
            ReferenceVariable rv = getOrCreateSingleton(resultType);
            this.addStatement(stmtFactory.localToLocal(a, rv, ir.getMethod(), false));
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
                                   FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
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
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod(), false));

        }
        else if (useSingleAllocForImmutableWrappers && Signatures.isImmutableWrapperType(allocType)) {
            // The newly allocated object is an immutable wrapper class, and we only want one allocation site for each type
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod(), false));
        }
        else if (useSingleAllocForStrings && TypeRepository.isAssignableFrom(AnalysisUtil.getStringClass(), klass)) {
            // the newly allocated object is a string, and we only want one allocation for strings
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod(), false));

        }
        else if (useSingleAllocForSwing
                && (klass.toString().contains("Ljavax/swing/") || klass.toString().contains("Lsun/swing/") || klass.toString()
                                                                                                                   .contains("Lcom/sun/java/swing"))) {
            swingClasses++;
            ReferenceVariable rv = getOrCreateSingleton(allocType);
            this.addStatement(stmtFactory.localToLocal(result, rv, ir.getMethod(), false));
        }
        else if (klass.toString().contains("swing")) {
            System.err.println("SWING CLASS: " + klass);
        }
        else {
            this.addStatement(stmtFactory.newForNormalAlloc(result,
                                                            klass,
                                                            ir.getMethod(),
                                                            i.getNewSite().getProgramCounter(),
                                                            pp.getLineNumber(i)));
        }

        if (stringVariableFactory.isStringType(resultType)) {
            StringVariable sv = stringVariableFactory.getOrCreateLocalDef(i, i.getDef(), ir.getMethod(), types, pp);
            this.addStringStatement(stmtFactory.newString(sv, ir.getMethod()));
        }
    }

    /**
     * x = phi(x_1, x_2, ...)
     *
     * @param stringVariableFactory
     */
    private void registerPhiAssignment(SSAPhiInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                       FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
                                       PrettyPrinter pp) {
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

        StringVariable svassignee = stringVariableFactory.getOrCreateLocalDef(i, i.getDef(), ir.getMethod(), types, pp);
        List<StringVariable> svuses = new ArrayList<>(i.getNumberOfUses());
        for (int j = 0; j < i.getNumberOfUses(); ++j) {
            svuses.add(stringVariableFactory.getOrCreateLocalUse(i, i.getUse(j), ir.getMethod(), types, pp));
        }
        this.addStringStatement(stmtFactory.phiToLocalString(svassignee, svuses, ir.getMethod(), pp));
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
     *
     * @param stringVariableFactory
     */
    private void registerReturn(SSAReturnInstruction i, IR ir, ReferenceVariableFactory rvFactory,
                                FlowSensitiveStringVariableFactory stringVariableFactory, TypeRepository types,
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
        ReferenceVariable summary = this.findOrCreateMethodSummary(ir.getMethod(), rvFactory).getReturn();
        this.addStatement(stmtFactory.returnStatement(v, summary, ir.getMethod(), i));

        if (StringAndReflectiveUtil.isStringType(valType)) {
            StringVariable svv = stringVariableFactory.getOrCreateLocalUse(i, i.getResult(), ir.getMethod(), types, pp);
            this.addStringStatement(stmtFactory.returnString(svv, summary, ir.getMethod(), i));
        }

        registerEscapeOfFormals(i, ir, stringVariableFactory, types, pp);
    }

    private void registerEscapeOfFormals(SSAReturnInstruction i, IR ir,
                                         FlowSensitiveStringVariableFactory stringVariableFactory,
                                         TypeRepository types, PrettyPrinter pp) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(ir.getMethod(), rvFactory);
        for (int j = 0; j < ir.getNumberOfParameters(); j++) {
            TypeReference paramType = ir.getParameterType(j);
            if (paramType.isPrimitiveType()) {
                // No statements for primitives
                continue;
            }
            int paramNum = ir.getParameter(j);
            if (paramType.equals(TypeReference.JavaLangString) || paramType.equals(TypeReference.JavaLangStringBuilder)) {
                ReferenceVariable rv = rvFactory.getOrCreateLocal(paramNum, paramType, ir.getMethod(), pp);
                StringVariable sv = stringVariableFactory.getOrCreateLocalUse(i, paramNum, ir.getMethod(), types, pp);
                this.addStringStatement(stmtFactory.escapeViaReturnStringStatement(rv, sv, ir.getMethod()));
            }
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
            ss = AnalysisUtil.createConcurrentSet();
            Set<PointsToStatement> ex = this.statementsForMethod.putIfAbsent(m, ss);
            if (ex != null) {
                ss = ex;
            }
        }
        assert !ss.contains(s) : "STATEMENT: " + s + " was already added";
        if (ss.add(s)) {
            this.size++;
        }
        if (stmtListener != null) {
            // let the listener now a statement has been added.
            stmtListener.newStatement(s);
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
        if (!this.stringStatementsForMethod.containsKey(m)) {
            this.stringStatementsForMethod.put(m, AnalysisUtil.createConcurrentSingletonSet(s));
        }
        else {
            boolean changedp = this.stringStatementsForMethod.get(m).add(s);
            assert changedp : "STATEMENT: " + s + " was already added";
            if (stmtListener != null) {
                // XXX: What to do about string statements
                // stmtListener.newStatement(s);
            }
            if (this.stringStatementsForMethod.size() % 100000 == 0) {
                System.err.println("REGISTERED: " + this.stringStatementsForMethod.size() + " string statements");
            }
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
                                               FlowSensitiveStringVariableFactory stringVariableFactory,
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
                StringVariable stringLiteralVariable = stringVariableFactory.getOrCreateLocalUse(i,
                                                                                                 use,
                                                                                                 ir.getMethod(),
                                                                                                 types,
                                                                                                 pp);
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
            this.addStatement(stmtFactory.localToLocal(stringLit, rv, m, false));
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
            ReferenceVariable existing = this.singletonReferenceVariables.putIfAbsent(varType, rv);
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
                    this.addStatement(StatementFactory.exceptionAssignment(thrown,
                                                                           caught,
                                                                           notType,
                                                                           ir.getMethod(),
                                                                           false));
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
                this.addStatement(StatementFactory.exceptionAssignment(thrown, caught, notType, ir.getMethod(), true));
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

    private void registerNative(IMethod m, ReferenceVariableFactory rvFactory) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(m, rvFactory);
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
                                                                           m,
                                                                           true));
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
                                                                   m,
                                                                   true));
            this.registerAllocationForNative(m, TypeReference.JavaLangRuntimeException, methodSummary.getException());
        }
    }

    private void registerFormalAssignments(IR ir, ReferenceVariableFactory rvFactory,
                                           FlowSensitiveStringVariableFactory stringVariableFactory,
                                           TypeRepository types, PrettyPrinter pp) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(ir.getMethod(), rvFactory);
        for (int i = 0; i < ir.getNumberOfParameters(); i++) {
            TypeReference paramType = ir.getParameterType(i);
            if (paramType.isPrimitiveType()) {
                // No statements for primitives
                continue;
            }
            int paramNum = ir.getParameter(i);
            ReferenceVariable param = rvFactory.getOrCreateLocal(paramNum, paramType, ir.getMethod(), pp);
            this.addStatement(stmtFactory.localToLocal(param, methodSummary.getFormal(i), ir.getMethod(), true));

            if (StringAndReflectiveUtil.isStringType(paramType)) {
                StringVariable sv = stringVariableFactory.getOrCreateParamDef(paramNum, ir.getMethod(), types, pp);
                this.addStringStatement(stmtFactory.localFromFormalString(sv,
                                                                          methodSummary.getFormal(i),
                                                                          ir.getMethod(),
                                                                          true));
            }
        }
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
        return this.entryPoint;
    }

    public boolean shouldUseSingleAllocForGenEx() {
        return useSingleAllocForGenEx;
    }
}
