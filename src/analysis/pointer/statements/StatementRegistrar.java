package analysis.pointer.statements;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.pointer.graph.MethodSummaryNodes;
import analysis.pointer.graph.ReferenceVariable;

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
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
     * Class initializers
     */
    private final Set<IMethod> classInitializers = new LinkedHashSet<>();
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
    public void registerArrayLoad(SSAArrayLoadInstruction i, IR ir) {
        TypeReference t = i.getElementType();
        if (t.isPrimitiveType()) {
            // Assigning from a primitive array so result is not a pointer
            return;
        }
        ReferenceVariable array = getLocal(i.getArrayRef(), ir);
        ReferenceVariable local = getLocal(i.getDef(), ir);
        addStatement(new ArrayToLocalStatement(local, array, i.getElementType(), ir, i));
    }

    /**
     * v[j] = x, store into an array
     * 
     * @param i
     *            array store instruction
     * @param ir
     *            code for method containing the instruction
     */
    public void registerArrayStore(SSAArrayStoreInstruction i, IR ir) {
        TypeReference t = i.getElementType();
        if (t.isPrimitiveType() || TypeRepository.getType(i.getValue(), ir) == TypeReference.Null) {
            // Assigning into a primitive array so value is not a pointer, or
            // assigning null
            return;
        }
        ReferenceVariable array = getLocal(i.getArrayRef(), ir);
        ReferenceVariable value = getLocal(i.getValue(), ir);
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
    public void registerCheckCast(SSACheckCastInstruction i, IR ir) {
        if (TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // the cast value is null so no effect on pointer analysis
            return;
        }

        // This has the same effect as a copy, v = x
        // TODO throws class cast exception
        ReferenceVariable result = getLocal(i.getResult(), ir);
        ReferenceVariable checkedVal = getLocal(i.getVal(), ir);
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
    public void registerGetField(SSAGetInstruction i, IR ir, IClassHierarchy cha) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = getLocal(i.getDef(), ir);
        ReferenceVariable receiver = getLocal(i.getRef(), ir);
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
    public void registerGetStatic(SSAGetInstruction i, IR ir, IClassHierarchy cha) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = getLocal(i.getDef(), ir);
        ReferenceVariable field = getNodeForStaticField(i.getDeclaredField(), cha);
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
    public void registerPutField(SSAPutInstruction i, IR ir, IClassHierarchy cha) {
        if (i.getDeclaredFieldType().isPrimitiveType() || TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        FieldReference f = i.getDeclaredField();
        ReferenceVariable assignedValue = getLocal(i.getVal(), ir);
        ReferenceVariable receiver = getLocal(i.getRef(), ir);
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
    public void registerPutStatic(SSAPutInstruction i, IR ir, IClassHierarchy cha) {
        if (i.getDeclaredFieldType().isPrimitiveType() || TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        FieldReference f = i.getDeclaredField();
        ReferenceVariable assignedValue = getLocal(i.getVal(), ir);
        ReferenceVariable fieldNode = getNodeForStaticField(f, cha);
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
    public void registerInvoke(SSAInvokeInstruction i, IR ir, IClassHierarchy cha) {
        assert (i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1);

        ReferenceVariable resultNode = null;
        if (i.getNumberOfReturnValues() > 0) {
            TypeReference returnType = TypeRepository.getType(i.getReturnValue(0), ir);
            if (!returnType.isPrimitiveType()) {
                resultNode = getLocal(i.getReturnValue(0), ir);
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
                actuals.add(getLocal(i.getUse(0), ir));
            }
        }
        for (int j = 1; j < i.getNumberOfParameters(); j++) {
            if (TypeRepository.getType(i.getUse(j), ir).isPrimitiveType()) {
                actuals.add(null);
            } else {
                actuals.add(getLocal(i.getUse(j), ir));
            }
        }

        // TODO can we do better than one value for the exception
        // could create one on each type
        ReferenceVariable exceptionNode = getLocal(i.getException(), ir);

        // Get the receiver if it is not static
        ReferenceVariable receiver = i.isStatic() ? null : getLocal(i.getReceiver(), ir);

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
     * Handle an allocation of the form: "new Foo(...)"
     * 
     * @param i
     *            new instruction
     * @param ir
     *            code for the method containing the instruction
     */
    public void registerNew(SSANewInstruction i, IR ir, IClassHierarchy cha) {
        // all "new" instructions are assigned to a local
        ReferenceVariable result = getLocal(i.getDef(), ir);

        addStatement(new NewStatement(result, i.getNewSite(), cha, ir, i));
    }

    /**
     * Handle an SSA phi instruction, x = phi(x_1, x_2, ...)
     * 
     * @param i
     *            phi instruction
     * @param ir
     *            code for the method containing the instruction
     */
    public void registerPhiAssignment(SSAPhiInstruction i, IR ir) {
        TypeReference type = TypeRepository.getType(i.getDef(), ir);
        if (type.isPrimitiveType()) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = getLocal(i.getDef(), ir);
        List<ReferenceVariable> uses = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int arg = i.getUse(j);
            if (TypeRepository.getType(arg, ir) != TypeReference.Null) {
                ReferenceVariable use = getLocal(i.getUse(j), ir);
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
    public void registerReflection(SSALoadMetadataInstruction i, IR ir) {
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
    public void registerReturn(SSAReturnInstruction i, IR ir) {
        if (i.returnsPrimitiveType() || i.returnsVoid()
                || TypeRepository.getType(i.getResult(), ir) == TypeReference.Null) {
            // no pointers here
            return;
        }
        ReferenceVariable result = getLocal(i.getResult(), ir);
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
    public void registerThrow(SSAThrowInstruction i, IR ir) {
        ReferenceVariable exception = getLocal(i.getException(), ir);
        addStatement(new ThrowStatement(exception, ir, i));
    }

    /**
     * Get the points-to graph node for the given local in the given IR. The
     * local should not have a primitive type or null.
     * 
     * @param local
     *            local ID, the type of this should not be primitive or null
     * @param ir
     *            method intermediate representation
     * @return points-to graph node for the local
     */
    public ReferenceVariable getLocal(int local, IR ir) {
        assert !TypeRepository.getType(local, ir).isPrimitiveType() : "No local nodes for primitives: "
                + PrettyPrinter.parseType(TypeRepository.getType(local, ir));

        LocalKey key = new LocalKey(local, ir);
        ReferenceVariable node = locals.get(key);
        if (node == null) {
            TypeReference type = TypeRepository.getType(local, ir);
            node = freshLocal(PrettyPrinter.valString(ir, local), type);
            locals.put(key, node);
        }
        return node;
    }

    /**
     * Get a points-to graph node for an implicitly thrown exception/error
     * 
     * @param type
     *            type of the exception
     * @param i
     *            instruction that throws
     * @param ir
     *            method containing the instruction that throws
     * @return local node for an implicit throwable
     */
    public ReferenceVariable getImplicitExceptionNode(TypeReference type, SSAInstruction i, IR ir) {
        ImplicitThrowKey key = new ImplicitThrowKey(type, ir, i);
        ReferenceVariable node = implicitThrows.get(key);
        if (node == null) {
            node = freshLocal("IMPLICIT-" + PrettyPrinter.parseType(type), type);
            implicitThrows.put(key, node);
        }
        return node;
    }

    /**
     * Get the points-to graph node for the given static field
     * 
     * @param field
     *            field to get the node for
     * @return points-to graph node for the static field
     */
    public ReferenceVariable getNodeForStaticField(FieldReference field, IClassHierarchy cha) {
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
     * Create a fresh points-to graph local node
     * 
     * @param debugString
     *            string used for printing and debugging (e.g. the local
     *            variable name)
     * @param expectedType
     *            type of the local
     * @return a new local points-to graph node
     */
    private ReferenceVariable freshLocal(String debugString, TypeReference expectedType) {
        return new ReferenceVariable(debugString, expectedType, false);
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

    }

    /**
     * Get all points-to statements
     * 
     * @return set of all statements
     */
    public Set<PointsToStatement> getAllStatements() {
        return new LinkedHashSet<PointsToStatement>(statements);
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
     * Get the entry point for the code being analyzed
     * 
     * @return entry point for the analyzed code
     */
    public IMethod getEntryPoint() {
        return entryPoint;
    }

    /**
     * Get all methods that should be analyzed in the initial empty context
     * 
     * @return set of methods
     */
    public Set<IMethod> getInitialContextMethods() {
        Set<IMethod> ret = new LinkedHashSet<>(classInitializers);
        ret.add(getEntryPoint());
        return ret;
    }

    public void addClassInitializer(IMethod classInitializer) {
        classInitializers.add(classInitializer);
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
    public static Set<IMethod> resolveMethodsForInvocation(SSAInvokeInstruction inv, IClassHierarchy cha) {
        Set<IMethod> targets = null;
        if (inv.isStatic()) {
            IMethod resolvedMethod = cha.resolveMethod(inv.getDeclaredTarget());
            assert resolvedMethod != null : "No method found for " + inv.toString();
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
}
