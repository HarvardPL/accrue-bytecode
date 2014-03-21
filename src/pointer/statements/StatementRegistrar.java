package pointer.statements;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pointer.graph.LocalNode;
import pointer.graph.MethodSummaryNodes;
import types.TypeRepository;
import util.PrettyPrinter;

import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
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
    private final Map<LocalKey, LocalNode> locals = new LinkedHashMap<>();
    /**
     * Points-to graph nodes for static fields
     */
    private final Map<FieldReference, LocalNode> staticFields = new LinkedHashMap<>();
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
    private Set<IMethod> classInitializers = new LinkedHashSet<>();

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
        LocalNode array = getLocal(i.getArrayRef(), ir);
        LocalNode local = getLocal(i.getDef(), ir);
        statements.add(new ArrayToLocalStatement(local, array, i.getElementType(), ir));
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
        LocalNode array = getLocal(i.getArrayRef(), ir);
        LocalNode value = getLocal(i.getValue(), ir);
        statements.add(new LocalToArrayStatement(array, value, i.getElementType(), ir));
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
        LocalNode result = getLocal(i.getResult(), ir);
        LocalNode checkedVal = getLocal(i.getVal(), ir);
        statements.add(new LocalToLocalStatement(result, checkedVal, ir));
    }

    /**
     * v = o.f
     * 
     * @param i
     *            instruction getting the field
     * @param ir
     *            code for method containing the instruction
     */
    public void registerFieldAccess(SSAGetInstruction i, IR ir) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        LocalNode assignee = getLocal(i.getDef(), ir);
        if (i.isStatic()) {
            LocalNode field = getNodeForStaticField(i.getDeclaredField());
            statements.add(new StaticFieldToLocalStatement(assignee, field, ir));
            return;
        }

        LocalNode receiver = getLocal(i.getRef(), ir);
        statements.add(new FieldToLocalStatment(i.getDeclaredField(), receiver, assignee, ir));
    }

    /**
     * Handle an assignment into a field, o.f = v
     * 
     * @param i
     *            instruction for the assignment
     * @param ir
     *            code for the method containing the instruction
     */
    public void registerFieldAssign(SSAPutInstruction i, IR ir) {
        if (i.getDeclaredFieldType().isPrimitiveType() || TypeRepository.getType(i.getVal(), ir) == TypeReference.Null) {
            // Assigning into a primitive field, or assigning null
            return;
        }

        FieldReference f = i.getDeclaredField();
        LocalNode assignedValue = getLocal(i.getVal(), ir);

        if (i.isStatic()) {
            LocalNode fieldNode = getNodeForStaticField(f);
            statements.add(new LocalToStaticFieldStatement(fieldNode, assignedValue, ir));
        } else {
            LocalNode receiver = getLocal(i.getRef(), ir);
            statements.add(new LocalToFieldStatement(f, receiver, assignedValue, ir));
        }
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
        LocalNode resultNode = null;
        int numOfRetVals = i.getNumberOfReturnValues();
        if (!(numOfRetVals == 0 || numOfRetVals == 1)) {
            throw new RuntimeException("Don't handle mutliple return values.");
        }
        if (numOfRetVals > 0) {
            TypeReference returnType = TypeRepository.getType(i.getReturnValue(0), ir);
            if (!returnType.isPrimitiveType()) {
                resultNode = getLocal(i.getReturnValue(0), ir);
            }
        }

        List<LocalNode> actuals = new LinkedList<>();
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
        LocalNode exceptionNode = getLocal(i.getException(), ir);

        // Get the receiver if it is not static
        LocalNode receiver = i.isStatic() ? null : getLocal(i.getReceiver(), ir);

        Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, cha);
        if (i.isStatic()) {
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            statements.add(new StaticCallStatement(i.getCallSite(), ir, resolvedMethod, actuals, resultNode,
                    exceptionNode));
        } else if (i.isSpecial()) {
            assert resolvedMethods.size() == 1;
            IMethod resolvedMethod = resolvedMethods.iterator().next();
            statements.add(new SpecialCallStatement(i.getCallSite(), ir, resolvedMethod, receiver, actuals, resultNode,
                    exceptionNode));
        } else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            statements.add(new VirtualCallStatement(i.getCallSite(), ir, i.getDeclaredTarget(), receiver, actuals,
                    resultNode, exceptionNode, cha));
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
        LocalNode result = getLocal(i.getDef(), ir);
        // TODO Do we need to do anything with array dimensions, probably do
        statements.add(new NewStatement(result, i.getNewSite(), ir, cha));
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
        LocalNode assignee = getLocal(i.getDef(), ir);
        List<LocalNode> uses = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int arg = i.getUse(j);
            if (TypeRepository.getType(arg, ir) != TypeReference.Null) {
                LocalNode use = getLocal(i.getUse(j), ir);
                uses.add(use);
            }
        }
        if (uses.isEmpty()) {
            // All entries to the phi are null literals, no effect on pointer
            // analysis
            return;
        }
        statements.add(new PhiStatement(assignee, uses, ir));
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
        LocalNode result = getLocal(i.getResult(), ir);
        LocalNode summary = methods.get(ir.getMethod()).getReturnNode();
        statements.add(new ReturnStatement(result, summary, ir));
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
        // TODO technically you can throw "null" literals, but seems unlikely
        assert TypeRepository.getType(i.getException(), ir) != TypeReference.Null;

        LocalNode exception = getLocal(i.getException(), ir);
        statements.add(new ThrowStatement(exception, ir, ir.getBasicBlockForInstruction(i)));
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
    public LocalNode getLocal(int local, IR ir) {
        assert !TypeRepository.getType(local, ir).isPrimitiveType() : "No local nodes for primitives: "
                + PrettyPrinter.parseType(TypeRepository.getType(local, ir));

        LocalKey key = new LocalKey(local, ir);
        LocalNode node = locals.get(key);
        if (node == null) {
            TypeReference type = TypeRepository.getType(local, ir);
            node = freshLocal(PrettyPrinter.getPrinter(ir).stringForValue(local), type, false);
            locals.put(key, node);
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
    private LocalNode getNodeForStaticField(FieldReference field) {
        LocalNode node = staticFields.get(field);
        if (node == null) {
            if (field.getFieldType().isPrimitiveType()) {
                throw new RuntimeException(
                        "Trying to create reference variable for a static field with a primitive type.");
            }
            node = freshLocal(field.getName().toString(), field.getFieldType(), true);
            staticFields.put(field, node);
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
     * @param isStatic
     *            true if this is a static field
     * @return a new local points-to graph node
     */
    private LocalNode freshLocal(String debugString, TypeReference expectedType, boolean isStatic) {
        return new LocalNode(debugString, expectedType, isStatic);
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
     * Key uniquely identifying a local variable.
     */
    private static class LocalKey {
        /**
         * local ID
         */
        public final int value;
        /**
         * Code
         */
        public final IR ir;

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
            // TODO is this right
            IMethod resolvedMethod = cha.resolveMethod(inv.getDeclaredTarget());
            assert resolvedMethod != null : "No method found for " + inv.toString();
            targets = Collections.singleton(resolvedMethod);
        } else if (inv.isSpecial()) {
            // TODO check that this is right
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
}
