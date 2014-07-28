package android.intent;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import util.print.PrettyPrinter;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.util.Unit;
import analysis.string.AbstractString;
import analysis.string.StringAnalysisResults;
import android.content.Intent;
import android.intent.model.AbstractComponentName;
import android.intent.model.AbstractIntent;
import android.intent.statements.ComponentNameInitStatement;
import android.intent.statements.CopyComponentNameStatement;
import android.intent.statements.CopyIntentStatement;
import android.intent.statements.IntentAddCategoryStatement;
import android.intent.statements.IntentSetActionStatement;
import android.intent.statements.IntentSetAndNormalizeDataStatement;
import android.intent.statements.IntentSetAndNormalizeTypeStatement;
import android.intent.statements.IntentSetComponentNameStatement;
import android.intent.statements.IntentSetDataStatement;
import android.intent.statements.IntentSetPackageStatement;
import android.intent.statements.IntentSetTypeStatement;
import android.intent.statements.IntentStatement;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
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
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

/**
 * Flow-insensitive intra-procedural generation of statements that will be solved to estimate the values of the fields
 * of android.content.Intent objects
 */
public class CollectIntentStatements extends InstructionDispatchDataFlow<Unit> {

    private final IMethod m;
    private final IR ir;
    private final TypeRepository types;
    private final PrettyPrinter pp;
    private final StringAnalysisResults stringResults;
    private final Set<IntentStatement> statements;

    // Interesting types
    private static final TypeName INTENT = TypeName.findOrCreate("Landroid/content/Intent");
    private static final TypeName URI = TypeName.findOrCreate("Ljava/net/URI");
    private static final TypeName COMPONENT_NAME = TypeName.findOrCreate("Landroid/content/Intent");

    private static final TypeName CLASS = TypeName.findOrCreate("Ljava/lang/Class");
    private static final TypeName STRING = TypeName.findOrCreate("Ljava/lang/String");
    private static final TypeName CONTEXT = TypeName.findOrCreate("Landroid/content/Context");

    // Intent methods
    private static final String MAKE_MAIN_ACTIVITY = "makeMainActivity";
    private static final String MAKE_MAIN_SELECTOR_ACTIVITY = "makeMainSelectorActivity";
    private static final String MAKE_RESTART_ACTIVITY_TASK = "makeRestartActivityTask";
    private static final String SET_ACTION = "setAction";
    private static final String SET_DATA = "setData";
    private static final String SET_DATA_AND_NORMALIZE = "setDataAndNormalize";
    private static final String SET_TYPE = "setType";
    private static final String SET_TYPE_AND_NORMALIZE = "setTypeAndNormalize";
    private static final String SET_DATA_AND_TYPE = "setDataAndType";
    private static final String SET_DATA_AND_TYPE_AND_NORMALIZE = "setDataAndTypeAndNormalize";
    private static final String ADD_CATEGORY = "addCategory";
    private static final String SET_SELECTOR = "setSelector";
    private static final String SET_PACKAGE = "setPackage";
    private static final String SET_COMPONENT = "setComponent";
    private static final String SET_CLASS_NAME = "setClassName";
    private static final String SET_CLASS = "setClass";
    private static final String FILL_IN = "fillIn";

    // ComponentName methods
    private static final String CLONE = "clone";

    // URI methods
    private static final String NORMALIZE = "normalize";
    private static final String PARSE_SERVER_AUTHORITY = "parseServerAuthority";
    private static final String RELATIVIZE = "relativize";
    private static final String RESOLVE = "resolve";

    public CollectIntentStatements(IR ir, StringAnalysisResults stringResults) {
        super(true);
        this.m = ir.getMethod();
        this.ir = ir;
        this.types = new TypeRepository(ir);
        this.pp = new PrettyPrinter(ir);
        this.stringResults = stringResults;
        this.statements = new LinkedHashSet<>();
    }

    @Override
    protected Unit flowBinaryOp(SSABinaryOpInstruction i, Set<Unit> previousItems,
                                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowComparison(SSAComparisonInstruction i, Set<Unit> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowConversion(SSAConversionInstruction i, Set<Unit> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetCaughtException(SSAGetCaughtExceptionInstruction i, Set<Unit> previousItems,
                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowGetStatic(SSAGetInstruction i, Set<Unit> previousItems,
                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Unit flowInstanceOf(SSAInstanceofInstruction i, Set<Unit> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowPhi(SSAPhiInstruction i, Set<Unit> previousItems,
                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Unit flowPutStatic(SSAPutInstruction i, Set<Unit> previousItems,
                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Unit flowUnaryNegation(SSAUnaryOpInstruction i, Set<Unit> previousItems,
                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLength(SSAArrayLengthInstruction i, Set<Unit> previousItems,
                                                        ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                        ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayLoad(SSAArrayLoadInstruction i, Set<Unit> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowArrayStore(SSAArrayStoreInstruction i, Set<Unit> previousItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowBinaryOpWithException(SSABinaryOpInstruction i, Set<Unit> previousItems,
                                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                                  ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowCheckCast(SSACheckCastInstruction i, Set<Unit> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowConditionalBranch(SSAConditionalBranchInstruction i,
                                                              Set<Unit> previousItems,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGetField(SSAGetInstruction i, Set<Unit> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    private AbstractString getPackageNameFromContext(int contextValueNumber) {
        // XXX How do we track the Context type, this is not precise or sound!!!
        TypeReference contextType = types.getType(contextValueNumber);
        String packageString = contextType.getName().getPackage().toString().replace("/", ".");
        return AbstractString.create(packageString);
    }

    private AbstractComponentName createImplicitComponentName(int contextValueNumber, int classNameValueNumber) {
        AbstractString packageName = getPackageNameFromContext(contextValueNumber);
        return createImplicitComponentName(packageName, classNameValueNumber);
    }

    private AbstractComponentName createImplicitComponentName(AbstractString packageName, int classNameValueNumber) {
        AbstractString className = stringResults.getResultsForLocal(classNameValueNumber, m);
        return new AbstractComponentName(packageName, className);
    }

    private Map<ISSABasicBlock, Unit> flowInvoke(SSAInvokeInstruction i,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        MethodReference mr = i.getDeclaredTarget();
        String methodName = mr.getName().toString();
        TypeName type = mr.getDeclaringClass().getName();
        if (type.equals(INTENT)) {
            if (mr.isInit()) {
                // Intent.<init>()
                if (mr.getNumberOfParameters() == 1 && mr.getParameterType(0).getName().equals(STRING)) {
                    // Intent.<init>(String action)
                    addStatement(new IntentSetActionStatement(i.getReceiver(), i.getUse(1)));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                else if (mr.getNumberOfParameters() == 2 && mr.getParameterType(0).getName().equals(STRING)
                        && mr.getParameterType(1).getName().equals(URI)) {
                    // Intent.<init>(String action, Uri uri)
                    addStatement(new IntentSetActionStatement(i.getReceiver(), i.getUse(1)));
                    addStatement(new IntentSetDataStatement(i.getReceiver(), i.getUse(2)));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                else if (mr.getNumberOfParameters() == 2 && mr.getParameterType(0).getName().equals(CONTEXT)
                        && mr.getParameterType(1).getName().equals(CLASS)) {
                    // Intent.<init>(Context packageContext, Class<?> cls)
                    AbstractComponentName cn = createImplicitComponentName(i.getUse(1), i.getUse(2));
                    addStatement(new IntentSetComponentNameStatement(i.getReceiver(), cn));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                else if (mr.getNumberOfParameters() == 4) {
                    // Intent.<init>(String action, Uri uri, Context packageContext, Class<?> cls)
                    int receiver = i.getReceiver();
                    addStatement(new IntentSetActionStatement(receiver, i.getUse(1)));
                    addStatement(new IntentSetDataStatement(receiver, i.getUse(2)));
                    AbstractComponentName cn = createImplicitComponentName(i.getUse(1), i.getUse(2));
                    addStatement(new IntentSetComponentNameStatement(i.getReceiver(), cn));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                else if (mr.getNumberOfParameters() == 1 && mr.getParameterType(0).getName().equals(INTENT)) {
                    // Intent.<init>(Intent)
                    addStatement(new CopyIntentStatement(i.getReceiver(), i.getUse(1)));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                assert false : "UNREACHEABLE";
                throw new RuntimeException("Missing Intent.<init>? " + i);
            }
            else if (methodName.equals(MAKE_MAIN_ACTIVITY)) {
                // Intent.makeMainActivity(ComponentName mainActivity)
                //        Intent intent = new Intent(ACTION_MAIN);
                //        intent.setComponent(mainActivity);
                //        intent.addCategory(CATEGORY_LAUNCHER);
                AbstractString actionMain = AbstractString.create(Intent.ACTION_MAIN);
                AbstractString categoryLauncher = AbstractString.create(Intent.CATEGORY_LAUNCHER);
                AbstractIntent newIntent = new AbstractIntent();
                addStatement(new IntentSetActionStatement(newIntent, actionMain));
                addStatement(new IntentSetComponentNameStatement(newIntent, i.getUse(0)));
                addStatement(new IntentAddCategoryStatement(newIntent, categoryLauncher));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(MAKE_MAIN_SELECTOR_ACTIVITY)) {
                // Intent.makeMainSelectorActivity(String selectorAction, String selectorCategory)
                //        Intent intent = new Intent(ACTION_MAIN);
                //        intent.addCategory(CATEGORY_LAUNCHER);
                //        Intent selector = new Intent();
                //        selector.setAction(selectorAction);
                //        selector.addCategory(selectorCategory);
                //        intent.setSelector(selector);
                AbstractIntent newIntent = new AbstractIntent();
                addStatement(new IntentSetActionStatement(newIntent, i.getUse(0)));
                addStatement(new IntentAddCategoryStatement(newIntent, i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(MAKE_RESTART_ACTIVITY_TASK)) {
                // Intent.makeRestartActivityTask(ComponentName mainActivity)
                //        Intent intent = new Intent(ACTION_MAIN);
                //        intent.setComponent(mainActivity);
                //        intent.addCategory(CATEGORY_LAUNCHER);
                AbstractString actionMain = AbstractString.create(Intent.ACTION_MAIN);
                AbstractString categoryLauncher = AbstractString.create(Intent.CATEGORY_LAUNCHER);
                AbstractIntent newIntent = new AbstractIntent();
                addStatement(new IntentSetActionStatement(newIntent, actionMain));
                addStatement(new IntentSetComponentNameStatement(newIntent, i.getUse(0)));
                addStatement(new IntentAddCategoryStatement(newIntent, categoryLauncher));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_ACTION)) {
                // Intent.setAction(String action)
                addStatement(new IntentSetActionStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_DATA)) {
                // Intent.setData(Uri data)
                addStatement(new IntentSetDataStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_DATA_AND_NORMALIZE)) {
                // Intent.setDataAndNormalize(Uri data)
                // Normalize sets the Scheme to lowercase
                addStatement(new IntentSetAndNormalizeDataStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_TYPE)) {
                // Intent.setType(String type)
                addStatement(new IntentSetTypeStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_TYPE_AND_NORMALIZE)) {
                // Intent.setTypeAndNormalize(String type)
                // Intent.setType(String type)
                addStatement(new IntentSetAndNormalizeTypeStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_DATA_AND_TYPE)) {
                // Intent.setDataAndType(Uri data, String type)
                addStatement(new IntentSetDataStatement(i.getReceiver(), i.getUse(1)));
                addStatement(new IntentSetTypeStatement(i.getReceiver(), i.getUse(2)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_DATA_AND_TYPE_AND_NORMALIZE)) {
                // Intent.setDataAndTypeAndNormalize(Uri data, String type)
                addStatement(new IntentSetAndNormalizeDataStatement(i.getReceiver(), i.getUse(1)));
                addStatement(new IntentSetAndNormalizeTypeStatement(i.getReceiver(), i.getUse(2)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(ADD_CATEGORY)) {
                // Intent.addCategory(String category)
                addStatement(new IntentAddCategoryStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_SELECTOR)) {
                // Intent.setSelector(Intent selector)
                addStatement(new CopyIntentStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_PACKAGE)) {
                // Intent.setPackage(String packageName)
                addStatement(new IntentSetPackageStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_COMPONENT)) {
                // Intent.setComponent(ComponentName component)
                addStatement(new IntentSetComponentNameStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(SET_CLASS_NAME)) {
                if (mr.getParameterType(0).getName().equals(CONTEXT)) {
                    // Intent.setClassName(Context packageContext, String className)
                    AbstractComponentName cn = createImplicitComponentName(i.getUse(1), i.getUse(2));
                    addStatement(new IntentSetComponentNameStatement(i.getReceiver(), cn));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                else if (mr.getParameterType(0).getName().equals(STRING)) {
                    // Intent.setClassName(String packageName, String className)
                    AbstractString packageString = stringResults.getResultsForLocal(i.getUse(1), m);
                    AbstractComponentName cn = createImplicitComponentName(packageString, i.getUse(2));
                    addStatement(new IntentSetComponentNameStatement(i.getReceiver(), cn));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                assert false : "UNREACHEABLE";
                throw new RuntimeException("More than two methods called Intent.setClassName?" + i);
            }
            else if (methodName.equals(SET_CLASS)) {
                // Intent.setClass(Context packageContext, Class<?> cls)
                AbstractComponentName cn = createImplicitComponentName(i.getUse(1), i.getUse(2));
                addStatement(new IntentSetComponentNameStatement(i.getReceiver(), cn));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (methodName.equals(FILL_IN)) {
                // Intent.fillIn(Intent other, int flags) // Only fills in the missing fields, estimate that all could be copied
                addStatement(new CopyIntentStatement(i.getReceiver(), i.getUse(1)));
                return factToMap(Unit.VALUE, current, cfg);
            }

            // TODO parse all the strings the URI and create the intent as described
            // Intent.getIntent(String uri)
            // Intent.parseUri(String uri, int flags)
            // Intent.getIntentOld(String uri)

            // Opaque Intent could be anything
            // Intent.readFromParcel(Parcel in) // Opaque Intent could be anything
            // Intent.parseIntent(Resources resources, XmlPullParser parser, AttributeSet attrs)

            // If this becomes flow-sensitive then process this method
            // Intent.removeCategory(String category) ???
        }
        else if (type.equals(COMPONENT_NAME)) {
            if (mr.isInit()) {
                if (mr.getParameterType(0).getName().equals(STRING)) {
                    // ComponentName.<init>(String pkg, String cls)
                    AbstractString packageName = stringResults.getResultsForLocal(i.getUse(1), m);
                    AbstractString className = stringResults.getResultsForLocal(i.getUse(2), m);
                    addStatement(new ComponentNameInitStatement(i.getReceiver(), packageName, className));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                else if (mr.getParameterType(0).getName().equals(CONTEXT)) {
                    // ComponentName.<init>(Context pkg, String cls)
                    // ComponentName.<init>(Context pkg, Class<?> cls)
                    AbstractString packageName = getPackageNameFromContext(i.getUse(1));
                    AbstractString className = stringResults.getResultsForLocal(i.getUse(2), m);
                    addStatement(new ComponentNameInitStatement(i.getReceiver(), packageName, className));
                    return factToMap(Unit.VALUE, current, cfg);
                }
                assert false : "UNREACHEABLE";
                throw new RuntimeException("Missing ComponentName.<init>?" + i);
            }
            else if (methodName.equals(CLONE)) {
                // ComponentName.clone()
                if (i.hasDef()) {
                    addStatement(new CopyComponentNameStatement(i.getDef(), i.getReceiver()));
                }
                return factToMap(Unit.VALUE, current, cfg);
            }

            // TODO need to parse and create a new ComponentName
            // ComponentName.unflattenFromString(String str)

            // Opaque ComponentName could be anything
            // ComponentName.readFromParcel(Parcel in) // opaque
        }
        else if (type.equals(URI)) {
            if (mr.isInit()) {
                // URI.<init>(String spec)
                // URI.<init>(String scheme, String schemeSpecificPart, String fragment)
                // URI.<init>(String scheme, String userInfo, String host, int port, String path, String query, String fragment)
                // URI.<init>(String scheme, String host, String path, String fragment)
                // URI.<init>(String scheme, String authority, String path, String query, String fragment)
            }
            else if (methodName.equals(NORMALIZE)) {
                // URI.normalize()
            }
            else if (methodName.equals(PARSE_SERVER_AUTHORITY)) {
                // URI.parseServerAuthority()
            }
            else if (methodName.equals(RELATIVIZE)) {
                // URI.relativize(URI relative)
            }
            else if (methodName.equals(RESOLVE)) {
                if (mr.getParameterType(0).getName().equals(URI)) {
                    // URI.resolve(URI relative)
                }
                else if (mr.getParameterType(0).getName().equals(STRING)) {
                    // URI.resolve(String relative)
                }
            }
        }

        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeInterface(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                            ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                            ISSABasicBlock current) {
        return flowInvoke(i, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeSpecial(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return flowInvoke(i, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeStatic(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        return flowInvoke(i, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowInvokeVirtual(SSAInvokeInstruction i, Set<Unit> previousItems,
                                                          ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                          ISSABasicBlock current) {
        return flowInvoke(i, cfg, current);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowGoto(SSAGotoInstruction i, Set<Unit> previousItems,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowLoadMetadata(SSALoadMetadataInstruction i, Set<Unit> previousItems,
                                                         ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                         ISSABasicBlock current) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowMonitor(SSAMonitorInstruction i, Set<Unit> previousItems,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewArray(SSANewInstruction i, Set<Unit> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowNewObject(SSANewInstruction i, Set<Unit> previousItems,
                                                      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                      ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowPutField(SSAPutInstruction i, Set<Unit> previousItems,
                                                     ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                     ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowReturn(SSAReturnInstruction i, Set<Unit> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowSwitch(SSASwitchInstruction i, Set<Unit> previousItems,
                                                   ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                   ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowThrow(SSAThrowInstruction i, Set<Unit> previousItems,
                                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                  ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    @Override
    protected Map<ISSABasicBlock, Unit> flowEmptyBlock(Set<Unit> inItems,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        return factToMap(Unit.VALUE, current, cfg);
    }

    private void addStatement(IntentStatement is) {
        statements.add(is);
    }

    @Override
    protected void post(IR ir) {
        // TODO Auto-generated method stub

    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        // TODO Auto-generated method stub
        return false;
    }

}
