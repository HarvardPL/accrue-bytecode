package pointer.statements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import pointer.LocalNode;
import types.TypeRepository;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * This class manages the registration of new points to graph statements, which
 * are then processed by the pointer analysis
 */
public class StatementRegistrar {

    /**
     * Points to graph nodes for local variables
     */
    private final Map<LocalKey, LocalNode> locals = new LinkedHashMap<>();
    /**
     * Points to graph nodes for static fields
     */
    private final Map<FieldReference, LocalNode> staticFields = new LinkedHashMap<>();
    /**
     * list of all points to statements TODO should this be a set?
     */
    private final List<PointsToStatement> statements = new LinkedList<>();

    /**
     * x = v[j], load from an array
     * 
     * @param i
     *            instruction
     * @param ir
     *            code for method containing the instruction
     */
    public void handleArrayLoad(SSAArrayLoadInstruction i, IR ir) {
        TypeReference t = i.getElementType();
        if (t.isPrimitiveType()) {
            // Assigning into a primitive array so value is not a pointer
            return;
        }
        LocalNode array = getLocal(i.getArrayRef(), ir);
        LocalNode local = getLocal(i.getDef(), ir);
        addArrayToLocalAssign(local, array, i.getElementType());
    }

    /**
     * v[j] = x, store into an array
     * 
     * @param i
     *            array store instruction
     * @param ir
     *            code for method containing the instruction
     */
    public void handleArrayStore(SSAArrayStoreInstruction i, IR ir) {
        TypeReference t = TypeRepository.getType(i.getValue(), ir);
        if (t.isPrimitiveType()) {
            // Assigning into a primitive array so value is not a pointer
            return;
        }
        LocalNode array = getLocal(i.getArrayRef(), ir);
        LocalNode value = getLocal(i.getValue(), ir);
        addLocalToArrayAssign(array, value, i.getElementType());
    }

    /**
     * Assignment into a catch block local
     * 
     * @param i
     *            catch block entry instruction
     * @param ir
     *            code for method containing the instruction
     */
    public void handleCatchAssignment(SSAGetCaughtExceptionInstruction i, IR ir) {
        getLocal(i.getException(), ir);
        // TODO Is there a way to get the actual argument?
        // They may have the same index in the IR symbol table, might have to
        // disambiguate i.getbbNumber()
    }

    /**
     * v = (Type) x
     * 
     * @param i
     *            cast instruction
     * @param ir
     *            code for method containing the instruction
     */
    public void handleCheckCast(SSACheckCastInstruction i, IR ir) {
        // This has the same effect as a copy, v = x
        // TODO throws class cast exception
        LocalNode result = getLocal(i.getResult(), ir);
        LocalNode checkedVal = getLocal(i.getVal(), ir);
        addLocalToLocalAssign(result, checkedVal);
    }

    /**
     * v = o.f
     * 
     * @param i
     *            instruction getting the field
     * @param ir
     *            code for method containing the instruction
     */
    public void handleFieldAccess(SSAGetInstruction i, IR ir) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }
        LocalNode assignee = getLocal(i.getDef(), ir);
        if (i.isStatic()) {
            LocalNode field = getNodeForStaticField(i.getDeclaredField());
            addLocalToLocalAssign(assignee, field);
            return;
        }

        LocalNode receiver = getLocal(i.getRef(), ir);
        addFieldToLocalAssign(i.getDeclaredField(), receiver, assignee);
    }

    /**
     * Handle an assignment into a field.
     * 
     * @param i
     *            instruction for the assignment
     * @param ir
     *            code for the method containing the instruction
     */
    public void handleFieldAssign(SSAPutInstruction i, IR ir) {
        if (i.getDeclaredFieldType().isPrimitiveType()) {
            // No pointers here
            return;
        }

        FieldReference f = i.getDeclaredField();
        LocalNode assignedValue = getLocal(i.getVal(), ir);

        if (i.isStatic()) {
            LocalNode fieldNode = getNodeForStaticField(f);
            addLocalToLocalAssign(fieldNode, assignedValue);
        } else {
            LocalNode receiver = getLocal(i.getRef(), ir);
            addLocalToFieldAssign(f, receiver, assignedValue);
        }
    }

    /**
     * Handle a virtual, static, special, or interface invocation
     * 
     * @param i
     *            invoke instruction
     * @param ir
     *            code for the method containing the instruction (the caller)
     */
    public void handleInvoke(SSAInvokeInstruction i, IR ir) {
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
        if (i.isStatic()) {
            // for non-static methods param(0) is the target
            // for static it is the first argument
            actuals.add(getLocal(i.getUse(0), ir));
        }
        for (int j = 1; j < i.getNumberOfParameters(); j++) {
            actuals.add(getLocal(i.getUse(j), ir));
        }

        // TODO can we do better than one value for the exception
        // there is a list of thrown types i.getExceptionTypes(), maybe use that
        // somehow
        // can use ir.iterateCatchInstructions(); to get exception handlers
        // or iterate over basic blocks in ir.getControlFlowGraph() to get
        // caught exceptions
        LocalNode exceptionNode = getLocal(i.getException(), ir);

        // Not a static call
        LocalNode receiver = i.isStatic() ? null : getLocal(i.getReceiver(), ir);

        if (i.isStatic()) {
            addStaticCall(i.getCallSite(), ir, i.getDeclaredTarget(), actuals, resultNode, exceptionNode);
        } else if (i.isSpecial()) {
            addSpecialCall(i.getCallSite(), ir, i.getDeclaredTarget(), receiver, actuals, resultNode, exceptionNode);
        } else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            addVirtualCall(i.getCallSite(), ir, i.getDeclaredTarget(), receiver, actuals, resultNode, exceptionNode);
        }
    }

    /**
     * Handle an assignment into a local variable.
     * 
     * @param i
     *            assignment instruction
     * @param ir
     *            code for the method containing the instruction
     */
    public void handleLocalAssignment(SSAInstruction i, IR ir) {
        TypeReference type = TypeRepository.getType(i.getDef(), ir);
        if (type.isPrimitiveType()) {
            // No pointers here
            return;
        }
        // TODO direct copies seem to be impossible
        // so there is no points information transferred in a local assignment
        // statement.
        // TODO Might be missing some other cases.
        freshLocal(ir.getSymbolTable().getValueString(i.getDef()), type, false);
    }

    /**
     * Handle an allocation of the form: "new Foo(...)"
     * 
     * @param i
     *            new instruction
     * @param ir
     *            code for the method containing the instruction
     */
    public void handleNew(SSANewInstruction i, IR ir) {
        // all "new" instructions are assigned to a local
        LocalNode result = getLocal(i.getDef(), ir);
        // TODO Do we need to do anything with array dimensions, probably do
        // TODO How are constructors handled, I think they are called on the
        // result of the new. The new allocates and then a method is called.
        addNewStatement(result, i.getNewSite(), ir);
    }

    /**
     * Handle an SSA phi instruction, x = phi(x_1, x_2, ...)
     * 
     * @param i
     *            phi instruction
     * @param ir
     *            code for the method containing the instruction
     */
    public void handlePhiAssignment(SSAPhiInstruction i, IR ir) {
        TypeReference type = TypeRepository.getType(i.getDef(), ir);
        if (type.isPrimitiveType()) {
            // No pointers here
            return;
        }
        LocalNode assignee = getLocal(i.getDef(), ir);
        List<LocalNode> uses = new ArrayList<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            LocalNode use = getLocal(i.getUse(j), ir);
            uses.add(use);
        }
        addPhiStatement(assignee, uses);
    }

    /**
     * Add assignment to a local from an array
     * 
     * @param local
     *            assignee
     * @param array
     *            array assigning from
     * @param elementType
     *            base type for array
     */
    private void addArrayToLocalAssign(LocalNode local, LocalNode array, TypeReference elementType) {
        statements.add(new ArrayToLocalStatement(local, array, elementType));
    }

    /**
     * Add an assignment from a non-static field into a local
     * 
     * @param declaredField
     *            field assigned from
     * @param value
     *            local assigned to
     * @param receiver
     *            target or field dereference
     */
    private void addFieldToLocalAssign(FieldReference declaredField, LocalNode receiver, LocalNode value) {
        statements.add(new FieldToLocalStatment(declaredField, receiver, value));
    }

    /**
     * Assign a local into an array
     * 
     * @param array
     *            node for array
     * @param value
     *            node for the value being assigned
     */
    private void addLocalToArrayAssign(LocalNode array, LocalNode value, TypeReference baseType) {
        statements.add(new LocalToArrayStatement(array, value, baseType));
    }

    /**
     * Add an assignment statement of the form, left = right
     * 
     * @param left
     *            local node for assignee
     * @param right
     *            local node for assigned value
     */
    private void addLocalToLocalAssign(LocalNode left, LocalNode right) {
        statements.add(new LocalToLocalStatement(left, right));
    }

    /**
     * Add an assignment to an object (i.e. non-static) field, target.field =
     * assigned
     * 
     * @param field
     *            field in a class
     * @param receiver
     *            local node for the target of the field dereference
     * @param assigned
     *            local node for the assigned value
     */
    private void addLocalToFieldAssign(FieldReference field, LocalNode receiver, LocalNode assigned) {
        statements.add(new LocalToFieldStatement(field, receiver, assigned));
    }

    /**
     * Add a statement for a new allocation
     * 
     * @param result
     *            result of the allocation
     * @param newSite
     *            site of the allocation
     * @param ir
     *            code where the allocation occurs
     */
    private void addNewStatement(LocalNode result, NewSiteReference newSite, IR ir) {
        statements.add(new NewStatement(result, newSite, ir));
    }

    /**
     * Add a call to a non-static method
     * 
     * @param callSite
     *            call site
     * @param ir
     *            code for the caller
     * @param callee
     *            method being called
     * @param receiver
     *            node for receiver of the call
     * @param actuals
     *            nodes for the actual arguments (in order)
     * @param resultNode
     *            node for the return result, will be null if the method has a
     *            void return or returns a primitive.
     * @param exceptionNode
     *            node for exceptions thrown by this call
     */
    private void addVirtualCall(CallSiteReference callSite, IR ir, MethodReference callee, LocalNode receiver,
            List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode) {
        statements.add(new NonstaticCallStatement(callSite, ir, callee, receiver, actuals, resultNode, exceptionNode));
    }

    /**
     * Add a phi statement, x = phi(x_1, x_2, ...)
     * 
     * @param assignee
     *            local being assigned into
     * @param uses
     *            all elements being merged (in the same order as in the
     *            instruction)
     */
    private void addPhiStatement(LocalNode assignee, List<LocalNode> uses) {
        statements.add(new PhiStatement(assignee, uses));
    }

    /**
     * Add a call to a non-static method
     * 
     * @param callSite
     *            call site
     * @param ir
     *            code for the caller
     * @param callee
     *            method being called
     * @param receiver
     *            node for receiver of the call
     * @param actuals
     *            nodes for the actual arguments (in order)
     * @param resultNode
     *            node for the return result, will be null if the method has a
     *            void return or returns a primitive.
     * @param exceptionNode
     *            node for exceptions thrown by this call
     */
    private void addSpecialCall(CallSiteReference callSite, IR ir, MethodReference callee, LocalNode receiver,
            List<LocalNode> actuals, LocalNode resultNode, LocalNode exceptionNode) {
        statements.add(new SpecialCallStatement(callSite, ir, callee, actuals, resultNode, exceptionNode));
    }

    /**
     * Add a call to a static method
     * 
     * @param callSite
     *            call site
     * @param ir
     *            code for the caller
     * @param callee
     *            method being called
     * @param actuals
     *            nodes for the actual arguments (in order)
     * @param resultNode
     *            node for the return result, will be null if the method has a
     *            void return or returns a primitive.
     * @param exceptionNode
     *            node for exceptions thrown by this call
     */
    private void addStaticCall(CallSiteReference callSite, IR ir, MethodReference callee, List<LocalNode> actuals,
            LocalNode resultNode, LocalNode exceptionNode) {
        statements.add(new StaticCallStatement(callSite, ir, callee, actuals, resultNode, exceptionNode));
    }

    /**
     * Get the points to graph node for the given local in the given IR
     * 
     * @param local
     *            local ID
     * @param ir
     *            method intermediate representation
     * @return points to graph node for the local
     */
    private LocalNode getLocal(int local, IR ir) {
        LocalKey key = new LocalKey(local, ir);
        LocalNode node = locals.get(key);
        if (node == null) {
            TypeReference type = TypeRepository.getType(local, ir);
            if (type.isPrimitiveType()) {
                throw new RuntimeException("Trying to create reference variable for a local with a primitive type.");
            }
            node = freshLocal(ir.getSymbolTable().getValueString(local), type, false);
            locals.put(key, node);
        }
        return node;
    }

    /**
     * Get the points to graph node for the given static field
     * 
     * @param field
     *            field to get the node for
     * @return points to graph node for the static field
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
     * Create a fresh points to graph local node
     * 
     * @param debugString
     *            string used for printing and debugging (e.g. the local
     *            variable name)
     * @param expectedType
     *            type of the local
     * @param isStatic
     *            true if this is a static field
     * @return a new local points to graph node
     */
    private LocalNode freshLocal(String debugString, TypeReference expectedType, boolean isStatic) {
        return new LocalNode(debugString, expectedType, isStatic);
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
            result = prime * result + ((ir == null) ? 0 : System.identityHashCode(ir));
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
}
