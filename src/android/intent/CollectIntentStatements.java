package android.intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import types.TypeRepository;
import analysis.AnalysisUtil;
import analysis.dataflow.InstructionDispatchDataFlow;
import analysis.dataflow.util.Unit;
import android.content.Intent;
import android.intent.model.AbstractComponentName;
import android.intent.model.AbstractIntent;
import android.intent.model.AbstractURI;
import android.intent.statements.ComponentNameConstantStatement;
import android.intent.statements.ComponentNameCopyStatement;
import android.intent.statements.ComponentNameInitStatement;
import android.intent.statements.ComponentNameMergeStatement;
import android.intent.statements.ComponentNameUnflattenStatement;
import android.intent.statements.IntentAddCategoryStatement;
import android.intent.statements.IntentConstantStatement;
import android.intent.statements.IntentCopyStatement;
import android.intent.statements.IntentMergeStatement;
import android.intent.statements.IntentSetActionStatement;
import android.intent.statements.IntentSetAndNormalizeDataStatement;
import android.intent.statements.IntentSetAndNormalizeTypeStatement;
import android.intent.statements.IntentSetComponentNameStatement;
import android.intent.statements.IntentSetDataStatement;
import android.intent.statements.IntentSetPackageStatement;
import android.intent.statements.IntentSetTypeStatement;
import android.intent.statements.IntentStatement;
import android.intent.statements.UriAppendStatement;
import android.intent.statements.UriConstantStatement;
import android.intent.statements.UriCopyStatement;
import android.intent.statements.UriInitStatement;
import android.intent.statements.UriMergeStatement;
import android.intent.statements.UriNormalizeSchemeStatement;
import android.net.Uri;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IField;
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
import com.ibm.wala.types.FieldReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;

/**
 * Flow-insensitive intra-procedural generation of statements that will be solved to estimate the values of the fields
 * of android.content.Intent objects
 */
public class CollectIntentStatements extends InstructionDispatchDataFlow<Unit> {

    private final IMethod m;
    private final TypeRepository types;
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
    private static final Object UNFLATTEN_FROM_STRING = "unflattenFromString";

    // URI methods
    private static final String WITH_APPENDED_PATH = "withAppendedPath";
    private static final String NORMALIZE_SCHEME = "normalizeScheme";
    private static final String FROM_PARTS = "fromParts";
    private static final String PARSE = "parse";
    private static final String EMPTY = "EMPTY";

    public static Set<IntentStatement> collect(IR ir) {
        CollectIntentStatements cis = new CollectIntentStatements(ir);
        for (int param : ir.getParameterValueNumbers()) {
            // Since this is an intra-procedural analysis we assume the string parameters can be anything
            TypeName type = cis.getTypeName(param);
            if (type.equals(INTENT)) {
                cis.addStatement(new IntentConstantStatement(param,AbstractIntent.ANY, cis.m));
            }
            else if (type.equals(URI)) {
                cis.addStatement(new UriConstantStatement(param,AbstractURI.ANY, cis.m));
            } else if (type.equals(COMPONENT_NAME)) {
                cis.addStatement(new ComponentNameConstantStatement(param, AbstractComponentName.ANY, cis.m));
            }
        }
        cis.dataflow(ir);
        return cis.statements;
    }

    private CollectIntentStatements(IR ir) {
        super(true);
        this.m = ir.getMethod();
        this.types = new TypeRepository(ir);
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
        FieldReference fr = i.getDeclaredField();
        if (fr.getName().toString().equals(EMPTY)) {
            IField f = AnalysisUtil.getClassHierarchy().resolveField(i.getDeclaredField());
            if (f == null) {
                System.err.println("Could not find field " + i.getDeclaredField());
            }
            assert f != null;
            assert f.getDeclaringClass() != null;
            assert f.getDeclaringClass().getName() != null;
            if (f.getDeclaringClass().getName().equals(URI)) {
                assert f.isStatic();
                assert f.isFinal();
                // def = Uri.EMPTY
                AbstractURI uri = AbstractURI.create(Collections.<Uri> singleton(Uri.EMPTY));
                addStatement(new UriConstantStatement(i.getDef(), uri, m));
                return Unit.VALUE;
            }
        }
        setDefToAny(i);
        return Unit.VALUE;
    }

    @Override
    protected Unit flowInstanceOf(SSAInstanceofInstruction i, Set<Unit> previousItems,
                                  ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        return Unit.VALUE;
    }

    private static Set<Integer> getAllUses(SSAPhiInstruction i) {
        Set<Integer> uses = new LinkedHashSet<>();
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            uses.add(i.getUse(j));
        }
        return uses;
    }

    @Override
    protected Unit flowPhi(SSAPhiInstruction i, Set<Unit> previousItems,
                           ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg, ISSABasicBlock current) {
        TypeName type = getTypeName(i.getDef());
        if (type.equals(INTENT)) {
            Set<Integer> toMerge = getAllUses(i);
            addStatement(new IntentMergeStatement(i.getDef(), toMerge, m));
        }
        else if (type.equals(URI)) {
            Set<Integer> toMerge = getAllUses(i);
            addStatement(new UriMergeStatement(i.getDef(), toMerge, m));
        }
        else if (type.equals(COMPONENT_NAME)) {
            Set<Integer> toMerge = getAllUses(i);
            addStatement(new ComponentNameMergeStatement(i.getDef(), toMerge, m));
        }
        return Unit.VALUE;
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
        setDefToAny(i);
        return factToMap(Unit.VALUE, current, cfg);
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
        TypeName defType = getTypeName(i.getDef());
        TypeName useType = getTypeName(i.getUse(0));
        if (defType.equals(useType)) {
            if (defType.equals(INTENT)) {
                addStatement(new IntentCopyStatement(i.getDef(), i.getUse(0), m));
            }
            else if (defType.equals(COMPONENT_NAME)) {
                addStatement(new ComponentNameCopyStatement(i.getDef(), i.getUse(0), m));
            }
            else if (defType.equals(URI)) {
                addStatement(new UriCopyStatement(i.getDef(), i.getUse(0), m));
            }
        }
        return factToMap(Unit.VALUE, current, cfg);
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
        setDefToAny(i);
        return factToMap(Unit.VALUE, current, cfg);
    }

    private String getPackageNameFromContext(int contextValueNumber) {
        // XXX How do we track the Context type, this is not precise or sound!!!
        TypeReference contextType = types.getType(contextValueNumber);
        return contextType.getName().getPackage().toString().replace("/", ".");
    }

    private Map<ISSABasicBlock, Unit> flowInvokeIntent(String methodName, MethodReference mr, SSAInvokeInstruction i,
                                                       ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                       ISSABasicBlock current) {
        if (mr.isInit()) {
            if (mr.getNumberOfParameters() == 0) {
                // Intent.<init>()
                addStatement(new IntentConstantStatement(i.getReceiver(), AbstractIntent.NONE, m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (mr.getNumberOfParameters() == 1 && mr.getParameterType(0).getName().equals(STRING)) {
                // Intent.<init>(String action)
                addStatement(new IntentSetActionStatement(i.getReceiver(), i.getUse(1), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (mr.getNumberOfParameters() == 2 && mr.getParameterType(0).getName().equals(STRING)
                    && mr.getParameterType(1).getName().equals(URI)) {
                // Intent.<init>(String action, Uri uri)
                addStatement(new IntentSetActionStatement(i.getReceiver(), i.getUse(1), m));
                addStatement(new IntentSetDataStatement(i.getReceiver(), i.getUse(2), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (mr.getNumberOfParameters() == 2 && mr.getParameterType(0).getName().equals(CONTEXT)
                    && mr.getParameterType(1).getName().equals(CLASS)) {
                // Intent.<init>(Context packageContext, Class<?> cls)
                String packageName = getPackageNameFromContext(i.getUse(1));
                addStatement(new IntentSetComponentNameStatement(i.getReceiver(), packageName, i.getUse(2), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (mr.getNumberOfParameters() == 4) {
                // Intent.<init>(String action, Uri uri, Context packageContext, Class<?> cls)
                int receiver = i.getReceiver();
                addStatement(new IntentSetActionStatement(receiver, i.getUse(1), m));
                addStatement(new IntentSetDataStatement(receiver, i.getUse(2), m));
                String packageName = getPackageNameFromContext(i.getUse(3));
                addStatement(new IntentSetComponentNameStatement(i.getReceiver(), packageName, i.getUse(4), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (mr.getNumberOfParameters() == 1 && mr.getParameterType(0).getName().equals(INTENT)) {
                // Intent.<init>(Intent)
                addStatement(new IntentCopyStatement(i.getReceiver(), i.getUse(1), m));
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
            //        return intent;
            if (i.hasDef()) {
                String actionMain = Intent.ACTION_MAIN;
                String categoryLauncher = Intent.CATEGORY_LAUNCHER;
                addStatement(new IntentSetActionStatement(i.getDef(), actionMain, m));
                addStatement(new IntentSetComponentNameStatement(i.getDef(), i.getUse(0), m));
                addStatement(new IntentAddCategoryStatement(i.getDef(), categoryLauncher, m));
            }
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
            //        return intent;
            if (i.hasDef()) {
                String actionMain = Intent.ACTION_MAIN;
                String categoryLauncher = Intent.CATEGORY_LAUNCHER;
                addStatement(new IntentSetActionStatement(i.getDef(), actionMain, m));
                addStatement(new IntentAddCategoryStatement(i.getDef(), categoryLauncher, m));
                addStatement(new IntentSetActionStatement(i.getDef(), i.getUse(0), m));
                addStatement(new IntentAddCategoryStatement(i.getDef(), i.getUse(1), m));
            }
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(MAKE_RESTART_ACTIVITY_TASK)) {
            // Intent.makeRestartActivityTask(ComponentName mainActivity)
            //            Intent intent = makeMainActivity(mainActivity);
            //            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            //                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            //            return intent;
            if (i.hasDef()) {
                String actionMain = Intent.ACTION_MAIN;
                String categoryLauncher = Intent.CATEGORY_LAUNCHER;
                addStatement(new IntentSetActionStatement(i.getDef(), actionMain, m));
                addStatement(new IntentSetComponentNameStatement(i.getDef(), i.getUse(0), m));
                addStatement(new IntentAddCategoryStatement(i.getDef(), categoryLauncher, m));
            }
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_ACTION)) {
            // Intent.setAction(String action)
            addStatement(new IntentSetActionStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_DATA)) {
            // Intent.setData(Uri data)
            addStatement(new IntentSetDataStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_DATA_AND_NORMALIZE)) {
            // Intent.setDataAndNormalize(Uri data)
            // Normalize sets the Scheme to lowercase
            addStatement(new IntentSetAndNormalizeDataStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_TYPE)) {
            // Intent.setType(String type)
            addStatement(new IntentSetTypeStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_TYPE_AND_NORMALIZE)) {
            // Intent.setTypeAndNormalize(String type)
            // Intent.setType(String type)
            addStatement(new IntentSetAndNormalizeTypeStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_DATA_AND_TYPE)) {
            // Intent.setDataAndType(Uri data, String type)
            addStatement(new IntentSetDataStatement(i.getReceiver(), i.getUse(1), m));
            addStatement(new IntentSetTypeStatement(i.getReceiver(), i.getUse(2), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_DATA_AND_TYPE_AND_NORMALIZE)) {
            // Intent.setDataAndTypeAndNormalize(Uri data, String type)
            addStatement(new IntentSetAndNormalizeDataStatement(i.getReceiver(), i.getUse(1), m));
            addStatement(new IntentSetAndNormalizeTypeStatement(i.getReceiver(), i.getUse(2), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(ADD_CATEGORY)) {
            // Intent.addCategory(String category)
            addStatement(new IntentAddCategoryStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_SELECTOR)) {
            // Intent.setSelector(Intent selector)
            addStatement(new IntentCopyStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_PACKAGE)) {
            // Intent.setPackage(String packageName)
            addStatement(new IntentSetPackageStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_COMPONENT)) {
            // Intent.setComponent(ComponentName component)
            addStatement(new IntentSetComponentNameStatement(i.getReceiver(), i.getUse(1), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(SET_CLASS_NAME)) {
            if (mr.getParameterType(0).getName().equals(CONTEXT)) {
                // Intent.setClassName(Context packageContext, String className)
                String packageName = getPackageNameFromContext(i.getUse(1));
                addStatement(new IntentSetComponentNameStatement(i.getReceiver(), packageName, i.getUse(2), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (mr.getParameterType(0).getName().equals(STRING)) {
                // Intent.setClassName(String packageName, String className)
                addStatement(new IntentSetComponentNameStatement(i.getReceiver(), i.getUse(1), i.getUse(2), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            assert false : "UNREACHEABLE";
            throw new RuntimeException("More than two methods called Intent.setClassName?" + i);
        }
        else if (methodName.equals(SET_CLASS)) {
            // Intent.setClass(Context packageContext, Class<?> cls)
            String packageName = getPackageNameFromContext(i.getUse(1));
            addStatement(new IntentSetComponentNameStatement(i.getReceiver(), packageName, i.getUse(2), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(FILL_IN)) {
            // Intent.fillIn(Intent other, int flags) // Only fills in the missing fields, estimate that all could be copied
            addStatement(new IntentCopyStatement(i.getReceiver(), i.getUse(1), m));
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
        return null;
    }

    private Map<ISSABasicBlock, Unit> flowInvokeUri(String methodName, SSAInvokeInstruction i,
                                                    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                    ISSABasicBlock current) {
        if (methodName.equals(PARSE)) {
            // URI.parse(String uriString)
            addStatement(new UriInitStatement(i.getReceiver(), Collections.<Integer> singletonList(i.getUse(0)), m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(FROM_PARTS)) {
            // URI.fromParts(String scheme, String ssp, String fragment)
            List<Integer> args = new ArrayList<>(3);
            args.add(i.getUse(0));
            args.add(i.getUse(1));
            args.add(i.getUse(2));
            addStatement(new UriInitStatement(i.getReceiver(), args, m));
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(NORMALIZE_SCHEME)) {
            // URI.normalizeScheme()
            if (i.hasDef()) {
                addStatement(new UriNormalizeSchemeStatement(i.getDef(), i.getReceiver(), m));
            }
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(WITH_APPENDED_PATH)) {
            // URI.withAppendedPath(Uri baseUri, String pathSegment)
            if (i.hasDef()) {
                addStatement(new UriAppendStatement(i.getDef(), i.getReceiver(), i.getUse(1), m));
            }
            return factToMap(Unit.VALUE, current, cfg);
        }
        // TODO Could track Builders in a similar way to StringBuilder (flow sensitive analysis needed)
        // URI.buildUpon()
        // TODO Could track file names
        // URI.fromFile(File file)
        return null;
    }

    private Map<ISSABasicBlock, Unit> flowInvokeComponentName(String methodName, MethodReference mr,
                                                              SSAInvokeInstruction i,
                                                              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                              ISSABasicBlock current) {
        if (mr.isInit()) {
            if (mr.getParameterType(0).getName().equals(STRING)) {
                // ComponentName.<init>(String pkg, String cls)
                addStatement(new ComponentNameInitStatement(i.getReceiver(), i.getUse(1), i.getUse(2), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            else if (mr.getParameterType(0).getName().equals(CONTEXT)) {
                // ComponentName.<init>(Context pkg, String cls)
                // ComponentName.<init>(Context pkg, Class<?> cls)
                String packageName = getPackageNameFromContext(i.getUse(1));
                addStatement(new ComponentNameInitStatement(i.getReceiver(), packageName, i.getUse(2), m));
                return factToMap(Unit.VALUE, current, cfg);
            }
            assert false : "UNREACHEABLE";
            throw new RuntimeException("Missing ComponentName.<init>?" + i);
        }
        else if (methodName.equals(CLONE)) {
            // ComponentName.clone()
            if (i.hasDef()) {
                addStatement(new ComponentNameCopyStatement(i.getDef(), i.getReceiver(), m));
            }
            return factToMap(Unit.VALUE, current, cfg);
        }
        else if (methodName.equals(UNFLATTEN_FROM_STRING)) {
            // ComponentName.unflattenFromString(String str)
            if (i.hasDef()) {
                addStatement(new ComponentNameUnflattenStatement(i.getDef(), i.getUse(0), m));
            }
            return factToMap(Unit.VALUE, current, cfg);
        }

        // Opaque ComponentName could be anything
        // ComponentName.readFromParcel(Parcel in) // opaque
        return null;
    }

    private Map<ISSABasicBlock, Unit> flowInvoke(SSAInvokeInstruction i,
                                                 ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
                                                 ISSABasicBlock current) {
        MethodReference mr = i.getDeclaredTarget();
        String methodName = mr.getName().toString();
        TypeName type = mr.getDeclaringClass().getName();
        if (type.equals(INTENT)) {
            Map<ISSABasicBlock, Unit> res = flowInvokeIntent(methodName, mr, i, cfg, current);
            if (res != null) {
                return res;
            }
        }
        else if (type.equals(COMPONENT_NAME)) {
            Map<ISSABasicBlock, Unit> res = flowInvokeComponentName(methodName, mr, i, cfg, current);
            if (res != null) {
                return res;
            }
        }
        else if (type.equals(URI)) {
            Map<ISSABasicBlock, Unit> res = flowInvokeUri(methodName, i, cfg, current);
            if (res != null) {
                return res;
            }
        }

        // Another method, if it could return a ComponentName, URI, or Intent the result is ANY

        // XXX What about Intent objects passed in as arguments? If we want to be sound we would need to handle these.

        // XXX What about subclasses of Intent! There is one in the framework, android.content.pm.LabeledIntent

        setDefToAny(i);
        return factToMap(Unit.VALUE, current, cfg);
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
        setDefToAny(i);
        return factToMap(Unit.VALUE, current, cfg);
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
        // Creation is handled in the <init> methods
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
        // Nothing to see here
    }

    @Override
    protected boolean isUnreachable(ISSABasicBlock source, ISSABasicBlock target) {
        return false;
    }

    /**
     * If the instruction defines an object of one of the tracked types then set the abstract object to ANY
     *
     * @param i
     * @param previousItems
     * @param current
     * @return
     */
    private void setDefToAny(SSAInstruction i) {
        if (i.hasDef()) {
            int def = i.getDef();
            TypeName type = getTypeName(def);
            if (type.equals(INTENT)) {
                addStatement(new IntentConstantStatement(def, AbstractIntent.ANY, m));
            }
            else if (type.equals(URI)) {
                addStatement(new UriConstantStatement(def, AbstractURI.ANY, m));
            }
            else if (type.equals(COMPONENT_NAME)) {
                addStatement(new ComponentNameConstantStatement(def, AbstractComponentName.ANY, m));
            }
        }
    }

    private TypeName getTypeName(int valNum) {
        return types.getType(valNum).getName();
    }
}
