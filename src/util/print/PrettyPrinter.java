package util.print;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import util.InstructionType;

import com.ibm.wala.classLoader.IBytecodeMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.shrikeBT.IBinaryOpInstruction.IOperator;
import com.ibm.wala.shrikeBT.IUnaryOpInstruction;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLengthInstruction;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSABinaryOpInstruction;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAComparisonInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAConversionInstruction;
import com.ibm.wala.ssa.SSAGetCaughtExceptionInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAGotoInstruction;
import com.ibm.wala.ssa.SSAInstanceofInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SSALoadMetadataInstruction;
import com.ibm.wala.ssa.SSAMonitorInstruction;
import com.ibm.wala.ssa.SSANewInstruction;
import com.ibm.wala.ssa.SSAPhiInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.ssa.SSASwitchInstruction;
import com.ibm.wala.ssa.SSAThrowInstruction;
import com.ibm.wala.ssa.SSAUnaryOpInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;

/**
 * Pretty printer for WALA code (SSA), types, and methods. Finds variable names from the original code (if any exists)
 * and constructs strings that are closer to Java code than JVM byte code.
 */
public class PrettyPrinter {

    /**
     * canonical copies of strings
     */
    private static final Map<String, String> stringMemo = new HashMap<>();
    /**
     * Map from type to pretty printed name
     */
    private static final Map<TypeReference, String> typeMemo = new HashMap<>();
    /**
     * Map from method to pretty printed name
     */
    private static final Map<MethodReference, String> methodMemo = new HashMap<>();

    /**
     * Map from local variable IDs to names
     */
    private final Map<Integer, String> locals = new HashMap<>();
    /**
     * Map from instruction to string
     */
    private final Map<SSAInstruction, String> instructions = new HashMap<>();
    /**
     * Map from instruction to index created on demand if line numbers are needed
     */
    private Map<SSAInstruction, Integer> instructionIndex = null;
    /**
     * IR associated with this pretty printer
     */
    private final IR ir;
    /**
     * Symbol table for the IR
     */
    private final SymbolTable st;
    /**
     * Method this is the PrettyPrinter for
     */
    private final IMethod m;

    /**
     * Create a new pretty printer
     * 
     * @param ir
     *            IR the pretty printer is for
     */
    public PrettyPrinter(IR ir) {
        this.ir = ir;
        this.st = ir.getSymbolTable();
        this.m = ir.getMethod();
    }

    /**
     * Get a string for the basic block
     * 
     * @param bb
     *            Basic block to write out
     * @param prefix
     *            prepend this string to each instruction (e.g. "\t" for indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place each instruction on a new line)
     * @return String for pretty printed basic block
     */
    public String basicBlockString(ISSABasicBlock bb, String prefix, String postfix) {
        try (StringWriter sw = new StringWriter()) {
            writeBasicBlock(bb, sw, prefix, postfix);
            return sw.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the line number for the instruction or -1 if this was generated by the conversion to SSA (e.g. phi
     * instructions)
     * 
     * @param i
     *            instruction to get the line number for
     * @return line number
     */
    public int getLineNumber(SSAInstruction i) {
        if (instructionIndex == null) {
            instructionIndex = computeInstructionIndex();
        }

        Integer index = instructionIndex.get(i);
        if (index == null) {
            return -1;
        }
        if (m instanceof IBytecodeMethod) {
            IBytecodeMethod method = (IBytecodeMethod) m;
            try {
                int bytecodeIndex = method.getBytecodeIndex(index);
                return method.getLineNumber(bytecodeIndex);
            } catch (InvalidClassFileException e) {
                assert false : "InvalidClassFileException looking for line numbers in: " + methodString(m);
            }
        }
        return -1;
    }

    private Map<SSAInstruction, Integer> computeInstructionIndex() {
        Map<SSAInstruction, Integer> map = new HashMap<>();
        SSAInstruction[] ins = ir.getInstructions();

        int index = 0;
        for (SSAInstruction i : ins) {
            map.put(i, ++index);
        }
        return map;
    }

    /**
     * Get the class name for the given instruction. If the class is anonymous this will give the named superclass.
     * 
     * @param i
     *            instruction to get the class name for
     * @return name of i's class, if o is anonymous then the name of i's superclass
     */
    public static String getSimpleClassName(SSAInstruction i) {
        Class<?> c = i.getClass();
        return getCanonical(c.isAnonymousClass() ? c.getSuperclass().getSimpleName() : c.getSimpleName());
    }

    /**
     * Get a string for the code for the given IR
     * 
     * @param ir
     *            code to print
     * @param prefix
     *            prepend this string to each instruction (e.g. "\t" for indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place each instruction on a new line)
     */
    public static String irString(IR ir, String prefix, String postfix) {
        try (StringWriter writer = new StringWriter()) {
            writeIR(ir, writer, prefix, postfix);
            return writer.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the string representation of the given call graph node, this prints the method and context that define the
     * node
     * 
     * @param n
     *            node to get a string for
     * @return string for <code>n</code>
     */
    public static String cgNodeString(CGNode n) {
        return getCanonical(PrettyPrinter.methodString(n.getMethod()) + " in " + n.getContext());
    }

    /**
     * Get a pretty String for the given method
     * 
     * @param m
     *            method to get a string for
     * @return String for "m"
     */
    public static String methodString(IMethod m) {
        return methodString(m.getReference());
    }

    /**
     * Get a pretty String for the given method
     * 
     * @param m
     *            method to get a string for
     * @return String for "m"
     */
    public static String methodString(MethodReference m) {
        String name = methodMemo.get(m);
        if (name == null) {
            StringBuilder s = new StringBuilder();
            s.append(typeString(m.getReturnType()) + " ");
            s.append(typeString(m.getDeclaringClass()));
            s.append("." + m.getName().toString());
            s.append("(");
            if (m.getNumberOfParameters() > 0) {
                s.append(typeString(m.getParameterType(0)));
                for (int j = 1; j < m.getNumberOfParameters(); j++) {
                    s.append(", " + typeString(m.getParameterType(j)));
                }
            }
            s.append(")");
            name = s.toString();
            methodMemo.put(m, name);
        }
        return name;
    }

    /**
     * Get a pretty string for the type of the klass
     * 
     * @param klass
     *            klass to get a string for
     * @return String for the type of klass
     */
    public static String typeString(IClass klass) {
        return typeString(klass.getReference());
    }

    /**
     * Get a pretty string for the given type Reference
     * 
     * @param type
     *            type to get a string for
     * @return String for <code>type</code>
     */
    public static String typeString(TypeReference type) {
        String name = typeMemo.get(type);
        if (name == null) {

            // C = char
            // D = double
            // F = float
            // I = int
            // J = long
            // S = short
            // Z = boolean
            // LClassName = ClassName
            // n = null-type
            // [ prefix = array type (append [])

            String finalType = type.getName().toString();
            StringBuilder arrayString = new StringBuilder("");
            while (finalType.startsWith("[")) {
                arrayString.append("[]");
                finalType = finalType.substring(1);
            }
            String baseName = "";
            switch (finalType.substring(0, 1)) {
            case "B":
                baseName = "byte";
                break;
            case "L":
                baseName = finalType.substring(1);
                break;
            case "C":
                baseName = "char";
                break;
            case "D":
                baseName = "double";
                break;
            case "F":
                baseName = "float";
                break;
            case "I":
                baseName = "int";
                break;
            case "J":
                baseName = "long";
                break;
            case "S":
                baseName = "short";
                break;
            case "Z":
                baseName = "boolean";
                break;
            case "V":
                baseName = "void";
                break;
            case "n":
                baseName = "null-type";
                break;
            default:
                throw new RuntimeException(finalType.substring(0, 1) + " is an invalid type specifier.");
            }

            name = baseName.replace("/", ".") + arrayString;
            typeMemo.put(type, name);
        }
        return name;
    }

    /**
     * Write the basic block to the given writer
     * 
     * @param bb
     *            Basic block to write out
     * @param writer
     *            string will be written to this writer
     * @param prefix
     *            prepend this string to each instruction (e.g. "\t" for indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place each instruction on a new line)
     */
    public void writeBasicBlock(ISSABasicBlock bb, Writer writer, String prefix, String postfix) {
        for (SSAInstruction i : bb) {
            try {
                writer.write(prefix + instructionString(i) + " (" + getSimpleClassName(i) + ") " + postfix);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Print out the code for the given IR
     * 
     * @param ir
     *            code for the method to print
     * @param writer
     *            string will be written to this writer
     * @param prefix
     *            prepend this string to each instruction (e.g. "\t" for indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place each instruction on a new line)
     */
    public static void writeIR(IR ir, Writer writer, String prefix, String postfix) {
        new IRWriter(ir).write(writer, prefix, postfix);
    }

    private String arrayLengthRight(SSAArrayLengthInstruction instruction) {
        return valString(instruction.getUse(0)) + ".length";
    }

    private String arrayLoadRight(SSAArrayLoadInstruction instruction) {
        StringBuilder s = new StringBuilder();
        s.append(valString(instruction.getArrayRef()));
        s.append("[" + valString(instruction.getIndex()) + "]");
        return s.toString();
    }

    private String arrayStoreString(SSAArrayStoreInstruction instruction) {
        StringBuilder s = new StringBuilder();
        s.append(valString(instruction.getArrayRef()));
        s.append("[" + valString(instruction.getIndex()) + "] = ");
        s.append(valString(instruction.getValue()));
        return s.toString();
    }

    private String binaryOpRight(SSABinaryOpInstruction instruction) {
        int v0 = instruction.getUse(0);
        int v1 = instruction.getUse(1);
        IOperator op = instruction.getOperator();

        // ADD, SUB, MUL, DIV, REM, AND, OR, XOR;
        // SHL, SHR, USHR;
        String opString = "";
        switch (op.toString()) {
        case "add":
            opString = getCanonical("+");
            break;
        case "sub":
            opString = getCanonical("-");
            break;
        case "mul":
            opString = getCanonical("*");
            break;
        case "div":
            opString = getCanonical("/");
            break;
        case "rem":
            opString = getCanonical("%");
            break;
        case "and":
            opString = getCanonical("&");
            break;
        case "or":
            opString = getCanonical("|");
            break;
        case "xor":
            opString = getCanonical("^");
            break;
        case "SHL":
            opString = getCanonical("<<");
            break;
        case "SHR":
            opString = getCanonical(">>");
            break;
        case "USHR":
            opString = getCanonical(">>>");
            break;
        default:
            throw new IllegalArgumentException("Urecognized binary operator " + op);
        }

        return valString(v0) + " " + opString + " " + valString(v1);
    }

    private String checkCastRight(SSACheckCastInstruction instruction) {
        int value = instruction.getVal();
        StringBuffer s = new StringBuffer();

        TypeReference[] types = instruction.getDeclaredResultTypes();
        if (types.length != 1) {
            System.err.println("More than one return type for a cast.");
        }
        s.append("(" + typeString(types[0]) + ")");
        s.append(valString(value));
        return s.toString();
    }

    private String comparisonRight(SSAComparisonInstruction instruction) {
        return valString(instruction.getUse(0)) + getCanonical(" == ") + valString(instruction.getUse(1));
    }

    /**
     * Get the string for a given binary comparison operator.
     * 
     * @param op
     *            operator
     * @return String for the operator
     */
    public static String conditionalOperatorString(com.ibm.wala.shrikeBT.IConditionalBranchInstruction.IOperator op) {
        switch (op.toString()) {
        case "eq":
            return getCanonical("==");
        case "ne":
            return getCanonical("!=");
        case "lt":
            return getCanonical("<");
        case "ge":
            return getCanonical(">=");
        case "gt":
            return getCanonical(">");
        case "le":
            return getCanonical("<=");
        default:
            throw new IllegalArgumentException("operator not found " + op.toString());
        }
    }

    private String conditionalBranchString(SSAConditionalBranchInstruction instruction) {
        StringBuffer sb = new StringBuffer();
        sb.append("if (" + valString(instruction.getUse(0)));
        String opString = conditionalOperatorString(instruction.getOperator());
        sb.append(" " + opString + " ");
        sb.append(valString(instruction.getUse(1)) + ")");
        return sb.toString();
    }

    private String conversionRight(SSAConversionInstruction instruction) {
        String toType = typeString(instruction.getToType());
        return "(" + toType + ") " + valString(instruction.getUse(0));
    }

    /**
     * Get a variable name if one exists
     * <p>
     * TODO How expensive is it to get real variable names? Is it amortized?
     * 
     * @param valNum
     *            number of the variable to get the name for
     * @return the name
     */
    private String getActualName(int valNum) {
        int lastInstructionNum = ir.getInstructions().length - 1;
        String[] names = ir.getLocalNames(lastInstructionNum, valNum);
        if (names == null) {
            if (!m.isStatic() && valNum == 1) {
                return getCanonical("this");
            }
            return null;
        }
        if (names.length == 0) {
            return null;
        }
        if (names.length > 1) {
            assert false : "More than one name: " + Arrays.toString(names) + " for " + valNum + " in "
                                            + methodString(m);
        }
        return getCanonical(names[0]);
    }

    private String getCaughtExceptionString(SSAGetCaughtExceptionInstruction instruction) {
        return getCanonical("catch " + valString(instruction.getException()));
    }

    private String getRight(SSAGetInstruction instruction) {
        StringBuilder sb = new StringBuilder();

        String receiver = null;
        if (instruction.isStatic()) {
            receiver = typeString(instruction.getDeclaredField().getDeclaringClass());
        } else {
            receiver = valString(instruction.getRef());
        }
        sb.append(receiver + ".");
        sb.append(instruction.getDeclaredField().getName());
        return sb.toString();
    }

    private static String gotoString(@SuppressWarnings("unused") SSAGotoInstruction instruction) {
        return getCanonical("goto ...");
    }

    private String instanceofRight(SSAInstanceofInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append(valString(instruction.getUse(0)) + " instanceof ");
        sb.append(typeString(instruction.getCheckedType()));
        return sb.toString();
    }

    /**
     * Get a pretty printed version of the instruction
     * 
     * @param instruction
     *            instruction to print
     * @return string for the instruction
     */
    public String instructionString(SSAInstruction instruction) {
        String name = instructions.get(instruction);
        if (name != null) {
            return name;
        }

        InstructionType type = InstructionType.forInstruction(instruction);
        if (instruction.hasDef() && !(instruction instanceof SSAGetCaughtExceptionInstruction)) {
            String right = rightSideString(instruction);
            name = valString(instruction.getDef()) + " = " + right;
            instructions.put(instruction, name);
            return name;
        }

        switch (type) {
        case ARRAY_STORE:
            name = arrayStoreString((SSAArrayStoreInstruction) instruction);
            break;
        case CONDITIONAL_BRANCH:
            name = conditionalBranchString((SSAConditionalBranchInstruction) instruction);
            break;
        case GET_CAUGHT_EXCEPTION:
            name = getCaughtExceptionString((SSAGetCaughtExceptionInstruction) instruction);
            break;
        case GOTO:
            name = gotoString((SSAGotoInstruction) instruction);
            break;
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            name = rightSideString(instruction);
            break;
        case MONITOR:
            name = monitorString((SSAMonitorInstruction) instruction);
            break;
        case PUT_FIELD:
        case PUT_STATIC:
            name = putString((SSAPutInstruction) instruction);
            break;
        case RETURN:
            name = returnString((SSAReturnInstruction) instruction);
            break;
        case SWITCH:
            name = switchString((SSASwitchInstruction) instruction);
            break;
        case THROW:
            name = throwString((SSAThrowInstruction) instruction);
            break;
        case ARRAY_LENGTH:
        case ARRAY_LOAD:
        case BINARY_OP:
        case BINARY_OP_EX:
        case CHECK_CAST:
        case COMPARISON:
        case CONVERSION:
        case GET_FIELD:
        case GET_STATIC:
        case INSTANCE_OF:
        case LOAD_METADATA:
        case NEW_ARRAY:
        case NEW_OBJECT:
        case PHI:
        case UNARY_NEG_OP:
            throw new RuntimeException("Instruction should have def: " + type + " for " + instruction);
        }
        assert name != null;
        instructions.put(instruction, name);
        return name;
    }

    private String invokeRight(String receiver, String params, SSAInvokeInstruction instruction) {
        StringBuilder sb = new StringBuilder();

        MethodReference mr = instruction.getDeclaredTarget();

        sb.append(receiver);
        sb.append(".");
        sb.append(mr.getName());
        sb.append("(" + params + ")@");
        sb.append(instruction.getCallSite().getProgramCounter());
        sb.append(" throws " + valString(instruction.getException()));
        return sb.toString();
    }

    private String invokeSpecialRight(SSAInvokeInstruction instruction) {
        String receiver = valString(instruction.getReceiver());
        MethodReference mr = instruction.getDeclaredTarget();
        if (mr.isInit()) {
            receiver = "((" + typeString(instruction.getDeclaredTarget().getDeclaringClass()) + ")" + receiver + ")";
        }
        String params = instruction.getNumberOfParameters() == 1 ? "" : paramsString(1, instruction);
        return invokeRight(receiver, params, instruction);
    }

    private String invokeStaticRight(SSAInvokeInstruction instruction) {
        // can resolve actual target statically using the class hierarchy;
        // this is the actual invocation though
        String receiver = typeString(instruction.getDeclaredTarget().getDeclaringClass());
        String params = instruction.getNumberOfParameters() == 0 ? "" : paramsString(0, instruction);
        return invokeRight(receiver, params, instruction);
    }

    private String invokeVirtualRight(SSAInvokeInstruction instruction) {
        String receiver = valString(instruction.getReceiver());
        String params = instruction.getNumberOfParameters() == 1 ? "" : paramsString(1, instruction);
        return invokeRight(receiver, params, instruction);
    }

    private static String loadMetadataRight(SSALoadMetadataInstruction instruction) {
        return "load_metadata: " + instruction.getToken() + ", " + instruction.getType();
    }

    private String monitorString(SSAMonitorInstruction instruction) {
        StringBuilder sb = new StringBuilder("monitor");
        sb.append((instruction.isMonitorEnter() ? "enter " : "exit "));
        sb.append(valString(instruction.getRef()));
        return sb.toString();
    }

    private String newRight(SSANewInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("new ");
        TypeReference type = instruction.getConcreteType();
        while (type.isArrayType()) {
            type = type.getArrayElementType();
        }
        sb.append(typeString(type));

        for (int i = 0; i < instruction.getNumberOfUses(); i++) {
            sb.append("[" + valString(instruction.getUse(i)) + "]");
        }

        return sb.toString();
    }

    /**
     * Get a string for the parameters to a method
     * 
     * @param startIndex
     *            index of first parameter in "use" array
     * @param instruction
     *            invocation instruction
     * @return String containing the parameters to the invoked method separated by ", "
     */
    private String paramsString(int startIndex, SSAInvokeInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append(valString(instruction.getUse(startIndex)));
        for (int i = startIndex + 1; i < instruction.getNumberOfParameters(); i++) {
            sb.append(", " + valString(instruction.getUse(i)));
        }
        return sb.toString();
    }

    private String phiRight(SSAPhiInstruction instruction) {
        StringBuilder s = new StringBuilder();
        s.append("phi" + "(");
        int uses = instruction.getNumberOfUses();
        for (int i = 0; i < uses - 1; i++) {
            s.append(valString(instruction.getUse(i)) + ", ");
        }
        s.append(valString(instruction.getUse(uses - 1)) + ")");
        return s.toString();
    }

    private String putString(SSAPutInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        String receiver = null;
        if (instruction.isStatic()) {
            receiver = typeString(instruction.getDeclaredField().getDeclaringClass());
        } else {
            receiver = valString(instruction.getRef());
        }
        sb.append(receiver + ".");
        sb.append(instruction.getDeclaredField().getName() + " = ");
        sb.append(valString(instruction.getVal()));
        return sb.toString();
    }

    private String returnString(SSAReturnInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append(getCanonical("return"));

        int result = instruction.getResult();
        if (result != -1) {
            // -1 indicates a void return
            sb.append(" " + valString(result));
        }

        return sb.toString();
    }

    /**
     * Print the part of the instruction to the right of the assignment operator "=". Only valid for instructions that
     * assign to local variable (i.e. not for arraystore, putfield, or putstatic)
     * 
     * @param instruction
     *            Instruction with a right hand side and a local on the left hand side
     * @return String representation of an expression to the right of the equals
     */
    public String rightSideString(SSAInstruction instruction) {
        InstructionType type = InstructionType.forInstruction(instruction);

        if (!instruction.hasDef() && !type.isInvoke()) {
            throw new RuntimeException(type + " has no right hand side or has a local on the right, " + instruction);
        }

        switch (type) {
        case ARRAY_LENGTH:
            return arrayLengthRight((SSAArrayLengthInstruction) instruction);
        case ARRAY_LOAD:
            return arrayLoadRight((SSAArrayLoadInstruction) instruction);
        case BINARY_OP:
        case BINARY_OP_EX:
            return binaryOpRight((SSABinaryOpInstruction) instruction);
        case CHECK_CAST:
            return checkCastRight((SSACheckCastInstruction) instruction);
        case COMPARISON:
            return comparisonRight((SSAComparisonInstruction) instruction);
        case CONVERSION:
            return conversionRight((SSAConversionInstruction) instruction);
        case GET_FIELD:
        case GET_STATIC:
            return getRight((SSAGetInstruction) instruction);
        case INSTANCE_OF:
            return instanceofRight((SSAInstanceofInstruction) instruction);
        case INVOKE_INTERFACE:
            return invokeVirtualRight((SSAInvokeInstruction) instruction);
        case INVOKE_SPECIAL:
            return invokeSpecialRight((SSAInvokeInstruction) instruction);
        case INVOKE_STATIC:
            return invokeStaticRight((SSAInvokeInstruction) instruction);
        case INVOKE_VIRTUAL:
            return invokeVirtualRight((SSAInvokeInstruction) instruction);
        case LOAD_METADATA:
            return loadMetadataRight((SSALoadMetadataInstruction) instruction);
        case NEW_ARRAY:
        case NEW_OBJECT:
            return newRight((SSANewInstruction) instruction);
        case PHI:
            return phiRight((SSAPhiInstruction) instruction);
        case UNARY_NEG_OP:
            return unaryOpRight((SSAUnaryOpInstruction) instruction);
        case ARRAY_STORE:
        case CONDITIONAL_BRANCH:
        case GET_CAUGHT_EXCEPTION:
        case GOTO:
        case MONITOR:
        case PUT_FIELD:
        case PUT_STATIC:
        case RETURN:
        case SWITCH:
        case THROW:
            assert !instruction.hasDef() : type + " has no right hand side or has a local on the right. " + instruction;
            throw new RuntimeException(type + " has no right hand side or has a local on the right. " + instruction);
        }
        throw new RuntimeException("Unexpected instruction type: " + type + " for " + instruction);
    }

    private String switchString(SSASwitchInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("switch(" + valString(instruction.getUse(0)) + ") \\{\n");
        int[] cases = instruction.getCasesAndLabels();
        for (int i = 0; i < cases.length; i += 2) {
            int c = cases[i];
            int jumpTarget = cases[i + 1];
            // TODO do better than bytecode index which is meaningless
            // Basic block number would be nice, but not sure how to associate
            // successors with cases
            sb.append("  case " + c + ":" + " " + "goto " + jumpTarget + "\n");
        }
        sb.append("  default: goto " + instruction.getDefault() + "\n");
        sb.append("\\}");
        return sb.toString();
    }

    private String throwString(SSAThrowInstruction instruction) {
        return "throw " + valString(instruction.getException());
    }

    private String unaryOpRight(SSAUnaryOpInstruction instruction) {
        if (instruction.getOpcode() == IUnaryOpInstruction.Operator.NEG) {
            return "-" + valString(instruction.getUse(0));
        }
        throw new RuntimeException("The only unary operation supported is negation.");
    }

    /**
     * Get the name of the variable with the given value number
     * 
     * @param valueNumber
     *            value number for the variable
     * @return String for the given value
     */
    public String valString(int valueNumber) {
        String name = locals.get(valueNumber);
        if (name != null) {
            return name;
        }

        if (!m.isStatic() && st.getParameter(0) == valueNumber) {
            // The first parameter of non-static methods is "this"
            name = getCanonical("this");
            locals.put(valueNumber, name);
            return name;
        }

        if (st.isConstant(valueNumber)) {
            String c = st.getValue(valueNumber).toString();
            assert c.startsWith("#") : "All constant strings should start with #: " + c + " didn't";
            // The value is a constant trim off the leading #
            c = c.substring(1);

            if (st.isStringConstant(valueNumber)) {
                // Put string literals in quotes
                c = "\"" + c + "\"";
            }
            if (st.isBooleanConstant(valueNumber)) {
                // the boolean case does not trigger, they are just integers
                assert false;
            }
            name = getCanonical(c);
            locals.put(valueNumber, name);
            return name;
        }

        String ret = getActualName(valueNumber);
        if (ret != null) {
            name = getCanonical(ret);
            locals.put(valueNumber, name);
            return name;
        }

        if (st.getValue(valueNumber) == null) {
            name = getCanonical("v" + valueNumber);
            locals.put(valueNumber, name);
            return name;
        }
        name = getCanonical(st.getValue(valueNumber).toString());
        locals.put(valueNumber, name);
        return name;
    }

    /**
     * Get the canonical version of a string
     * 
     * @param s
     *            string to get
     * @return String that is .equal to the string passed in, but is the canonical version
     */
    public static String getCanonical(String s) {
        String canonical = stringMemo.get(s);
        if (canonical == null) {
            canonical = s;
            stringMemo.put(canonical, canonical);
        }
        return canonical;
    }
}
