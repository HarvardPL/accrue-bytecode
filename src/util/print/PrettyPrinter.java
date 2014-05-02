package util.print;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import util.InstructionType;

import com.ibm.wala.classLoader.IBytecodeMethod;
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
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

/**
 * Pretty printer for WALA code (SSA), types, and methods. Finds variable names
 * from the original code (if any exists) and constructs strings that are closer
 * to Java code than JVM byte code.
 */
public class PrettyPrinter {

    /**
     * If true then this class records the pretty printer used for a given IR
     * and returns the same one on each request
     */
    private static final boolean MEMOIZE = true;

    /**
     * If {@value PrettyPrinter#MEMOIZE} is true store the pretty printers here
     * so that there is a single printer per method.
     */
    private static final Map<IR, PrettyPrinter> memo = new HashMap<>();

    /**
     * Get a string for the basic block
     * 
     * @param ir
     *            IR containing the basic block
     * @param bb
     *            Basic block to write out
     * @param prefix
     *            prepend this string to each instruction (e.g. "\t" for
     *            indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place
     *            each instruction on a new line)
     * @return String for pretty printed basic block
     */
    public static String basicBlockString(IR ir, ISSABasicBlock bb, String prefix, String postfix) {
        try (StringWriter sw = new StringWriter()) {
            writeBasicBlock(ir, bb, sw, prefix, postfix);
            return sw.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the line number for the first instruction in the basic block
     * containing <code>i</code>.
     * <p>
     * TODO this seems to be the best we can do for line numbers without an
     * instruction index
     * 
     * @param ir
     *            IR for method containing the instruction
     * @param i
     *            instruction to get the line number for
     * @return line number
     */
    public static int getApproxLineNumber(IR ir, SSAInstruction i) {
        ISSABasicBlock bb = ir.getBasicBlockForInstruction(i);
        int first = bb.getFirstInstructionIndex();
        if (ir.getMethod() instanceof IBytecodeMethod) {
            IBytecodeMethod method = (IBytecodeMethod) ir.getMethod();
            int bytecodeIndex = -1;
            try {
                bytecodeIndex = method.getBytecodeIndex(first);
            } catch (InvalidClassFileException e) {
                return -1;
            }
            int sourceLineNum = method.getLineNumber(bytecodeIndex);
            return sourceLineNum;
        }
        return -1;
    }

    /**
     * Get the pretty printer associated with the given IR
     * 
     * @param ir
     *            Code for method to get a printer for
     * @return pretty printer for the given method
     */
    private static PrettyPrinter getPrinter(IR ir) {
        if (!MEMOIZE) {
            return new PrettyPrinter(ir);
        }

        // Check memo for existing printer and return that if it exists or
        // create and memoize
        PrettyPrinter pp = memo.get(ir);
        if (pp != null) {
            return pp;
        }

        pp = new PrettyPrinter(ir);
        memo.put(ir, pp);
        return pp;
    }

    /**
     * Get the class name for the given instruction. If the class is anonymous
     * this will give the named superclass.
     * 
     * @param i
     *            instruction to get the class name for
     * @return name of i's class, if o is anonymous then the name of i's
     *         superclass
     */
    public static String getSimpleClassName(SSAInstruction i) {
        Class<?> c = i.getClass();
        return c.isAnonymousClass() ? c.getSuperclass().getSimpleName() : c.getSimpleName();
    }

    public static String instructionString(SSAInstruction instruction, IR ir) {
        PrettyPrinter pp = getPrinter(ir);
        return pp.instructionString(instruction);
    }

    /**
     * Get a string for the code for the given IR
     * 
     * @param ir
     *            code to print
     * @param prefix
     *            prepend this string to each instruction (e.g. "\t" for
     *            indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place
     *            each instruction on a new line)
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
     * Get the string representation of the given call graph node, this prints
     * the method and context that define the node
     * 
     * @param n
     *            node to get a string for
     * @return string for <code>n</code>
     */
    public static String parseCGNode(CGNode n) {
        return PrettyPrinter.parseMethod(n.getMethod()) + " in " + n.getContext();
    }
    
    /**
     * Get a pretty String for the given method
     * 
     * @param m
     *            method to get a string for
     * @return String for "m"
     */
    public static String parseMethod(IMethod m) {
        return parseMethod(m.getReference());
    }

    /**
     * Get a pretty String for the given method
     * 
     * @param m
     *            method to get a string for
     * @return String for "m"
     */
    public static String parseMethod(MethodReference m) {
        StringBuilder s = new StringBuilder();
        s.append(parseType(m.getDeclaringClass()));
        s.append("." + m.getName().toString());
        s.append("(");
        if (m.getNumberOfParameters() > 0) {
            Descriptor d = m.getDescriptor();
            TypeName[] n = d.getParameters();
            s.append(parseType(n[0].toString()));
            for (int j = 1; j < m.getNumberOfParameters(); j++) {
                s.append(", " + parseType(n[j].toString()));
            }
        }
        s.append(")");
        return s.toString();
    }

    /**
     * Get a pretty string for the given type reference, with no package names
     * 
     * @param type
     *            type to get a string for
     * @return String for "type"
     */
    public static String parseSimpleType(TypeReference type) {
        String fullType = parseType(type.getName().toString());
        String[] strings = fullType.split("\\.");
        return strings[strings.length - 1];
    }

    /**
     * <ul>
     * <li>C = char</li>
     * <li>D = double</li>
     * <li>F = float</li>
     * <li>I = int</li>
     * <li>J = long</li>
     * <li>S = short</li>
     * <li>Z = boolean</li>
     * <li>LClassName = ClassName</li>
     * <li>n = null-type</li>
     * <li>[ prefix = array type (append [])</li>
     * </ul>
     * 
     * @param type
     *            JVM encoding of the type
     * @return decoded name for the type
     */
    private static String parseType(String type) {
        String arrayString = "";
        while (type.startsWith("[")) {
            arrayString += "[]";
            type = type.substring(1);
        }
        String baseName = "";
        switch (type.substring(0, 1)) {
        case "B":
            baseName = "byte";
            break;
        case "L":
            baseName = type.substring(1);
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
            throw new RuntimeException(type.substring(0, 1) + " is an invalid type specifier.");
        }

        return baseName.replace("/", ".") + arrayString;
    }

    /**
     * Get a pretty string for the given type Reference
     * 
     * @param type
     *            type to get a string for
     * @return String for "type"
     */
    public static String parseType(TypeReference type) {
        return parseType(type.getName().toString());
    }

    /**
     * Print the part of the instruction to the right of the assignment operator
     * "=". Only valid for instructions that assign to local variable (i.e. not
     * for arraystore, putfield, or putstatic)
     * 
     * @param instruction
     *            instruction with local def to print the right side of
     * @param ir
     *            containing code
     * @return string for right side of the operation
     */
    public static String rightSideString(SSAInstruction instruction, IR ir) {
        PrettyPrinter pp = getPrinter(ir);
        return pp.rightSideString(instruction);
    }

    /**
     * Get a String name for the variable represented by the given value number
     * 
     * @param valueNumber
     *            value number for the variable to get the string representation
     *            for
     * @param ir
     *            IR for the method containing the variable represented by the
     *            value number
     * @return name of the given value
     */
    public static String valString(int valueNumber, IR ir) {
        PrettyPrinter pp = getPrinter(ir);
        return pp.valString(valueNumber);
    }

    /**
     * Write the basic block to the given writer
     * 
     * @param ir
     *            IR for the method containing the basic block
     * @param bb
     *            Basic block to write out
     * @param writer
     *            string will be written to this writer
     * @param prefix
     *            prepend this string to each instruction (e.g. "\t" for
     *            indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place
     *            each instruction on a new line)
     */
    public static void writeBasicBlock(IR ir, ISSABasicBlock bb, Writer writer, String prefix, String postfix) {
        PrettyPrinter pp = getPrinter(ir);
        for (SSAInstruction i : bb) {
            try {
                writer.write(prefix + pp.instructionString(i) + " (" + getSimpleClassName(i) + ") " + postfix);
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
     *            prepend this string to each instruction (e.g. "\t" for
     *            indentation)
     * @param postfix
     *            append this string to each instruction (e.g. "\n" to place
     *            each instruction on a new line)
     */
    public static void writeIR(IR ir, Writer writer, String prefix, String postfix) {
        new IRWriter(ir).write(writer, prefix, postfix);
    }

    /**
     * Symbol table to use for printing
     */
    private final SymbolTable st;

    /**
     * IR associated with this pretty printer
     */
    private final IR ir;

    /**
     * Create a new pretty printer
     * 
     * @param ir
     *            IR the pretty printer is for
     */
    private PrettyPrinter(IR ir) {
        this.ir = ir;
        this.st = ir.getSymbolTable();
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
            opString = "+";
            break;
        case "sub":
            opString = "-";
            break;
        case "mul":
            opString = "*";
            break;
        case "div":
            opString = "/";
            break;
        case "rem":
            opString = "%";
            break;
        case "and":
            opString = "&";
            break;
        case "or":
            opString = "|";
            break;
        case "xor":
            opString = "^";
            break;
        case "SHL":
            opString = "<<";
            break;
        case "SHR":
            opString = ">>";
            break;
        case "USHR":
            opString = ">>>";
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
        s.append("(" + parseType(types[0]) + ")");
        s.append(valString(value));
        return s.toString();
    }

    private String comparisonRight(SSAComparisonInstruction instruction) {
        return valString(instruction.getUse(0)) + " == "
                                        + valString(instruction.getUse(1));
    }

    private String conditionalBranchString(SSAConditionalBranchInstruction instruction) {
        StringBuffer sb = new StringBuffer();
        sb.append("if (" + valString(instruction.getUse(0)));
        String opString = "";
        switch (instruction.getOperator().toString()) {
        case "eq":
            opString = "==";
            break;
        case "ne":
            opString = "!=";
            break;
        case "lt":
            opString = "<";
            break;
        case "ge":
            opString = ">=";
            break;
        case "gt":
            opString = ">";
            break;
        case "le":
            opString = "<=";
            break;
        default:
            throw new IllegalArgumentException("operator not found " + instruction.getOperator().toString() + " for "
                                            + instruction);
        }
        sb.append(" " + opString + " ");
        sb.append(valString(instruction.getUse(1)) + ")");
        return sb.toString();
    }

    private String conversionRight(SSAConversionInstruction instruction) {
        String toType = parseType(instruction.getToType());
        return "(" + toType + ") " + valString(instruction.getUse(0));
    }

    /**
     * Get a variable name if one exists
     * 
     * @param valNum
     *            number of the variable to get the name for
     * @return the name
     */
    private String getActualName(int valNum) {
        @SuppressWarnings("deprecation")
        int lastInstructionNum = ir.getInstructions().length - 1;
        String[] justForDebug = ir.getLocalNames(lastInstructionNum, valNum);
        if (justForDebug == null) {
            if (!ir.getMethod().isStatic() && valNum == 1) {
                return "this";
            }
            return null;
        }
        if (justForDebug.length == 0) {
            return null;
        }
        if (justForDebug.length > 1) {
            System.err.println("multiple names for " + valNum + " in " + parseMethod(ir.getMethod()) + ": "
                                            + Arrays.toString(justForDebug));
        }
        return justForDebug[0];
    }

    private String getCaughtExceptionString(SSAGetCaughtExceptionInstruction instruction) {
        return instruction.toString(st);
    }

    private String getRight(SSAGetInstruction instruction) {
        StringBuilder sb = new StringBuilder();

        String receiver = null;
        if (instruction.isStatic()) {
            receiver = parseType(instruction.getDeclaredField().getDeclaringClass().getName().toString());
        } else {
            receiver = valString(instruction.getRef());
        }
        sb.append(receiver + ".");
        sb.append(instruction.getDeclaredField().getName());
        return sb.toString();
    }

    private String gotoString(SSAGotoInstruction instruction) {
        return "goto ...";
    }

    private String instanceofRight(SSAInstanceofInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append(valString(instruction.getUse(0)) + " instanceof ");
        sb.append(parseType(instruction.getCheckedType()));
        return sb.toString();
    }

    private String instructionString(SSAInstruction instruction) {
        InstructionType type = InstructionType.forInstruction(instruction);

        if (instruction.hasDef()) {
            String right = rightSideString(instruction);
            return valString(instruction.getDef()) + " = " + right;
        }
        
        switch (type) {
        case ARRAY_STORE:
            return arrayStoreString((SSAArrayStoreInstruction) instruction);
        case CONDITIONAL_BRANCH:
            return conditionalBranchString((SSAConditionalBranchInstruction) instruction);
        case GET_CAUGHT_EXCEPTION:
            return getCaughtExceptionString((SSAGetCaughtExceptionInstruction) instruction);
        case GOTO:
            return gotoString((SSAGotoInstruction) instruction);
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            return rightSideString(instruction);
        case MONITOR:
            return monitorString((SSAMonitorInstruction) instruction);
        case PUT_FIELD:
        case PUT_STATIC:
            return putString((SSAPutInstruction) instruction);
        case RETURN:
            return returnString((SSAReturnInstruction) instruction);
        case SWITCH:
            return switchString((SSASwitchInstruction) instruction);
        case THROW:
            return throwString((SSAThrowInstruction) instruction);
        case ARRAY_LENGTH:
        case ARRAY_LOAD:
        case BINARY_OP:
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
        throw new RuntimeException("Unexpected instruction type: " + type + " for " + instruction);
    }

    private String invokeRight(String receiver, String params, SSAInvokeInstruction instruction) {
        StringBuilder sb = new StringBuilder();

        MethodReference mr = instruction.getDeclaredTarget();

        sb.append(receiver);
        sb.append(".");
        sb.append(mr.getName());
        sb.append("(" + params + ")");
        sb.append(" throws " + valString(instruction.getException()));
        return sb.toString();
    }

    private String invokeSpecialRight(SSAInvokeInstruction instruction) {
        String receiver = valString(instruction.getReceiver());
        MethodReference mr = instruction.getDeclaredTarget();
        if (mr.isInit()) {
            receiver = "((" + parseType(instruction.getDeclaredTarget().getDeclaringClass()) + ")" + receiver + ")";
        }
        String params = instruction.getNumberOfParameters() == 1 ? "" : paramsString(1, instruction);
        return invokeRight(receiver, params, instruction);
    }
    
    private String invokeStaticRight(SSAInvokeInstruction instruction) {
        // can resolve actual target statically using the class hierarchy;
        // this is the actual invocation though
        String receiver = parseType(instruction.getDeclaredTarget().getDeclaringClass().getName().toString());
        String params = instruction.getNumberOfParameters() == 0 ? "" : paramsString(0, instruction);
        return invokeRight(receiver, params, instruction);
    }

    private String invokeVirtualRight(SSAInvokeInstruction instruction) {
        String receiver = valString(instruction.getReceiver());
        String params = instruction.getNumberOfParameters() == 1 ? "" : paramsString(1, instruction);
        return invokeRight(receiver, params, instruction);
    }

    private String loadMetadataRight(SSALoadMetadataInstruction instruction) {
        return "load_metadata: " + instruction.getToken() + ", " + instruction.getType();
    }

    private String monitorString(SSAMonitorInstruction instruction) {
        return instruction.toString(st);
    }

    private String newRight(SSANewInstruction instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("new ");
        TypeReference type = instruction.getConcreteType();
        while (type.isArrayType()) {
            type = type.getArrayElementType();
        }
        sb.append(parseType(type.getName().toString()));

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
     * @return String containing the parameters to the invoked method separated
     *         by ", "
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
            receiver = parseType(instruction.getDeclaredField().getDeclaringClass().getName().toString());
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
        sb.append("return");

        int result = instruction.getResult();
        if (result != -1) {
            // -1 indicates a void return
            sb.append(" " + valString(result));
        }

        return sb.toString();
    }

    private String rightSideString(SSAInstruction instruction) {
        InstructionType type = InstructionType.forInstruction(instruction);
        
        if (!instruction.hasDef() || type.isInvoke()) {
            throw new RuntimeException(type + " has no right hand side or has a local on the right, "
                                            + instructionString(instruction));
        }
        
        switch (type) {
        case ARRAY_LENGTH:
            return arrayLengthRight((SSAArrayLengthInstruction) instruction);
        case ARRAY_LOAD:
            return arrayLoadRight((SSAArrayLoadInstruction) instruction);
        case BINARY_OP:
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
            assert !instruction.hasDef();
            throw new RuntimeException(type + " has no right hand side or has a local on the right. " + instructionString(instruction));
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
        return instruction.toString(st);
    }

    private String unaryOpRight(SSAUnaryOpInstruction instruction) {
        if (instruction.getOpcode() == IUnaryOpInstruction.Operator.NEG) {
            return "-" + valString(instruction.getUse(0));
        } else {
            throw new RuntimeException("The only unary operation supported is negation.");
        }
    }

    /**
     * Get a String for the given value number
     * 
     * @param valueNumber
     *            value number for the variable to get the string representation
     *            for
     * @return String for the given value
     */
    private String valString(int valueNumber) {
        if (!ir.getMethod().isStatic() && st.getParameter(0) == valueNumber) {
            // The first parameter of non-static methods is "this"
            return "this";
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
            return c;
        }

        String ret = getActualName(valueNumber);
        if (ret != null) {
            return ret;
        }

        if (st.getValue(valueNumber) == null) {
            return "v" + valueNumber;
        } else {
            return st.getValue(valueNumber).toString();
        }
    }
}
