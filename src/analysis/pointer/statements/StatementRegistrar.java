package analysis.pointer.statements;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.graph.PointsToGraph;
import analysis.pointer.graph.ReferenceVariable;

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
 * This class manages the registration of new points-to graph statements, which
 * are then processed by the pointer analysis
 */
public class StatementRegistrar {

    /**
     * Points-to graph nodes for local variables
     */
    private final Map<LocalKey, ReferenceVariable> locals = new LinkedHashMap<>();
    /**
     * Points-to graph nodes for local variables representing the contents of
     * the inner dimensions of multi-dimensional arrays
     */
    private final Map<ArrayContentsKey, ReferenceVariable> arrayContentsTemps = new LinkedHashMap<>();
    /**
     * Points-to graph nodes for implicit exceptions and errors
     */
    private final Map<ImplicitThrowKey, ReferenceVariable> implicitThrows = new LinkedHashMap<>();
    /**
     * Points-to graph nodes for static fields
     */
    private final Map<IField, ReferenceVariable> staticFields = new LinkedHashMap<>();
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
     * Map from method to the points-to statements generated from instructions
     * in that method
     */
    private final Map<IMethod, Set<PointsToStatement>> statementsForMethod = new HashMap<>();

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
        ReferenceVariable array = getOrCreateLocal(i.getArrayRef(), ir);
        ReferenceVariable local = getOrCreateLocal(i.getDef(), ir);
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
        ReferenceVariable array = getOrCreateLocal(i.getArrayRef(), ir);
        ReferenceVariable value = getOrCreateLocal(i.getValue(), ir);
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
        ReferenceVariable result = getOrCreateLocal(i.getResult(), ir);
        ReferenceVariable checkedVal = getOrCreateLocal(i.getVal(), ir);
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
        ReferenceVariable assignee = getOrCreateLocal(i.getDef(), ir);
        ReferenceVariable receiver = getOrCreateLocal(i.getRef(), ir);
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
        ReferenceVariable assignee = getOrCreateLocal(i.getDef(), ir);
        ReferenceVariable field = getOrCreateNodeForStaticField(i.getDeclaredField(), cha);
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
        ReferenceVariable assignedValue = getOrCreateLocal(i.getVal(), ir);
        ReferenceVariable receiver = getOrCreateLocal(i.getRef(), ir);
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
        ReferenceVariable assignedValue = getOrCreateLocal(i.getVal(), ir);
        ReferenceVariable fieldNode = getOrCreateNodeForStaticField(f, cha);
        addStatement(new LocalToStaticFieldStatement(fieldNode, assignedValue, ir, i));

    }

    /**
     * Handle a virtual, static, special, or interface invocation
     * 
     * @param i
     *            invoke instruction
     * @param ir
     *            code for the method containing the instruction (the caller)
     * @param cha
     *            Class hierarchy, used to resolve method calls
     */
    protected void registerInvoke(SSAInvokeInstruction i, IR ir, IClassHierarchy cha) {
        assert (i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1);

        ReferenceVariable resultNode = null;
        if (i.getNumberOfReturnValues() > 0) {
            TypeReference returnType = TypeRepository.getType(i.getReturnValue(0), ir);
            if (!returnType.isPrimitiveType()) {
                resultNode = getOrCreateLocal(i.getReturnValue(0), ir);
            }
        }

        List<ReferenceVariable> actuals = new LinkedList<>();
        // actuals
        if (i.isStatic() && i.getNumberOfParameters() > 0) {
            // for non-static methods param(0) is the target
            // for static it is the first argument
            if (TypeRepository.getType(i.getUse(0), ir).isPrimitiveType()) {
                actuals.add(null);
            } else {
                actuals.add(getOrCreateLocal(i.getUse(0), ir));
            }
        }
        for (int j = 1; j < i.getNumberOfParameters(); j++) {
            if (TypeRepository.getType(i.getUse(j), ir).isPrimitiveType()) {
                actuals.add(null);
            } else {
                actuals.add(getOrCreateLocal(i.getUse(j), ir));
            }
        }

        ReferenceVariable exceptionNode = getOrCreateLocal(i.getException(), ir);

        // Get the receiver if it is not static
        ReferenceVariable receiver = i.isStatic() ? null : getOrCreateLocal(i.getReceiver(), ir);

        Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, cha);
        if (i.isStatic()) {
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            addStatement(new StaticCallStatement(i.getCallSite(), resolvedMethod, actuals, resultNode, exceptionNode,
                                            ir, i));
        } else if (i.isSpecial()) {
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            addStatement(new SpecialCallStatement(i.getCallSite(), resolvedMethod, receiver, actuals, resultNode,
                                            exceptionNode, ir, i));
        } else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                                        || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            addStatement(new VirtualCallStatement(i.getCallSite(), i.getDeclaredTarget(), receiver, actuals,
                                            resultNode, exceptionNode, cha, ir, i));
        } else {
            throw new UnsupportedOperationException("Unhandled invocation code: " + i.getInvocationCode() + " for "
                                            + PrettyPrinter.parseMethod(i.getDeclaredTarget()));
        }
    }

    /**
     * Register a new array allocation, note that this is only the allocation
     * not the initialization if there is any.
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
        ReferenceVariable result = getOrCreateLocal(i.getDef(), ir);

        IClass klass = cha.lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.parseType(i.getNewSite().getDeclaredType());
        addStatement(new NewStatement(result, klass, ir, i));

        // Handle arrays with multiple dimensions
        ReferenceVariable array = result;
        for (int dim = 1; dim < i.getNumberOfUses(); dim++) {
            // Create local for array contents
            ReferenceVariable contents = getLocalForArrayContents(dim, array.getExpectedType().getArrayElementType(),
                                            i, ir);
            // Add an allocation for the contents
            IClass arrayklass = cha.lookupClass(contents.getExpectedType());
            assert arrayklass != null : "No class found for "
                                            + PrettyPrinter.parseType(i.getNewSite().getDeclaredType());
            addStatement(new NewStatement(contents, arrayklass, ir, i));

            // Add field assign from the field of the outer array to the array
            // contents
            addStatement(new LocalToFieldStatement(array, contents, ir, i));

            // The array on the next iteration will be contents of this one
            array = contents;
        }
    }

    /**
     * Handle an allocation of the form: "new Foo". Note that this is only the
     * allocation not the constructor call.
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
        ReferenceVariable result = getOrCreateLocal(i.getDef(), ir);

        IClass klass = cha.lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.parseType(i.getNewSite().getDeclaredType());
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
        ReferenceVariable assignee = getOrCreateLocal(i.getDef(), ir);
        List<ReferenceVariable> uses = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int arg = i.getUse(j);
            if (TypeRepository.getType(arg, ir) != TypeReference.Null) {
                ReferenceVariable use = getOrCreateLocal(i.getUse(j), ir);
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
        ReferenceVariable result = getOrCreateLocal(i.getResult(), ir);
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
        ReferenceVariable exception = getOrCreateLocal(i.getException(), ir);
        addAssignmentForThrownException(i, ir, exception, cha);
    }

    /**
     * Get the reference variable for the given local in the given IR. The local
     * should not have a primitive type or null. Create a reference variable if
     * one does not already exist
     * 
     * @param local
     *            local ID, the type of this should not be primitive or null
     * @param ir
     *            method intermediate representation
     * @return points-to graph node for the local
     */
    protected ReferenceVariable getOrCreateLocal(int local, IR ir) {
        assert !TypeRepository.getType(local, ir).isPrimitiveType() : "No local nodes for primitives: "
                                        + PrettyPrinter.parseType(TypeRepository.getType(local, ir));
        LocalKey key = new LocalKey(local, ir);
        ReferenceVariable node = locals.get(key);
        if (node == null) {
            TypeReference type = TypeRepository.getType(local, ir);
            node = new ReferenceVariable(PrettyPrinter.valString(local, ir), type, false);
            locals.put(key, node);
        }
        return node;
    }

    /**
     * Get the reference variable for the given local in the given IR. The local
     * should not have a primitive type or null.
     * 
     * @param local
     *            local ID, the type of this should not be primitive or null
     * @param ir
     *            method intermediate representation
     * @return reference variable for the local
     */
    public ReferenceVariable getLocal(int local, IR ir) {
        assert !TypeRepository.getType(local, ir).isPrimitiveType() : "No local nodes for primitives: "
                                        + PrettyPrinter.parseType(TypeRepository.getType(local, ir));
        LocalKey key = new LocalKey(local, ir);
        assert locals.containsKey(key);
        return locals.get(key);
    }

    private ReferenceVariable getLocalForArrayContents(int dim, TypeReference type, SSANewInstruction i, IR ir) {
        // Need to create one for the inner and use it to get the outer or it
        // doesn't work
        ArrayContentsKey key = new ArrayContentsKey(dim, i, ir);
        ReferenceVariable local = arrayContentsTemps.get(key);
        if (local == null) {
            local = new ReferenceVariable(PointsToGraph.ARRAY_CONTENTS + dim, type, false);
            arrayContentsTemps.put(key, local);
        }
        return local;
    }

    /**
     * Get a reference variable for an implicitly thrown exception/error, create
     * it if it does not already exist
     * 
     * @param type
     *            type of the exception
     * @param i
     *            instruction that throws
     * @param ir
     *            method containing the instruction that throws
     * @return reference variable for an implicit throwable
     */
    protected ReferenceVariable getOrCreateImplicitExceptionNode(TypeReference type, SSAInstruction i, IR ir) {
        ImplicitThrowKey key = new ImplicitThrowKey(type, ir, i);
        ReferenceVariable node = implicitThrows.get(key);
        if (node == null) {
            node = new ReferenceVariable("IMPLICIT-" + PrettyPrinter.parseType(type), type, false);
            implicitThrows.put(key, node);
        }
        return node;
    }

    /**
     * Get a reference variable for an implicitly thrown exception/error
     * 
     * @param type
     *            type of the exception
     * @param i
     *            instruction that throws
     * @param ir
     *            method containing the instruction that throws
     * @return reference variable for an implicit throwable
     */
    public ReferenceVariable getImplicitExceptionNode(TypeReference type, SSAInstruction i, IR ir) {
        ImplicitThrowKey key = new ImplicitThrowKey(type, ir, i);
        assert implicitThrows.containsKey(key);
        return implicitThrows.get(key);
    }

    /**
     * Get the reference variable for the given static field
     * 
     * @param field
     *            field to get the node for
     * @return reference variable for the static field
     */
    private ReferenceVariable getOrCreateNodeForStaticField(FieldReference field, IClassHierarchy cha) {
        IField f = cha.resolveField(field);
        ReferenceVariable node = staticFields.get(f);
        if (node == null) {
            if (f.getFieldTypeReference().isPrimitiveType()) {
                throw new RuntimeException(
                                                "Trying to create reference variable for a static field with a primitive type.");
            }

            node = new ReferenceVariable(PrettyPrinter.parseType(f.getDeclaringClass().getReference()) + "."
                                            + f.getName().toString(), f.getFieldTypeReference(), true);
            staticFields.put(f, node);
        }
        return node;
    }

    /**
     * Get the reference variable for the given static field
     * 
     * @param field
     *            field to get the node for
     * @return reference variable for the static field
     */
    public ReferenceVariable getNodeForStaticField(FieldReference field, IClassHierarchy cha) {
        IField f = cha.resolveField(field);
        assert staticFields.containsKey(f);
        return staticFields.get(f);
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
     * If this is a static or special call then we know statically what the
     * target of the call is and can therefore resolve the method statically. If
     * it is virtual then we need to add statements for all possible run time
     * method resolutions but may only analyze some of these depending on what
     * the pointer analysis gives for the receiver type.
     * 
     * @param inv
     *            method invocation to resolve methods for
     * @param cha
     *            class hierarchy to use for method resolution
     * @return Set of methods the invocation could call
     */
    protected static Set<IMethod> resolveMethodsForInvocation(SSAInvokeInstruction inv, IClassHierarchy cha) {
        Set<IMethod> targets = null;
        if (inv.isStatic()) {
            IMethod resolvedMethod = cha.resolveMethod(inv.getDeclaredTarget());
            assert resolvedMethod != null : "No method found for " + PrettyPrinter.parseMethod(inv.getDeclaredTarget());
            targets = Collections.singleton(resolvedMethod);
        } else if (inv.isSpecial()) {
            IMethod resolvedMethod = cha.resolveMethod(inv.getDeclaredTarget());
            assert resolvedMethod != null : "No method found for " + inv.toString();
            targets = Collections.singleton(resolvedMethod);
        } else if (inv.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                                        || inv.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            targets = cha.getPossibleTargets(inv.getDeclaredTarget());
            assert targets != null : "No methods found for " + inv.toString();
        } else {
            throw new UnsupportedOperationException("Unhandled invocation code: " + inv.getInvocationCode() + " for "
                                            + PrettyPrinter.parseMethod(inv.getDeclaredTarget()));
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
     * Key uniquely identifying an implicitly thrown exception or error
     */
    private static class ImplicitThrowKey {
        /**
         * Containing method
         */
        private final IR ir;
        /**
         * Instruction that throws the exception/error
         */
        private final SSAInstruction i;
        /**
         * Type of exception/error
         */
        private final TypeReference type;

        /**
         * Create a new key
         * 
         * @param type
         *            Type of exception/error
         * @param ir
         *            Containing method
         * @param i
         *            Instruction that throws the exception/error
         */
        public ImplicitThrowKey(TypeReference type, IR ir, SSAInstruction i) {
            this.type = type;
            this.ir = ir;
            this.i = i;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((i == null) ? 0 : i.hashCode());
            result = prime * result + ((ir == null) ? 0 : ir.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ImplicitThrowKey other = (ImplicitThrowKey) obj;
            if (i == null) {
                if (other.i != null)
                    return false;
            } else if (!i.equals(other.i))
                return false;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (!ir.equals(other.ir))
                return false;
            if (type == null) {
                if (other.type != null)
                    return false;
            } else if (!type.equals(other.type))
                return false;
            return true;
        }
    }

    /**
     * Key uniquely identifying a local variable.
     */
    private static class LocalKey {
        /**
         * local ID
         */
        private final int value;
        /**
         * Code
         */
        private final IR ir;

        /**
         * Create a new key
         * 
         * @param value
         *            local variable ID
         * @param ir
         *            code
         */
        public LocalKey(int value, IR ir) {
            this.value = value;
            this.ir = ir;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ir.hashCode();
            result = prime * result + value;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            LocalKey other = (LocalKey) obj;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (ir != other.ir)
                return false;
            if (value != other.value)
                return false;
            return true;
        }

        @Override
        public String toString() {
            return PrettyPrinter.valString(value, ir) + " in " + PrettyPrinter.parseMethod(ir.getMethod());
        }
    }

    /**
     * Key into the array containing temporary local variables created to
     * represent the contents of the inner dimensions of multi-dimensional
     * arrays
     */
    private static class ArrayContentsKey {
        /**
         * Dimension (counted from the outside in) e.g. 1 is the contents of the
         * actual declared multi-dimensional array
         */
        private final int dim;
        /**
         * instruction for new array
         */
        private final SSANewInstruction i;
        /**
         * Code containing the instruction
         */
        private final IR ir;

        /**
         * Create a new key for the contents of the inner dimensions of
         * multi-dimensional arrays
         * 
         * @param dim
         *            Dimension (counted from the outside in) e.g. 1 is the
         *            contents of the actual declared multi-dimensional array
         * @param i
         *            instruction for new array
         * @param ir
         *            Code containing the instruction
         */
        public ArrayContentsKey(int dim, SSANewInstruction i, IR ir) {
            this.dim = dim;
            this.i = i;
            this.ir = ir;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + dim;
            result = prime * result + ((i == null) ? 0 : i.hashCode());
            result = prime * result + ((ir == null) ? 0 : ir.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ArrayContentsKey other = (ArrayContentsKey) obj;
            if (dim != other.dim)
                return false;
            if (i == null) {
                if (other.i != null)
                    return false;
            } else if (!i.equals(other.i))
                return false;
            if (ir == null) {
                if (other.ir != null)
                    return false;
            } else if (!ir.equals(other.ir))
                return false;
            return true;
        }
    }

    protected final void addStatementsForGeneratedExceptions(SSAInstruction i, IR ir, IClassHierarchy cha) {
        for (TypeReference exType : PreciseExceptionResults.implicitExceptions(i)) {
            ReferenceVariable ex = getImplicitExceptionNode(exType, i, ir);
            IClass exClass = cha.lookupClass(exType);
            assert exClass != null : "No class found for " + PrettyPrinter.parseType(exType);
            addStatement(NewStatement.newStatementForGeneratedException(ex, exClass, ir, i));
            addAssignmentForThrownException(i, ir, ex, cha);
        }
    }

    /**
     * Add an assignment from the a thrown exception to any catch block or exit
     * block exception that exception could reach
     * 
     * @param i
     *            instruction throwing the exception
     * @param ir
     *            code containing the instruction that throws
     * @param thrown
     *            reference variable representing the value of the exception
     */
    private final void addAssignmentForThrownException(SSAInstruction i, IR ir, ReferenceVariable thrown, IClassHierarchy cha) {
        Set<IClass> notType = new LinkedHashSet<>();

        ISSABasicBlock bb = ir.getBasicBlockForInstruction(i);
        for (ISSABasicBlock succ : ir.getControlFlowGraph().getExceptionalSuccessors(bb)) {
            ReferenceVariable caught;
            if (succ.isCatchBlock()) {
                SSAGetCaughtExceptionInstruction catchIns = (SSAGetCaughtExceptionInstruction) succ.iterator().next();
                caught = getLocal(catchIns.getException(), ir);
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
}
