package android.statements;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import util.InstructionType;
import analysis.pointer.registrar.StatementRegistrar;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ssa.SSAInstruction;

public class AndroidStatementRegistrar extends StatementRegistrar {

    private final Map<IClass, Set<IMethod>> allCallbacks = new LinkedHashMap<>();

    @Override
    protected void handle(InstructionInfo info) {
        // Add callbacks if this is a new statement or ...
        SSAInstruction i = info.instruction;
        IMethod containingMethod = info.ir.getMethod();
        switch (InstructionType.forInstruction(i)) {
        case ARRAY_LENGTH:
            break;
        case ARRAY_LOAD:
            break;
        case ARRAY_STORE:
            break;
        case BINARY_OP:
            break;
        case BINARY_OP_EX:
            break;
        case CHECK_CAST:
            break;
        case COMPARISON:
            break;
        case CONDITIONAL_BRANCH:
            break;
        case CONVERSION:
            break;
        case GET_CAUGHT_EXCEPTION:
            break;
        case GET_FIELD:
            break;
        case GET_STATIC:
            break;
        case GOTO:
            break;
        case INSTANCE_OF:
            break;
        case INVOKE_INTERFACE:
            break;
        case INVOKE_SPECIAL:
            break;
        case INVOKE_STATIC:
            break;
        case INVOKE_VIRTUAL:
            break;
        case LOAD_METADATA:
            break;
        case MONITOR:
            break;
        case NEW_ARRAY:
            break;
        case NEW_OBJECT:
            break;
        case PHI:
            break;
        case PUT_FIELD:
            break;
        case PUT_STATIC:
            break;
        case RETURN:
            break;
        case SWITCH:
            break;
        case THROW:
            break;
        case UNARY_NEG_OP:
            break;
        }
        super.handle(info);
    }

    /**
     * Get a map from class to callbacks invoked on that class that were collected during the pointer-analysis.
     * 
     * @return callback methods collected
     */
    public Map<IClass, Set<IMethod>> getAllCallbacks() {
        return allCallbacks;
    }
}