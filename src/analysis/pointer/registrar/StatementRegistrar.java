package analysis.pointer.registrar;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import signatures.Signatures;
import types.TypeRepository;
import util.InstructionType;
import util.WorkQueue;
import util.print.CFGWriter;
import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import analysis.ClassInitFinder;
import analysis.dataflow.interprocedural.exceptions.PreciseExceptionResults;
import analysis.pointer.duplicates.RemoveDuplicateStatements.VariableIndex;
import analysis.pointer.engine.PointsToAnalysis;
import analysis.pointer.graph.ReferenceVariableCache;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.CallSiteProgramPoint;
import analysis.pointer.statements.LocalToFieldStatement;
import analysis.pointer.statements.NewStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.ProgramPoint;
import analysis.pointer.statements.StatementFactory;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.shrikeBT.IInvokeInstruction;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAArrayLoadInstruction;
import com.ibm.wala.ssa.SSAArrayStoreInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACheckCastInstruction;
import com.ibm.wala.ssa.SSAFieldAccessInstruction;
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
    private final IMethod entryMethod;

    /**
     * Map from method to the points-to statements generated from instructions in that method
     */
    private final ConcurrentMap<IMethod, Set<PointsToStatement>> statementsForMethod;
    /**
     * Map from method to the CallSiteProgramPoints that are in that method.
     */
    private final ConcurrentMap<IMethod, Set<CallSiteProgramPoint>> callSitesWithinMethod;
    /**
     * Map from method to the may-happen-after relationship for call-sites within the method
     */
    private final ConcurrentMap<IMethod, Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>>> callSiteOrdering;

    /**
     * Program point for the call to a class initialization method
     */
    private final ConcurrentMap<IMethod, CallSiteProgramPoint> programPointForClassInit;

    /**
     * Program point to statement map
     */
    private final ConcurrentMap<ProgramPoint, PointsToStatement> ppToStmtMap;
    /**
     * Program point in the entry method for the last class initializer or singleton allocation added
     */
    private ProgramPoint lastEntryMethodPP = null;

    /**
     * The total number of statements
     */
    private int size;

    /**
     * String and null literals that allocation statements have already been created for
     */
    private final Set<ReferenceVariable> handledLiterals;
    /**
     * If true then only one allocation will be made for each generated exception type. This will reduce the size of the
     * points-to graph (and speed up the points-to analysis), but result in a loss of precision for such exceptions.
     */
    final boolean useSingleAllocForGenEx;
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
     * If true then only print the successor graph for the main method.
     */
    private final boolean simplePrint;

    /**
     * If true then only one allocation will be made for any immutable wrapper class. This will reduce the size of the
     * points-to graph (and speed up the points-to analysis), but result in a loss of precision for these classes.
     * <p>
     * These classes are java.lang.String, all the primitive wrappers, and BigInteger and BigDecimal if they are not
     * subclassed.
     */
    private final boolean useSingleAllocForImmutableWrappers;

    /**
     * Whether to use a single allocation site for all classes in the Swing GUI libraries
     */
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
     * Map from method and instruction in that method to the program point for that instruction
     */
    private final Map<SSAInstruction, ProgramPoint> insToPP = new LinkedHashMap<>();
    /**
     * Program points for the entry to catch instructions
     */
    private final Set<ProgramPoint> catchEntries = new HashSet<>();
    /**
     * These are classes with no class initializer but with static fields
     */
    private final Set<IClass> noClinitWithStaticFields = new HashSet<>();

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
     * @param simplePrint If true then print less information to files for inspection
     */
    public StatementRegistrar(StatementFactory factory, boolean useSingleAllocForGenEx,
                              boolean useSingleAllocPerThrowableType, boolean useSingleAllocForPrimitiveArrays,
                              boolean useSingleAllocForStrings, boolean useSingleAllocForImmutableWrappers,
                              boolean simplePrint) {
        this.methods = AnalysisUtil.createConcurrentHashMap();
        this.statementsForMethod = AnalysisUtil.createConcurrentHashMap();
        this.callSitesWithinMethod = AnalysisUtil.createConcurrentHashMap();
        this.callSiteOrdering = AnalysisUtil.createConcurrentHashMap();
        this.programPointForClassInit = AnalysisUtil.createConcurrentHashMap();
        this.ppToStmtMap = AnalysisUtil.createConcurrentHashMap();
        this.singletonReferenceVariables = AnalysisUtil.createConcurrentHashMap();
        this.handledLiterals = AnalysisUtil.createConcurrentSet();
        this.entryMethod = AnalysisUtil.getFakeRoot();
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
                + useSingleAllocPerThrowableType);
        this.simplePrint = simplePrint;
        this.useSingleAllocForImmutableWrappers = useSingleAllocForImmutableWrappers;
        System.err.println("Singleton allocation site per immutable wrapper type: "
                + this.useSingleAllocForImmutableWrappers);
        System.err.println("Singleton allocation site per Swing library type: " + this.useSingleAllocForSwing);
    }

    /**
     * Handle all the instructions for a given method
     *
     * @param m method to register points-to statements for
     * @return whether the method was registered
     */
    public synchronized boolean registerMethod(IMethod m) {
        if (m.isAbstract()) {
            // Don't need to register abstract methods
            return false;
        }

        if (!this.registeredMethods.add(m)) {
            // Already registered
            return false;
        }

        // we need to register the method.
        IR ir = AnalysisUtil.getIR(m);
        if (ir == null) {
            // Native method with no signature
            assert m.isNative() : "No IR for non-native method: " + PrettyPrinter.methodString(m);
            this.registerNative(m);
            return true;
        }

        TypeRepository types = new TypeRepository(ir);
        PrettyPrinter pprint = new PrettyPrinter(ir);

        MethodSummaryNodes methSumm = this.findOrCreateMethodSummary(m);

        // Add edges from formal summary nodes to the local variables representing the method parameters
        ProgramPoint lastFormalPP = this.registerFormalAssignments(ir, this.rvFactory, types, pprint);

        Map<SSAInstruction, PPSubGraph> insToPPSubGraph = new HashMap<>();
        Map<ISSABasicBlock, ProgramPoint> bbToEntryPP = new HashMap<>();
        // Normal exits and exceptions thrown at call sites
        Set<ProgramPoint> callExits = new HashSet<>();

        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            for (SSAInstruction ins : bb) {
                if (ins.toString().contains("signatures/library/java/lang/String")
                        || ins.toString().contains("signatures/library/java/lang/AbstractStringBuilder")) {
                    System.err.println("\tWARNING: handling instruction mentioning String signature " + ins + " in "
                            + m);
                }
                handleInstruction(ins, ir, bb, insToPPSubGraph, types, pprint, methSumm, callExits);
                if (ppToStmtMap.containsKey(insToPPSubGraph.get(ins).normalExit())) {
                    // Record the pp for this instruction
                    insToPP.put(ins, insToPPSubGraph.get(ins).normalExit());
                }
                else if (ins instanceof SSAInvokeInstruction) {
                    insToPP.put(ins, insToPPSubGraph.get(ins).entry());
                }
                else if (ins instanceof SSAFieldAccessInstruction) {
                    insToPP.put(ins, insToPPSubGraph.get(ins).entry());
                }
            }
            // need to handle catch blocks here, getOrCreate...
            ProgramPoint pp = new ProgramPoint(m, "BB" + bb.getNumber() + " entry");
            bbToEntryPP.put(bb, pp);

            if (ir.getControlFlowGraph().entry() == bb) {
                lastFormalPP.addSucc(pp);
            }
        }

        // Chain together the subgraphs
        for (ISSABasicBlock bb : ir.getControlFlowGraph()) {
            addPPEdgesForBasicBlock(bb, methSumm, ir.getControlFlowGraph(), insToPPSubGraph, bbToEntryPP);
        }

        if (m.isClinit()) {
            // the following is to make sure static fields point to null before the clinit
            ProgramPoint entry = methSumm.getEntryPP();
            ProgramPoint prev = null;
            ProgramPoint first = null;
            for (IField f : m.getDeclaringClass().getAllStaticFields()) {
                if (f.getFieldTypeReference().isPrimitiveType()) {
                    // No need to do anything with primitive fields
                    continue;
                }
                if (!f.getDeclaringClass().equals(m.getDeclaringClass())) {
                    // The static field is not in the class we are initializing it will be initialized elsewhere
                    continue;
                }
                // The static field is in the class we are initializing
                ReferenceVariable staticField = rvFactory.getOrCreateStaticField(f.getReference());
                ProgramPoint pp = new ProgramPoint(m, staticField + " = null-lit");
                addStatement(stmtFactory.nullToLocal(staticField, pp));
                if (first == null) {
                    first = pp;
                }
                else {
                    assert prev != null;
                    prev.addSucc(pp);
                }
                prev = pp;
            }
            if (first != null) {
                // Some program points were added
                Set<ProgramPoint> succs = entry.succs();
                entry.clearSuccs();
                entry.addSucc(first);
                // prev is now the last pp added
                assert prev != null;
                prev.addSuccs(succs);
            }
        }

        // clean up graph
        cleanUpProgramPoints(methSumm, callExits);

        // now try to remove duplicates
        Set<PointsToStatement> oldStatements = this.getStatementsForMethod(m);

        // XXX AAJ - I'm not sure this is valid anymore, some of these statements need to be treated flow-sensitively
        //            int oldSize = oldStatements.size();
        //            OrderedPair<Set<PointsToStatement>, VariableIndex> duplicateResults = RemoveDuplicateStatements.removeDuplicates(oldStatements);
        //            Set<PointsToStatement> newStatements = duplicateResults.fst();
        //            replacedVariableMap.put(m, duplicateResults.snd());
        //            int newSize = newStatements.size();
        //
        //            removedStmts += (oldSize - newSize);
        //            this.statementsForMethod.put(m, newStatements);
        //            this.size += (newSize - oldSize);

        // XXX Don't replace anything for now
        replacedVariableMap.put(m, new VariableIndex());
        Set<PointsToStatement> newStatements = oldStatements;

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

        // int[] stats = df.cleanUpProgramPoints();
        // removedProgramPoints += stats[1];
        // totalProgramPoints += stats[0] - stats[1];

        for (PointsToStatement stmt : newStatements) {
            if (stmt.programPoint() instanceof CallSiteProgramPoint) {
                CallSiteProgramPoint pp = (CallSiteProgramPoint) stmt.programPoint();
                assert !pp.isDiscarded();
                Set<CallSiteProgramPoint> scpps = this.callSitesWithinMethod.get(m);
                if (scpps == null) {
                    scpps = AnalysisUtil.createConcurrentSet();
                    Set<CallSiteProgramPoint> existing = this.callSitesWithinMethod.put(m, scpps);
                    if (existing != null) {
                        // someone else got there first.
                        scpps = existing;
                    }
                }
                scpps.add(pp);
            }
            for (ReferenceVariable def : stmt.getDefs()) {
                if (def.hasLocalScope()) {
                    def.setLocalDef(stmt.programPoint());
                }
            }
            for (ReferenceVariable use : stmt.getUses()) {
                if (use != null && use.hasLocalScope()) {
                    use.addLocalUse(stmt.programPoint());
                }
            }
        }

        // compute and record the call-site ordering for this method
        Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> ordering = new CallSiteOrdering().getForMethod(methSumm.getEntryPP());
        callSiteOrdering.put(m, ordering);

        return true;

    }

    /**
     * Add program point edges for the given basic block
     *
     * @param bb basic block to add edges for
     * @param methSumm method summary nodes for the basic block
     * @param controlFlowGraph control flow graph for the method containing the basic block
     * @param insToPPSubGraph map from instruction to program point subgraph for that instruction
     * @param bbToEntryPP map from basic block to entry program point
     */
    private void addPPEdgesForBasicBlock(ISSABasicBlock bb, MethodSummaryNodes methSumm, SSACFG controlFlowGraph,
                                         Map<SSAInstruction, PPSubGraph> insToPPSubGraph,
                                         Map<ISSABasicBlock, ProgramPoint> bbToEntryPP) {

        ProgramPoint thisBBEntryPP = bbToEntryPP.get(bb);
        if (controlFlowGraph.getMethod().equals(entryMethod) && bb.isEntryBlock()) {
            // This is the entry block.
            // We may have inserted class-inits or singleton allocations after the method summary-entry PP
            // Use the last PP added
            thisBBEntryPP = lastEntryMethodPP;
        }
        Iterator<SSAInstruction> insIter = bb.iterator();
        if (!insIter.hasNext()) {
            addPPEdgesForEmptyBlock(controlFlowGraph, methSumm, bbToEntryPP, thisBBEntryPP, bb);
            return;
        }

        // we have a nonempty block
        PPSubGraph prev = null; // previous instruction within the basic block
        while (insIter.hasNext()) {
            PPSubGraph subgraph = insToPPSubGraph.get(insIter.next());
            addPPEdgesForInstruction(subgraph, prev, thisBBEntryPP);
            prev = subgraph;

            if (insIter.hasNext()) {
                // only the last instruction in a basic block is allowed to throw exceptions.
                assert subgraph.getThrownExceptionExits().isEmpty();
                assert subgraph.getGeneratedExceptionExits().isEmpty();
            }
        }
        assert prev != null;

        // prev is the last instruction in the basic block. Connect it to its successors.
        // get the successors of bb, and connect the nodes appropriately.
        if (prev.canExitNormally()) {
            addPPEdgesForNormalTermination(controlFlowGraph.getNormalSuccessors(bb),
                                           prev.normalExit(),
                                           bbToEntryPP,
                                           methSumm);
        }

        addPPEdgesForExceptions(prev, bbToEntryPP, methSumm);
    }

    /**
     * Add edges to the program point graph for the normal termination of an basic block
     *
     * @param normalSuccessors normal successor basic block
     * @param normalExit normal exit of the last instruction of the basic block
     * @param bbToEntryPP map from basic block to entry program point
     * @param methSumm method summary nodes for the basic block
     */
    private static void addPPEdgesForNormalTermination(Collection<ISSABasicBlock> normalSuccessors,
                                                       ProgramPoint normalExit,
                                                       Map<ISSABasicBlock, ProgramPoint> bbToEntryPP,
                                                       MethodSummaryNodes methSumm) {
        if (normalSuccessors.isEmpty()) {
            normalExit.addSucc(methSumm.getNormalExitPP());
        }
        else {
            for (ISSABasicBlock succBB : normalSuccessors) {
                normalExit.addSucc(bbToEntryPP.get(succBB));
            }
        }
    }

    /**
     * Add edges to the program point graph for all exceptions thrown by a given instruction
     *
     * @param instructionGraph the program point subgraph for the instruction
     * @param bbToEntryPP method summary nodes for the basic block throwning the exceptions
     * @param methSumm
     */
    private static void addPPEdgesForExceptions(PPSubGraph instructionGraph,
                                                Map<ISSABasicBlock, ProgramPoint> bbToEntryPP,
                                                MethodSummaryNodes methSumm) {

        // Loop through all the exceptions in the subgraph and add edges to each of the successor basic blocks

        Map<TypeReference, Map<ISSABasicBlock, ProgramPoint>> thrownEx = instructionGraph.getThrownExceptionExits();
        for (TypeReference exType : thrownEx.keySet()) {
            for (ISSABasicBlock succ : thrownEx.get(exType).keySet()) {
                // Add an edge from the exception PP to the entry of the catch block
                ProgramPoint exPP = thrownEx.get(exType).get(succ);
                if (succ.isExitBlock()) {
                    exPP.addSucc(methSumm.getExceptionExitPP());
                }
                else {
                    exPP.addSucc(bbToEntryPP.get(succ));
                }
            }
        }

        Map<TypeReference, Map<ISSABasicBlock, ProgramPoint>> genEx = instructionGraph.getGeneratedExceptionExits();
        for (TypeReference exType : genEx.keySet()) {
            for (ISSABasicBlock succ : genEx.get(exType).keySet()) {
                // Add an edge from the exception PP to the entry of the catch block
                ProgramPoint exPP = genEx.get(exType).get(succ);
                if (succ.isExitBlock()) {
                    exPP.addSucc(methSumm.getExceptionExitPP());
                }
                else {
                    exPP.addSucc(bbToEntryPP.get(succ));
                }
            }
        }
    }

    /**
     * Add edges to the program point graph for a particular instruction
     *
     * @param currentSubgraph subgraph for the current instruction
     * @param prevSubgraph subgraph for the previous instruction (null if this is the first instruction)
     * @param bbEntryPP entry program point for the basic block
     */
    private static void addPPEdgesForInstruction(PPSubGraph currentSubgraph, PPSubGraph prevSubgraph,
                                                 ProgramPoint bbEntryPP) {
        if (prevSubgraph == null) {
            // ins is the very first instruction of the block
            // so add an edge from the bb entry PP to the entry to subgraph.
            bbEntryPP.addSucc(currentSubgraph.entry());
        }
        else {
            prevSubgraph.normalExit().addSucc(currentSubgraph.entry());
        }
    }

    /**
     * Create edges from the program point for an empty basic block to the entry program points for the successors
     *
     * @param cfg control flow graph for the containing method
     * @param methSumm method summary nodes
     * @param bbToEntryPP map from basic bloc to entry program point
     * @param thisBBEntryPP entry program point for this basic block
     * @param bb empty basic block
     */
    private static void addPPEdgesForEmptyBlock(SSACFG cfg, MethodSummaryNodes methSumm,
                                                Map<ISSABasicBlock, ProgramPoint> bbToEntryPP,
                                                ProgramPoint thisBBEntryPP, ISSABasicBlock bb) {
        assert !bb.iterator().hasNext();
        Collection<ISSABasicBlock> normalSuccs = cfg.getNormalSuccessors(bb);
        if (normalSuccs.isEmpty()) {
            thisBBEntryPP.addSucc(methSumm.getNormalExitPP());
        }
        else {
            for (ISSABasicBlock succBB : normalSuccs) {
                thisBBEntryPP.addSucc(bbToEntryPP.get(succBB));
            }
        }
    }

    /**
     * Remove unnecessary program points from the program point graph
     *
     * @param methSumm method summary nodes for this method (should not be removed)
     * @param callExceptions exceptions at call sites (should not be removed)
     */
    private void cleanUpProgramPoints(MethodSummaryNodes methSumm, Set<ProgramPoint> callExceptions) {
        // try to clean up the program points. Let's first get a reverse mapping, and then check to see if there are any we can merge
        Map<ProgramPoint, Set<ProgramPoint>> preds = new HashMap<>();

        int totalPPs = 0;
        {
            Set<ProgramPoint> visited = new HashSet<>();
            ArrayList<ProgramPoint> q = new ArrayList<>();
            q.add(methSumm.getEntryPP());
            while (!q.isEmpty()) {
                ProgramPoint pp = q.remove(q.size() - 1);
                if (visited.contains(pp)) {
                    continue;
                }
                visited.add(pp);
                for (ProgramPoint succ : pp.succs()) {
                    Set<ProgramPoint> predsForSucc = preds.get(succ);
                    if (predsForSucc == null) {
                        predsForSucc = new HashSet<>();
                        preds.put(succ, predsForSucc);
                    }
                    predsForSucc.add(pp);
                    if (!visited.contains(succ)) {
                        q.add(succ);
                    }
                }
            }
            totalPPs = visited.size();
        }

        int removedPPs = 0;

        // we now have the pred relation.
        // Go through and collapse non-modifying PPs that have only one successor or predecessor
        Set<ProgramPoint> removed = new HashSet<>();
        for (ProgramPoint pp : preds.keySet()) {
            if (getStmtAtPP(pp) != null) {
                // this pp may use or modify the flow-sensitive part of the points to graph
                continue;
            }
            if (removed.contains(pp)) {
                // this program point has already been removed.
                continue;
            }
            if (pp.isEntrySummaryNode() || pp.isExceptionExitSummaryNode() || pp.isNormalExitSummaryNode()) {
                // don't try to remove summary nodes
                continue;
            }
            if (callExceptions.contains(pp)) {
                // don't remove program points for exceptions at call-sites
                continue;
            }
            if (catchEntries.contains(pp)) {
                // do not remove the program point for an entry to a catch block (these are the "def" site for the formal)
                continue;
            }
            if (pp instanceof CallSiteProgramPoint) {
                // don't remove call sites
                continue;
            }

            Set<ProgramPoint> predSet = preds.get(pp);
            if (predSet.size() == 1) {
                // we have one predecessor
                // merge pp with the predecessor
                ProgramPoint predPP = predSet.iterator().next();
                assert !removed.contains(predPP);
                predSet.clear();

                assert predPP.succs().contains(pp);
                predPP.removeSucc(pp);
                for (ProgramPoint ppSucc : pp.succs()) {
                    assert !removed.contains(ppSucc);
                    // for each successor of pp, remove pp as a predecessor, and add ppPred.
                    assert preds.get(ppSucc) != null && preds.get(ppSucc).contains(pp);
                    preds.get(ppSucc).remove(pp);
                    preds.get(ppSucc).add(predPP);
                    predPP.addSucc(ppSucc);
                }

                removedPPs++;
                removed.add(pp);
                pp.setIsDiscardedProgramPoint();
                predSet.clear();
                assert pp.succs().isEmpty();
                assert preds.get(pp).isEmpty();
                continue;
            }
            if (pp.succs().size() == 1) {
                // we have one successor
                // merge pp with the successor
                ProgramPoint succPP = pp.succs().iterator().next();
                assert !removed.contains(succPP);

                Set<ProgramPoint> succPPpreds = preds.get(succPP);
                assert succPPpreds.contains(pp);
                succPPpreds.remove(pp);
                for (ProgramPoint ppPred : predSet) {
                    assert !removed.contains(ppPred);
                    // for each predecessor of pp, remove pp as a successor, and add ppSucc.
                    ppPred.removeSucc(pp);

                    ppPred.addSucc(succPP);
                    succPPpreds.add(ppPred);
                }

                pp.removeSucc(succPP);
                predSet.clear();
                assert pp.succs().isEmpty();
                assert preds.get(pp).isEmpty();

                removedPPs++;
                removed.add(pp);
                continue;
            }

        }
        StatementRegistrar.totalProgramPoints += totalPPs;
        StatementRegistrar.removedProgramPoints += removedPPs;
        if (PointsToAnalysis.outputLevel > 0) {
            System.err.println("Total pp: " + totalPPs + ", removed pp: " + removedPPs);
        }
    }

    /**
     * A listener that will get notified of newly created statements.
     */
    private StatementListener stmtListener = null;
    int swingClasses = 0;

    private static int removedStmts = 0;
    private static int removedProgramPoints = 0;
    private static int totalProgramPoints = 0;

    /**
     * Handle a particular instruction, this dispatches on the type of the instruction
     *
     * @param i instruction to handle
     * @param bb basic block containing the instruction
     * @param insToPPSubGraph program point subgraphs for each instruction (will be modified to add the subgraph for the
     *            new instruction)
     * @param printer pretty printer
     * @param methSumm method summary reference variables for the method containing the instruction
     * @param types repository for local variable type information
     * @param callExits program points for normal exit and exceptions at call-sites (may be modified)
     */
    protected void handleInstruction(SSAInstruction i, IR ir, ISSABasicBlock bb,
                                     Map<SSAInstruction, PPSubGraph> insToPPSubGraph, TypeRepository types,
                                     PrettyPrinter printer, MethodSummaryNodes methSumm, Set<ProgramPoint> callExits) {
        assert i.getNumberOfDefs() <= 2 : "More than two defs in instruction: " + i;

        PPSubGraph subgraph;
        if (insToPPSubGraph.containsKey(i)) {
            assert i instanceof SSAGetCaughtExceptionInstruction;
            subgraph = insToPPSubGraph.get(i);
        }
        else {
            subgraph = new PPSubGraph(ir.getMethod(), i, printer);
        }
        insToPPSubGraph.put(i, subgraph);

        // Add statements for any string literals in the instruction
        this.findAndRegisterStringAndNullLiterals(i, ir, subgraph, this.rvFactory, printer);

        // Add statements for any JVM-generated exceptions this instruction could throw (e.g. NullPointerException)
        this.findAndRegisterGeneratedExceptions(i,
                                                bb,
                                                ir,
                                                subgraph,
                                                this.rvFactory,
                                                types,
                                                printer,
                                                insToPPSubGraph,
                                                methSumm);
        IClass reqInit = ClassInitFinder.getRequiredInitializedClass(i);
        if (reqInit != null) {
            // There is a class that must be initialized before executing this instruction
            List<IMethod> inits = ClassInitFinder.getClassInitializersForClass(reqInit);
            assert inits != null;
            if (!inits.isEmpty()) {
                // this is the location of the class initialization _statement_ not the class initializer itself
                // So this triggers the processing of a new clinit, but is not the call site for the clinit
                // (which is in the root method)
                ProgramPoint clinitPP = subgraph.addIntermediateNormal("clinit " + PrettyPrinter.typeString(reqInit));
                this.registerClassInitializers(i, clinitPP, inits);
            }
            // record program points for the class initializers
            this.addProgramPointsForClassInitializers(reqInit);
        }

        InstructionType type = InstructionType.forInstruction(i);
        ProgramPoint pp = subgraph.normalExit();
        switch (type) {
        case ARRAY_LOAD:
            // x = v[i]
            this.registerArrayLoad((SSAArrayLoadInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case ARRAY_STORE:
            // v[i] = x
            this.registerArrayStore((SSAArrayStoreInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case CHECK_CAST:
            // v = (Type) x
            this.registerCheckCast((SSACheckCastInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case GET_FIELD:
            // v = o.f
            this.registerGetField((SSAGetInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case GET_STATIC:
            // v = ClassName.f
            this.registerGetStatic((SSAGetInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case INVOKE_INTERFACE:
        case INVOKE_SPECIAL:
        case INVOKE_STATIC:
        case INVOKE_VIRTUAL:
            // procedure calls, instance initializers
            SSAInvokeInstruction invocation = (SSAInvokeInstruction) i;
            this.registerInvoke(invocation, bb, ir, this.rvFactory, types, printer, insToPPSubGraph, callExits);
            return;
        case LOAD_METADATA:
            // Reflection
            this.registerLoadMetadata((SSALoadMetadataInstruction) i, ir, pp, this.rvFactory, printer);
            return;
        case NEW_ARRAY:
            this.registerNewArray((SSANewInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case NEW_OBJECT:
            // v = new Foo();
            this.registerNewObject((SSANewInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case PHI:
            // v = phi(x_1,x_2)
            this.registerPhiAssignment((SSAPhiInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case PUT_FIELD:
            // o.f = v
            this.registerPutField((SSAPutInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case PUT_STATIC:
            // ClassName.f = v
            this.registerPutStatic((SSAPutInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case RETURN:
            // return v
            this.registerReturn((SSAReturnInstruction) i, ir, pp, this.rvFactory, types, printer);
            return;
        case THROW:
            // throw e
            this.registerThrow((SSAThrowInstruction) i, bb, ir, this.rvFactory, types, printer, insToPPSubGraph);
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
    private void registerArrayLoad(SSAArrayLoadInstruction i, IR ir, ProgramPoint pp,
                                   ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pprint) {
        TypeReference arrayType = types.getType(i.getArrayRef());
        TypeReference baseType = arrayType.getArrayElementType();
        assert baseType.equals(types.getType(i.getDef()));
        if (baseType.isPrimitiveType()) {
            // Assigning to a primitive
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), baseType, ir.getMethod(), pprint);
        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getArrayRef(), arrayType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.arrayToLocal(v, a, baseType, pp));
    }

    /**
     * a[j] = v, store into an array
     */
    private void registerArrayStore(SSAArrayStoreInstruction i, IR ir, ProgramPoint pp,
                                    ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pprint) {
        TypeReference valueType = types.getType(i.getValue());
        if (valueType.isPrimitiveType() && !ir.getSymbolTable().isNullConstant(i.getValue())) {
            // Assigning from a primitive or assigning null (also no effect on points-to graph)
            return;
        }

        TypeReference arrayType = types.getType(i.getArrayRef());
        TypeReference baseType = arrayType.getArrayElementType();

        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getArrayRef(), arrayType, ir.getMethod(), pprint);
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getValue(), valueType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToArrayContents(a, v, baseType, pp, i));
    }

    /**
     * v2 = (TypeName) v1
     */
    private void registerCheckCast(SSACheckCastInstruction i, IR ir, ProgramPoint pp,
                                   ReferenceVariableFactory rvFactory, TypeRepository types, PrettyPrinter pprint) {
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
                                                          pprint);
        ReferenceVariable v1 = rvFactory.getOrCreateLocal(i.getVal(), valType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToLocalFiltered(v2, v1, pp));
    }

    /**
     * v = o.f
     */
    private void registerGetField(SSAGetInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                  TypeRepository types, PrettyPrinter pprint) {
        TypeReference resultType = i.getDeclaredFieldType();
        // If the class can't be found then WALA sets the type to object (why can't it be found?)
        assert resultType.getName().equals(types.getType(i.getDef()).getName())
                || types.getType(i.getDef()).equals(TypeReference.JavaLangObject);
        if (resultType.isPrimitiveType()) {
            // No pointers here
            return;
        }

        TypeReference receiverType = types.getType(i.getRef());

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);
        ReferenceVariable o = rvFactory.getOrCreateLocal(i.getRef(), receiverType, ir.getMethod(), pprint);
        FieldReference f = i.getDeclaredField();
        this.addStatement(stmtFactory.fieldToLocal(v, o, f, pp));
    }

    /**
     * v = ClassName.f
     */
    private void registerGetStatic(SSAGetInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                   TypeRepository types, PrettyPrinter pprint) {
        TypeReference resultType = i.getDeclaredFieldType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        if (resultType.isPrimitiveType()) {
            // No pointers here
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);
        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        this.addStatement(stmtFactory.staticFieldToLocal(v, f, pp));
    }

    /**
     * o.f = v
     */
    private void registerPutField(SSAPutInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                  TypeRepository types, PrettyPrinter pprint) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType() && !ir.getSymbolTable().isNullConstant(i.getVal())) {
            // Assigning into a primitive field
            return;
        }

        TypeReference receiverType = types.getType(i.getRef());

        ReferenceVariable o = rvFactory.getOrCreateLocal(i.getRef(), valueType, ir.getMethod(), pprint);
        FieldReference f = i.getDeclaredField();
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), receiverType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToField(o, f, v, pp, i));
    }

    /**
     * ClassName.f = v
     */
    private void registerPutStatic(SSAPutInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                   TypeRepository types, PrettyPrinter pprint) {
        TypeReference valueType = types.getType(i.getVal());
        if (valueType.isPrimitiveType() && !ir.getSymbolTable().isNullConstant(i.getVal())) {
            // Assigning into a primitive field
            return;
        }

        ReferenceVariable f = rvFactory.getOrCreateStaticField(i.getDeclaredField());
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getVal(), valueType, ir.getMethod(), pprint);
        this.addStatement(stmtFactory.localToStaticField(f, v, pp, i));

    }

    /**
     * A virtual, static, special, or interface invocation
     */
    private void registerInvoke(SSAInvokeInstruction i, ISSABasicBlock bb, IR ir, ReferenceVariableFactory rvFactory,
                                TypeRepository types, PrettyPrinter pprint,
                                Map<SSAInstruction, PPSubGraph> insToPPSubGraph, Set<ProgramPoint> callExits) {
        assert i.getNumberOfReturnValues() == 0 || i.getNumberOfReturnValues() == 1;

        // //////////// Result ////////////

        ReferenceVariable result = null;
        if (i.getNumberOfReturnValues() > 0) {
            TypeReference returnType = types.getType(i.getReturnValue(0));
            if (!returnType.isPrimitiveType()) {
                result = rvFactory.getOrCreateLocal(i.getReturnValue(0), returnType, ir.getMethod(), pprint);
            }
        }

        // //////////// Actual arguments ////////////

        List<ReferenceVariable> actuals = new LinkedList<>();
        for (int j = 0; j < i.getNumberOfParameters(); j++) {
            TypeReference actualType = types.getType(i.getUse(j));
            if (actualType.isPrimitiveType() && !ir.getSymbolTable().isNullConstant(i.getUse(j))) {
                actuals.add(null);
            }
            else {
                actuals.add(rvFactory.getOrCreateLocal(i.getUse(j), actualType, ir.getMethod(), pprint));
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
            receiver = rvFactory.getOrCreateLocal(i.getReceiver(), receiverType, ir.getMethod(), pprint);
        }

        if (PointsToAnalysis.outputLevel >= 2) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, ir.getMethod());
            if (resolvedMethods.isEmpty()) {
                System.err.println("No resolved methods for " + pprint.instructionString(i) + " method: "
                        + PrettyPrinter.methodString(i.getDeclaredTarget()) + " caller: "
                        + PrettyPrinter.methodString(ir.getMethod()));
            }
        }

        // //////////// Exceptions ////////////

        PPSubGraph sg = insToPPSubGraph.get(i);
        ProgramPoint exPP = sg.getCallSiteException(i.getCallSite());
        callExits.add(exPP);

        TypeReference exType = TypeReference.JavaLangThrowable;
        ReferenceVariable exception = rvFactory.getOrCreateLocal(i.getException(), exType, ir.getMethod(), pprint);


        this.registerThrownException(insToPPSubGraph.get(i),
                                     bb,
                                     ir,
                                     exception,
                                     rvFactory,
                                     types,
                                     pprint,
                                     insToPPSubGraph,
                                     false,
                                     true);

        // Create program point for the call site
        CallSiteProgramPoint cspp = new CallSiteProgramPoint(ir.getMethod(), i.getCallSite(), exPP, sg.normalExit());
        sg.replaceEntryWithCallSitePP(cspp);
        callExits.add(sg.normalExit());

        // //////////// Resolve methods add statements ////////////

        if (i.isStatic()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, ir.getMethod());
            if (resolvedMethods.isEmpty()) {
                return;
            }
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee);
            this.addStatement(stmtFactory.staticCall(cspp, resolvedCallee, result, actuals, exception, calleeSummary));
        }
        else if (i.isSpecial()) {
            Set<IMethod> resolvedMethods = resolveMethodsForInvocation(i, ir.getMethod());
            if (resolvedMethods.isEmpty()) {
                // No methods found!
                return;
            }
            assert resolvedMethods.size() == 1;
            IMethod resolvedCallee = resolvedMethods.iterator().next();
            MethodSummaryNodes calleeSummary = this.findOrCreateMethodSummary(resolvedCallee);
            this.addStatement(stmtFactory.specialCall(cspp,
                                                      resolvedCallee,
                                                      result,
                                                      receiver,
                                                      actuals,
                                                      exception,
                                                      calleeSummary));
        }
        else if (i.getInvocationCode() == IInvokeInstruction.Dispatch.INTERFACE
                || i.getInvocationCode() == IInvokeInstruction.Dispatch.VIRTUAL) {
            if (ir.getSymbolTable().isNullConstant(i.getReceiver())) {
                // Sometimes the receiver is a null constant
                return;
            }
            this.addStatement(stmtFactory.virtualCall(cspp,
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

    /**
     * a = new TypeName[j][k][l]
     * <p>
     * Note that this is only the allocation not the initialization if there is any.
     */
    private void registerNewArray(SSANewInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                  TypeRepository types, PrettyPrinter pprint) {
        // all "new" instructions are assigned to a local
        TypeReference resultType = i.getConcreteType();
        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        ReferenceVariable a = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);

        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(i.getNewSite().getDeclaredType());
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        if (useSingleAllocForPrimitiveArrays && resultType.getArrayElementType().isPrimitiveType()) {
            ReferenceVariable rv = getOrCreateSingleton(resultType);
            this.addStatement(stmtFactory.localToLocal(a, rv, pp));
        }
        else {
            this.addStatement(stmtFactory.newForNormalAlloc(a,
                                                            klass,
                                                            pp,
                                                            i.getNewSite().getProgramCounter(),
                                                            pprint.getLineNumber(i)));
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
            this.addStatement(stmtFactory.newForInnerArray(innerArray, arrayklass, pp));

            // Add field assign from the inner array to the array contents field of the outer array
            this.addStatement(stmtFactory.multidimensionalArrayContents(outerArray, innerArray, pp));

            // The array on the next iteration will be contents of this one
            outerArray = innerArray;
        }
    }

    /**
     * v = new TypeName
     * <p>
     * Handle an allocation of the form: "new Foo". Note that this is only the allocation not the constructor call.
     */
    private void registerNewObject(SSANewInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                   TypeRepository types, PrettyPrinter pprint) {
        // all "new" instructions are assigned to a local
        TypeReference resultType = i.getConcreteType();

        assert resultType.getName().equals(types.getType(i.getDef()).getName());
        ReferenceVariable result = rvFactory.getOrCreateLocal(i.getDef(), resultType, ir.getMethod(), pprint);

        TypeReference allocType = i.getNewSite().getDeclaredType();
        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(allocType);
        assert klass != null : "No class found for " + PrettyPrinter.typeString(i.getNewSite().getDeclaredType());
        if (!tryAllocateSingleton(klass, result, pp)) {
            this.addStatement(stmtFactory.newForNormalAlloc(result,
                                                            klass,
                                                            pp,
                                                            i.getNewSite().getProgramCounter(),
                                                            pprint.getLineNumber(i)));
        }
    }

    /**
     * x = phi(x_1, x_2, ...)
     */
    private void registerPhiAssignment(SSAPhiInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                       TypeRepository types, PrettyPrinter pprint) {
        TypeReference phiType = types.getType(i.getDef());
        if (phiType.isPrimitiveType() && !ir.getSymbolTable().isNullConstant(i.getUse(0))) {
            // No pointers here
            return;
        }
        ReferenceVariable assignee = rvFactory.getOrCreateLocal(i.getDef(), phiType, ir.getMethod(), pprint);
        List<ReferenceVariable> uses = new ArrayList<>(i.getNumberOfUses());
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int arg = i.getUse(j);
            TypeReference argType = types.getType(arg);
            assert !argType.isPrimitiveType() || argType.equals(TypeReference.Null) : "arg type: "
                    + PrettyPrinter.typeString(argType) + " for phi type: " + PrettyPrinter.typeString(phiType);

            ReferenceVariable x_i = rvFactory.getOrCreateLocal(arg, phiType, ir.getMethod(), pprint);
            uses.add(x_i);

        }

        if (uses.isEmpty()) {
            // All entries to the phi are null literals, no effect on pointer analysis
            return;
        }
        this.addStatement(stmtFactory.phiToLocal(assignee, uses, pp));
    }

    /**
     * Load-metadata is used for .class access
     */
    private void registerLoadMetadata(SSALoadMetadataInstruction i, IR ir, ProgramPoint pp,
                                      ReferenceVariableFactory rvFactory, PrettyPrinter pprint) {
        // This is a call like Object.class that returns a Class object, until we handle reflection just allocate a singleton
        if (!i.getType().equals(TypeReference.JavaLangClass)) {
            throw new RuntimeException("Load metadata with a non-class target " + i);
        }

        // Allocation of a singleton java.lang.Class object
        ReferenceVariable rv = getOrCreateSingleton(TypeReference.JavaLangClass);
        ReferenceVariable left = rvFactory.getOrCreateLocal(i.getDef(),
                                                            TypeReference.JavaLangClass,
                                                            ir.getMethod(),
                                                            pprint);
        this.addStatement(stmtFactory.localToLocal(left, rv, pp));
    }

    /**
     * return v
     */
    private void registerReturn(SSAReturnInstruction i, IR ir, ProgramPoint pp, ReferenceVariableFactory rvFactory,
                                TypeRepository types, PrettyPrinter pprint) {
        if (i.returnsVoid()) {
            // no pointers here
            return;
        }

        TypeReference valType = types.getType(i.getResult());
        if (valType.isPrimitiveType() && !ir.getSymbolTable().isNullConstant(i.getResult())) {
            // returning a primitive or "null"
            return;
        }

        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getResult(), valType, ir.getMethod(), pprint);
        ReferenceVariable summary = this.findOrCreateMethodSummary(ir.getMethod()).getReturn();
        this.addStatement(stmtFactory.returnStatement(v, summary, pp, i));
    }

    /**
     * throw v
     */
    private void registerThrow(SSAThrowInstruction i, ISSABasicBlock bb, IR ir, ReferenceVariableFactory rvFactory,
                               TypeRepository types, PrettyPrinter pprint,
                               Map<SSAInstruction, PPSubGraph> insToPPSubGraph) {
        TypeReference throwType = types.getType(i.getException());
        PPSubGraph subgraph = insToPPSubGraph.get(i);

        subgraph.setCanExitNormally(false);
        ReferenceVariable v = rvFactory.getOrCreateLocal(i.getException(), throwType, ir.getMethod(), pprint);
        this.registerThrownException(subgraph, bb, ir, v, rvFactory, types, pprint, insToPPSubGraph, false, false);
    }

    /**
     * Get the method summary nodes for the given method, create if necessary
     *
     * @param method method to get summary nodes for
     */
    public MethodSummaryNodes findOrCreateMethodSummary(IMethod method) {
        MethodSummaryNodes msn = this.methods.get(method);
        if (msn == null) {
            msn = new MethodSummaryNodes(method);
            MethodSummaryNodes ex = this.methods.putIfAbsent(method, msn);
            if (ex != null) {
                msn = ex;
            }
        }
        return msn;
    }

    /**
     * Get the method summary nodes for the given method, return null if not found
     */
    public MethodSummaryNodes getMethodSummary(IMethod method) {
        return this.methods.get(method);
    }

    /**
     * Get the statement at a program point, return null is not found
     */
    public PointsToStatement getStmtAtPP(ProgramPoint pp) {
        return this.ppToStmtMap.get(pp);
    }

    /**
     * Get all methods that should be analyzed in the initial empty context
     *
     * @return set of methods
     */
    public Set<IMethod> getInitialContextMethods() {
        Set<IMethod> ret = new LinkedHashSet<>();
        ret.add(this.entryMethod);
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

        // handle the mapping for program points
        if (s.mayChangeOrUseFlowSensPointsToGraph()) {
            ProgramPoint pp = s.programPoint();
            PointsToStatement existing = ppToStmtMap.putIfAbsent(pp, s);
            assert (existing == null) : "More than one statement that may modify the points to graph at a program point: existing is '"
                    + existing + "' and just tried to add '" + s + "'";
        }
        else {
            // it shouldn't matter what program point we use for the statement
            // XXX s.setProgramPoint(getMethodSummary(m).getEntryPP());
        }

        if ((this.size + StatementRegistrar.removedStmts) % 100000 == 0) {
            reportStats();
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

    public void reportStats() {
        System.err.println("REGISTERED statements: " + (this.size + StatementRegistrar.removedStmts) + ", removed: "
                + StatementRegistrar.removedStmts + ", effective: " + this.size + "\n           program points:  "
                + (totalProgramPoints() + totalProgramPointsRemoved()) + ", removed: " + totalProgramPointsRemoved()
                + ", effective: " + totalProgramPoints() + ", with stmt that may modify or use graph: "
                + this.ppToStmtMap.size());

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
     * Set of all methods that have been registered
     *
     * @return set of methods
     */
    public Set<IMethod> getRegisteredMethods() {
        return this.statementsForMethod.keySet();
    }

    /**
     * Look for String and null literals in the instruction and create allocation sites for them
     *
     * @param i instruction to create string literals for
     * @param ir code containing the instruction
     * @param stringClass WALA representation of the java.lang.String class
     */
    private void findAndRegisterStringAndNullLiterals(SSAInstruction i, IR ir, PPSubGraph subgraph,
                                                      ReferenceVariableFactory rvFactory, PrettyPrinter pprint) {
        for (int j = 0; j < i.getNumberOfUses(); j++) {
            int use = i.getUse(j);
            if (ir.getSymbolTable().isStringConstant(use)) {
                ReferenceVariable newStringLit = rvFactory.getOrCreateLocal(use,
                                                                            TypeReference.JavaLangString,
                                                                            ir.getMethod(),
                                                                            pprint);
                if (!this.handledLiterals.add(newStringLit)) {
                    // Already handled this allocation
                    continue;
                }

                // The fake root method always allocates a String so the clinit has already been called, even if we are
                // flow sensitive

                // add points to statements to simulate the allocation
                this.registerStringLiteral(newStringLit, use, subgraph, pprint);
            }
            else if (ir.getSymbolTable().isNullConstant(use)) {
                ReferenceVariable newNullLit = rvFactory.getOrCreateLocal(use,
                                                                          TypeReference.Null,
                                                                          ir.getMethod(),
                                                                          pprint);
                if (!this.handledLiterals.add(newNullLit)) {
                    // Already handled this allocation
                    continue;
                }

                // add points to statements to simulate the allocation
                ProgramPoint newPP = subgraph.addIntermediateNormal("null-lit");
                this.addStatement(stmtFactory.nullToLocal(newNullLit, newPP));
            }
        }
    }

    /**
     * Add points-to statements for a String constant
     *
     * @param stringLit reference variable for the string literal being handled
     * @param local local variable value number for the literal
     * @param subgraph Program point subgraph for the instruction where the literal is created
     * @param pprint pretty printer
     */
    private void registerStringLiteral(ReferenceVariable stringLit, int local, PPSubGraph subgraph, PrettyPrinter pprint) {
        if (useSingleAllocForStrings) {
            // v = string
            ReferenceVariable rv = getOrCreateSingleton(AnalysisUtil.getStringClass().getReference());
            ProgramPoint newPP = subgraph.addIntermediateNormal("string-lit-alloc");
            this.addStatement(stmtFactory.localToLocal(stringLit, rv, newPP));
        }
        else {
            // v = new String
            ProgramPoint newPP = subgraph.addIntermediateNormal("string-lit-alloc");
            this.addStatement(stmtFactory.newForStringLiteral(pprint.valString(local), stringLit, newPP));

            for (IField f : AnalysisUtil.getStringClass().getAllFields()) {
                if (f.getName().toString().equals("value")) {
                    // This is the value field of the String
                    ReferenceVariable stringValue = ReferenceVariableFactory.createStringLitField();

                    newPP = subgraph.addIntermediateNormal("string-lit-alloc");
                    this.addStatement(stmtFactory.newForStringField(stringValue, newPP));

                    newPP = subgraph.addIntermediateNormal("string-lit");
                    this.addStatement(new LocalToFieldStatement(stringLit, f.getReference(), stringValue, newPP));
                }
            }
        }
    }

    /**
     * Add points-to statements for any generated exceptions thrown by the given instruction
     */
    @SuppressWarnings("unused")
    private final void findAndRegisterGeneratedExceptions(SSAInstruction i, ISSABasicBlock bb, IR ir, PPSubGraph g,
                                                          ReferenceVariableFactory rvFactory, TypeRepository types,
                                                          PrettyPrinter pprint,
                                                          Map<SSAInstruction, PPSubGraph> insToPPSubGraph,
                                                          MethodSummaryNodes methSumm) {
        for (TypeReference exType : PreciseExceptionResults.implicitExceptions(i)) {
            ReferenceVariable ex;
            if (useSingleAllocForGenEx) {
                ex = getOrCreateSingleton(exType);
                this.registerThrownException(g, bb, ir, ex, rvFactory, types, pprint, insToPPSubGraph, true, false);
            }
            else {
                ex = rvFactory.createImplicitExceptionNode(exType, bb.getNumber(), ir.getMethod());

                IClass exClass = AnalysisUtil.getClassHierarchy().lookupClass(exType);
                assert exClass != null : "No class found for " + PrettyPrinter.typeString(exType);
                this.registerThrownException(g, bb, ir, ex, rvFactory, types, pprint, insToPPSubGraph, true, false);
                this.addStatement(stmtFactory.newForGeneratedException(ex, exClass, g.getGenExAllocation(exType)));
            }
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

            // We pretend that the allocation for this object occurs in the entry point of the entire program
            ProgramPoint pp = new ProgramPoint(getEntryPoint(), "EntryMethod-pp-" + klass);
            addEntryMethodProgramPoint(pp, true);
            NewStatement stmt = stmtFactory.newForGeneratedObject(rv, klass, pp, PrettyPrinter.typeString(varType));

            this.addStatement(stmt);

        }
        return rv;
    }

    /**
     * Add a program point to the entry method.
     *
     * @param pp program point to add
     */
    private void addEntryMethodProgramPoint(ProgramPoint pp, boolean putDirectlyAfterEntry) {
        if (putDirectlyAfterEntry) {
            // This pp needs to go right after the entry pp so put it there
            ProgramPoint entryPP = getMethodSummary(this.entryMethod).getEntryPP();
            pp.addSuccs(entryPP.succs());
            entryPP.clearSuccs();
            entryPP.addSucc(pp);
            if (lastEntryMethodPP == null) {
                // If this is the first entry-method pp added then record it, otherwise the last pp in the sequence after the
                // entry-method entry PP doesn't change since this was added at the head of that list
                lastEntryMethodPP = pp;
            }
            return;
        }

        if (lastEntryMethodPP == null) {
            // This is the first entry method PP being added, add it after the method summary-entry PP
            lastEntryMethodPP = getMethodSummary(this.entryMethod).getEntryPP();
        }

        // add all of the succs of lastEntryMethodPP as succs of pp
        // and add pp as a succ to lastEntryMethodPP.
        pp.addSuccs(lastEntryMethodPP.succs());
        lastEntryMethodPP.clearSuccs();
        lastEntryMethodPP.addSucc(pp);
        lastEntryMethodPP = pp;
    }

    /**
     * Add an assignment from the a thrown exception to any catch block or exit block exception that exception could
     * reach
     *
     * @param subgraph program point subgraph for the instruction that throws this exception
     * @param bb Basic block containing the instruction that throws the exception
     * @param ir code containing the instruction that throws
     * @param thrown reference variable representing the value of the exception
     * @param rvFactory factory used to create new reference variables
     * @param types type information about local variables
     * @param pprint pretty printer for the appropriate method
     * @param insToPPSubGraph map from instruction to program point subgraph
     * @param isGenerated whether the exception was generated by the JVM (e.g. NullPointerException)
     * @param isCalleeThrow whether the exception is at a call site and represents an exception thrown by the callee
     */
    private final void registerThrownException(PPSubGraph subgraph, ISSABasicBlock bb, IR ir, ReferenceVariable thrown,
                                               ReferenceVariableFactory rvFactory, TypeRepository types,
                                               PrettyPrinter pprint, Map<SSAInstruction, PPSubGraph> insToPPSubGraph,
                                               boolean isGenerated, boolean isCalleeThrow) {
        IClass thrownClass = AnalysisUtil.getClassHierarchy().lookupClass(thrown.getExpectedType());
        assert thrownClass != null;

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
                assert caughtType.equals(types.getType(catchIns.getException())) : caughtType
                        + " not equal to type repository type: " + types.getType(catchIns.getException())
                        + " resloved " + AnalysisUtil.getClassHierarchy().lookupClass(caughtType);

                IClass caughtClass = AnalysisUtil.getClassHierarchy().lookupClass(caughtType);
                assert caughtClass != null : caughtType + " not found in class hierarchy";
                boolean definitelyCaught = TypeRepository.isAssignableFrom(caughtClass, thrownClass);
                boolean maybeCaught = TypeRepository.isAssignableFrom(thrownClass, caughtClass);

                if (maybeCaught || definitelyCaught) {
                    caught = rvFactory.getOrCreateLocal(catchIns.getException(), caughtType, ir.getMethod(), pprint);
                    PPSubGraph catchSG = insToPPSubGraph.get(catchIns);
                    if (catchSG == null) {
                        catchSG = new PPSubGraph(ir.getMethod(), catchIns, pprint);
                        insToPPSubGraph.put(catchIns, catchSG);
                    }
                    catchEntries.add(catchSG.entry());
                    caught.setLocalDef(catchSG.entry());
                    ProgramPoint pp = subgraph.addException(succ, thrown.getExpectedType(), isGenerated, isCalleeThrow);
                    this.addStatement(StatementFactory.exceptionAssignment(thrown, caught, notType, pp));
                }

                // if we have definitely caught the exception, no need to add more exception assignment statements.
                if (definitelyCaught) {
                    break;
                }

                // Add this exception to the set of types that have already been caught
                notType.add(caughtClass);
            }
            else {
                assert succ.isExitBlock() : "Exceptional successor should be catch block or exit block.";
                // do not propagate java.lang.Errors out of this class, this is possibly unsound
                // uncomment to not propagate errors notType.add(AnalysisUtil.getErrorClass());
                caught = this.findOrCreateMethodSummary(ir.getMethod()).getException();
                ProgramPoint pp = subgraph.addException(succ, thrown.getExpectedType(), isGenerated, isCalleeThrow);
                this.addStatement(StatementFactory.exceptionAssignment(thrown, caught, notType, pp));
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
    void registerClassInitializers(SSAInstruction trigger, ProgramPoint pp, List<IMethod> clinits) {
        // Note the class initializers in the order in which they occurs
        this.addStatement(stmtFactory.classInit(clinits, pp, trigger));
    }

    /**
     * Given a class that must be initialized, create program points for that class initializer as well as any
     * superclass initializers that must be called first.
     *
     * @param reqInit class that must be initialized
     */
    private void addProgramPointsForClassInitializers(IClass reqInit) {
        List<IMethod> inits = ClassInitFinder.getClassInitializersForClass(reqInit);
        if (inits.isEmpty()) {
            if (!reqInit.getAllStaticFields().isEmpty() && noClinitWithStaticFields.add(reqInit)) {
                // There is no initializer, but there are static fields
                // We still need to set these fields to null
                for (IField f : reqInit.getAllStaticFields()) {
                    if (!f.getDeclaringClass().equals(reqInit)) {
                        // This field is in a superclass, it will be initialized elsewhere
                        continue;
                    }
                    if (f.getFieldTypeReference().isPrimitiveType()) {
                        // Primitive field
                        continue;
                    }
                    ReferenceVariable staticField = rvFactory.getOrCreateStaticField(f.getReference());
                    ProgramPoint pp = new ProgramPoint(getEntryPoint(), staticField + " = null-lit");
                    addStatement(stmtFactory.nullToLocal(staticField, pp));
                    addEntryMethodProgramPoint(pp, false);
                }
            }
            return;
        }

        if (this.programPointForClassInit.containsKey(reqInit)) {
            // This class initializer was already added
            return;
        }

        // list of program points for all required clinits
        LinkedList<ProgramPoint> pps = new LinkedList<>();

        // Loop from last clinit to be called (that for reqInit) to first (the highest superclass in the hierarchy)
        for (int i = inits.size(); i > 0; i--) {
            IMethod init = inits.get(i - 1);
            if (this.programPointForClassInit.containsKey(init)) {
                // Already seen this initializer and all super classes/interfaces
                break;
            }

            // We assume all class initializers are called in the root method
            MethodSummaryNodes entryNodes = findOrCreateMethodSummary(getEntryPoint());
            // XXX We assume exceptions thrown by class inits are never caught but bubble out to the exception exit of the root method
            CallSiteProgramPoint classInitPP = CallSiteProgramPoint.createClassInit(init,
                                                                                    getEntryPoint(),
                                                                                    entryNodes.getExceptionExitPP());
            pps.addFirst(classInitPP);
            CallSiteProgramPoint prev = this.programPointForClassInit.putIfAbsent(init, classInitPP);
            assert prev == null : "Registering duplicate clinit for "
                    + PrettyPrinter.typeString(init.getDeclaringClass()) + " maybe a race?";
            Set<CallSiteProgramPoint> entryCallSites = callSitesWithinMethod.get(entryMethod);
            if (entryCallSites == null) {
                entryCallSites = AnalysisUtil.createConcurrentSet();
                Set<CallSiteProgramPoint> existing = this.callSitesWithinMethod.put(entryMethod, entryCallSites);
                if (existing != null) {
                    // someone else got there first.
                    entryCallSites = existing;
                }
            }
            entryCallSites.add(classInitPP);
        }

        if (pps.isEmpty()) {
            // All inits had already been added
            return;
        }

        // Add the program points to the root method in the correct order
        for (ProgramPoint pp : pps) {
            if (reqInit.getReference().equals(TypeReference.JavaLangSystem)) {
                // System class init needs to go first since other classes rely on the fields therein
                addEntryMethodProgramPoint(pp, true);
            }
            addEntryMethodProgramPoint(pp, false);
        }
    }

    /**
     * Check whether the allocation should be a singleton (i.e. one object for the given type) if so then create the
     * singleton allocation and return true. If the type should not be a singleton then return false and do nothing.
     *
     * @param klass class being allocated
     * @param result local variable to assign the result of the allocation
     * @param pp program point for the allocation
     *
     * @return true if a singleton allocation occurred
     */
    private boolean tryAllocateSingleton(IClass klass, ReferenceVariable result, ProgramPoint pp) {
        TypeReference type = klass.getReference();
        if (useSingleAllocPerThrowableType && TypeRepository.isAssignableFrom(AnalysisUtil.getThrowableClass(), klass)) {
            // the newly allocated object is throwable, and we only want one allocation per throwable type
            ReferenceVariable rv = getOrCreateSingleton(type);
            this.addStatement(stmtFactory.localToLocal(result, rv, pp));
            return true;

        }
        else if (useSingleAllocForImmutableWrappers && Signatures.isImmutableWrapperType(type)) {
            // The newly allocated object is an immutable wrapper class, and we only want one allocation site for each type
            ReferenceVariable rv = getOrCreateSingleton(type);
            this.addStatement(stmtFactory.localToLocal(result, rv, pp));
            return true;
        }
        else if (useSingleAllocForStrings && TypeRepository.isAssignableFrom(AnalysisUtil.getStringClass(), klass)) {
            // the newly allocated object is a string, and we only want one allocation for strings
            ReferenceVariable rv = getOrCreateSingleton(type);
            this.addStatement(stmtFactory.localToLocal(result, rv, pp));
            return true;

        }
        else if (useSingleAllocForSwing
                && (klass.toString().contains("Ljavax/swing/") || klass.toString().contains("Lsun/swing/") || klass.toString()
                                                                                                                   .contains("Lcom/sun/java/swing"))) {
            swingClasses++;
            ReferenceVariable rv = getOrCreateSingleton(type);
            this.addStatement(stmtFactory.localToLocal(result, rv, pp));
            return true;
        }
        return false;
    }

    /**
     * Add statements for the generated allocation of an exception or return object of a given type for a native method
     * with no signature.
     *
     * @param m native method
     * @param type allocated type
     * @param summary summary reference variable for method exception or return
     */
    private void registerAllocationForNative(IMethod m, ProgramPoint pp, TypeReference type, ReferenceVariable summary) {
        IClass klass = AnalysisUtil.getClassHierarchy().lookupClass(type);
        assert klass != null : "No class found for " + m;
        if (!tryAllocateSingleton(klass, summary, pp)) {
            this.addStatement(stmtFactory.newForNative(summary, klass, m, pp));
        }
    }

    private void registerNative(IMethod m) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(m);
        int ppCount = 0;
        ProgramPoint entryPP = methodSummary.getEntryPP();
        if (!m.getReturnType().isPrimitiveType()) {
            // Allocation of return value
            ProgramPoint pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)
                    + " ALLOC " + PrettyPrinter.typeString(m.getReturnType())));
            this.registerAllocationForNative(m, pp, m.getReturnType(), methodSummary.getReturn());
            pp.addSucc(methodSummary.getNormalExitPP());
        }
        else {
            // The return value is not a reference so connect up the entry and exit
            entryPP.addSucc(methodSummary.getNormalExitPP());
        }

        boolean containsRTE = false;
        try {
            TypeReference[] exceptions = m.getDeclaredExceptions();
            if (exceptions != null) {
                for (TypeReference exType : exceptions) {
                    // Allocation of exception of a particular type
                    ReferenceVariable ex = ReferenceVariableFactory.createNativeException(exType, m);
                    ProgramPoint pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)
                            + " ALLOC " + PrettyPrinter.typeString(exType)));
                    this.registerAllocationForNative(m, pp, exType, ex);
                    pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++) + " ASSIGN "
                            + PrettyPrinter.typeString(exType)));
                    this.addStatement(StatementFactory.exceptionAssignment(ex,
                                                                           methodSummary.getException(),
                                                                           Collections.<IClass> emptySet(),
                                                                           pp));

                    containsRTE |= exType.equals(TypeReference.JavaLangRuntimeException);
                    pp.addSucc(methodSummary.getExceptionExitPP());
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
            ReferenceVariable ex = ReferenceVariableFactory.createNativeException(TypeReference.JavaLangRuntimeException,
                                                                                  m);
            ProgramPoint pp = nextProgramPoint(entryPP, new ProgramPoint(m, "native-method-pp-" + (ppCount++)
                    + " alloc " + PrettyPrinter.typeString(TypeReference.JavaLangRuntimeException)));
            this.registerAllocationForNative(m,
                                             pp,
                                             TypeReference.JavaLangRuntimeException,
                                             methodSummary.getException());
            pp = nextProgramPoint(pp, new ProgramPoint(m, "native-method-pp-" + (ppCount++) + " assign "
                    + PrettyPrinter.typeString(TypeReference.JavaLangRuntimeException)));
            this.addStatement(StatementFactory.exceptionAssignment(ex,
                                                                   methodSummary.getException(),
                                                                   Collections.<IClass> emptySet(),
                                                                   pp));
            pp.addSucc(methodSummary.getExceptionExitPP());
        }
    }

    private static ProgramPoint nextProgramPoint(ProgramPoint currPP, ProgramPoint nextPP) {
        currPP.addSucc(nextPP);
        return nextPP;
    }

    /**
     * Create statments for assignments from the formal parameters of a method to the local variables inside that
     * method.
     *
     * @param ir IR for containing procedure
     * @param rvFactory factory for creating reference variables
     * @param types repository for local variable types
     * @param pprint pretty printer for the method
     *
     * @return the program point for the last assignment
     */
    private ProgramPoint registerFormalAssignments(IR ir, ReferenceVariableFactory rvFactory, TypeRepository types,
                                                   PrettyPrinter pprint) {
        MethodSummaryNodes methodSummary = this.findOrCreateMethodSummary(ir.getMethod());
        ProgramPoint current = methodSummary.getEntryPP();
        for (int i = 0; i < ir.getNumberOfParameters(); i++) {
            int paramNum = ir.getParameter(i);
            TypeReference paramType = types.getType(paramNum);
            if (paramType.isPrimitiveType() && !ir.getSymbolTable().isNullConstant(paramNum)) {
                // No statements for primitives
                continue;
            }
            ReferenceVariable param = rvFactory.getOrCreateLocal(paramNum, paramType, ir.getMethod(), pprint);
            current = nextProgramPoint(current, new ProgramPoint(ir.getMethod(), param + " = formal" + "(" + i + ")"));
            this.addStatement(stmtFactory.localToLocal(param, methodSummary.getFormal(i), current));
        }
        return current;
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
     * Get the may-happen-after relationship for call sites within a given method
     *
     * @param m method
     * @return map from call-site to the set of call-sites that may occur after it
     */
    public Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> getCallSiteOrdering(IMethod m) {
        assert callSiteOrdering.containsKey(m);
        return callSiteOrdering.get(m);
    }

    /**
     * Listener for new statements added to the registrar
     */
    public interface StatementListener {
        /**
         * Called when a new statement is added to the registrar.
         *
         * @param stmt statement added
         */
        void newStatement(PointsToStatement stmt);
    }

    /**
     * Add a statement listener
     *
     * @param stmtListener Listener for new statements added to the registrar
     */
    public void setStatementListener(StatementListener stmtListener) {
        this.stmtListener = stmtListener;
    }

    /**
     * Get the entry point for the application
     *
     * @return entry point method
     */
    public IMethod getEntryPoint() {
        return this.entryMethod;
    }

    /**
     * Total number of program points
     */
    public static int totalProgramPoints() {
        return totalProgramPoints;
    }

    /**
     * Total number of unecessary program points that were removed
     */
    public static int totalProgramPointsRemoved() {
        return removedProgramPoints;
    }

    /**
     * Whether to print only for certain methods
     */
    public boolean shouldUseSimplePrint() {
        return simplePrint;
    }

    /**
     * Get program points for any call sites within the given method
     */
    public Set<CallSiteProgramPoint> getCallSitesWithinMethod(IMethod m) {
        Set<CallSiteProgramPoint> s = this.callSitesWithinMethod.get(m);
        if (s == null) {
            return Collections.emptySet();
        }
        return s;
    }

    /**
     * Map from instruction to program point
     */
    public Map<SSAInstruction, ProgramPoint> getInsToPP() {
        return this.insToPP;
    }

    /**
     * Write program-point successor graph to file for a given method.
     */
    public void dumpProgramPointSuccGraphToFile(IMethod m) {
        String file = PrettyPrinter.methodString(m);
        String fullFilename = file + "_ppgraph.dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpProgramPointSuccGraph(m, out);
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Write program-point successor graph to file for a given method.
     */
    public static void dumpProgramPointSuccGraphToFile(ProgramPoint entryPP) {
        String file = PrettyPrinter.methodString(entryPP.getContainingProcedure());
        String fullFilename = "tests/" + file + "_ppgraph.dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpProgramPointSuccGraph(entryPP, out);
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Write program-point successor graph to file.
     */
    public void dumpProgramPointSuccGraphToFile(String filename) {
        String file = filename;
        String fullFilename = file + ".dot";
        try (Writer out = new BufferedWriter(new FileWriter(fullFilename))) {
            dumpProgramPointSuccGraph(out);
            System.err.println("\nDOT written to: " + fullFilename);
        }
        catch (IOException e) {
            System.err.println("Could not write DOT to file, " + fullFilename + ", " + e.getMessage());
        }
    }

    /**
     * Write out the program point graph to the given writer
     */
    private static Writer dumpProgramPointSuccGraph(ProgramPoint entryPP, Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]" + ";\n");

        Set<ProgramPoint> visited = new HashSet<>();
        WorkQueue<ProgramPoint> q = new WorkQueue<>();
        q.add(entryPP);
        while (!q.isEmpty()) {
            ProgramPoint pp = q.poll();
            if (!visited.contains(pp)) {
                visited.add(pp);
                for (ProgramPoint succ : pp.succs()) {
                    String fromStr = escape(pp.toString());
                    String toStr = escape(succ.toString());
                    writer.write("\t\"" + fromStr + "\" -> \"" + toStr + "\";\n");
                    q.add(succ);
                }
            }
        }

        writer.write("};\n");
        return writer;
    }

    /**
     * Write out the program point graph to the given writer
     */
    private Writer dumpProgramPointSuccGraph(IMethod m, Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]" + ";\n");

        Set<ProgramPoint> visited = new HashSet<>();
        writeSucc(methods.get(m).getEntryPP(), writer, visited);

        writer.write("};\n");
        return writer;
    }

    /**
     * Write out the program point graph to the given writer
     */
    private Writer dumpProgramPointSuccGraph(Writer writer) throws IOException {
        double spread = 1.0;
        writer.write("digraph G {\n" + "nodesep=" + spread + ";\n" + "ranksep=" + spread + ";\n"
                + "graph [fontsize=10]" + ";\n" + "node [fontsize=10]" + ";\n" + "edge [fontsize=10]" + ";\n");

        Set<ProgramPoint> visited = new HashSet<>();

        for (IMethod m : methods.keySet()) {
            MethodSummaryNodes methSum = methods.get(m);
            if (simplePrint) {
                if (!m.getDeclaringClass().toString().contains("Exception")
                        && !m.getDeclaringClass().toString().contains("CaseInsensitiveComparator")
                        && !(m.getDeclaringClass().toString().contains("String") && m.isClinit())
                        && !(m.getDeclaringClass().toString().contains("Object") && m.isInit())) {
                    writeSucc(methSum.getEntryPP(), writer, visited);
                }
                continue;
            }

            writeSucc(methSum.getEntryPP(), writer, visited);
        }
        if (PointsToAnalysis.outputLevel > 1) {
            for (MethodSummaryNodes methSum : methods.values()) {
                if (!visited.contains(methSum.getEntryPP())) {
                    System.err.println("MISSING " + methSum.getEntryPP());
                }
                if (!visited.contains(methSum.getExceptionExitPP())) {
                    System.err.println("MISSING " + methSum.getExceptionExitPP());
                }
                if (!visited.contains(methSum.getNormalExitPP())) {
                    System.err.println("MISSING " + methSum.getNormalExitPP());
                }
            }
        }

        writer.write("};\n");
        return writer;
    }

    /**
     * Write all successor edges of the given program point
     */
    private void writeSucc(ProgramPoint pp, Writer writer, Set<ProgramPoint> visited) throws IOException {
        if (!visited.contains(pp)) {
            visited.add(pp);
            for (ProgramPoint succ : pp.succs()) {
                String fromStr = escape(pp.toString() + " : " + getStmtAtPP(pp));
                String toStr = escape(succ.toString() + " : " + getStmtAtPP(succ));
                writer.write("\t\"" + fromStr + "\" -> \"" + toStr + "\";\n");
                writeSucc(succ, writer, visited);
            }
        }
    }

    /**
     * Escape the string for dot format
     */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Program point graph for a particular instruction
     */
    private class PPSubGraph {

        /**
         * Entry program point
         */
        private ProgramPoint entry;
        /**
         * Program point for normal (non-exceptional) exit
         */
        private ProgramPoint normExit;
        /**
         * Program point right before the normal exit (null if there is only one PP for this instruction)
         */
        private ProgramPoint preNormExit;
        /**
         * Program points for exceptional exit, one for each exception type and successor block the type can reach
         */
        private final Map<TypeReference, Map<ISSABasicBlock, ProgramPoint>> thrownExceptions;
        /**
         * Program points for generated exception (generated) allocation sites
         */
        private final Map<TypeReference, ProgramPoint> genExAllocations;
        /**
         * Program points for generated exception throw sites
         */
        private final Map<TypeReference, Map<ISSABasicBlock, ProgramPoint>> generatedExceptions;
        /**
         * True if the instruction can exit normally, false if not (for a "throw" instruction)
         */
        private boolean canExitNormally;
        /**
         * Program point that represents exceptional exit from a callSite
         */
        private ProgramPoint callSiteException;

        /**
         * Create a new program point subgraph with a single node
         *
         * @param containingProcedure procedure the instructino is contained in
         * @param i instruction the graph is for
         * @param pprint pretty printer for the method
         */
        PPSubGraph(IMethod containingProcedure, SSAInstruction i, PrettyPrinter pprint) {
            this.entry = new ProgramPoint(containingProcedure, "i-entry " + pprint.instructionString(i));
            this.normExit = this.entry;
            this.preNormExit = null;
            this.thrownExceptions = new HashMap<>();
            this.genExAllocations = new HashMap<>();
            this.generatedExceptions = new HashMap<>();
            this.canExitNormally = true;
        }

        /**
         * Get the entry program point for the instruction
         */
        ProgramPoint entry() {
            return this.entry;
        }

        /**
         * Program point for normal (non-exceptional) exit
         */
        ProgramPoint normalExit() {
            return this.normExit;
        }

        /**
         * Set whether this instruction can exit normally
         *
         * @param canExitNormally True if the instruction can exit normally, false if not (for a "throw" instruction)
         */
        void setCanExitNormally(boolean canExitNormally) {
            if (!canExitNormally) {
                if (preNormExit != null) {
                    preNormExit.removeSucc(normExit);
                }
            }
            this.canExitNormally = canExitNormally;
        }

        /**
         * Program points for exceptional exit, one for each (succ basic block, exception type) pair
         */
        Map<TypeReference, Map<ISSABasicBlock, ProgramPoint>> getThrownExceptionExits() {
            return this.thrownExceptions;
        }

        /**
         * Program points for exceptional exit via generated exception, one for each (succ basic block, exception type)
         * pair
         */
        Map<TypeReference, Map<ISSABasicBlock, ProgramPoint>> getGeneratedExceptionExits() {
            return this.generatedExceptions;
        }

        /**
         * Get the allocation site for a generated exceptions
         *
         * @param type type of the exception
         * @return program point for the allocation site of the generated exception of the given type
         */
        public ProgramPoint getGenExAllocation(TypeReference type) {
            assert this.genExAllocations.containsKey(type) : "No allocation site for gen-ex " + type;
            return this.genExAllocations.get(type);
        }

        public ProgramPoint getCallSiteException(CallSiteReference callSiteReference) {
            if (this.callSiteException == null) {
                String name = PrettyPrinter.methodString(callSiteReference.getDeclaredTarget()) + "@"
                        + callSiteReference.getProgramCounter();
                this.callSiteException = new ProgramPoint(this.entry.containingProcedure(), "(callee-exception-exit) "
                        + name);
            }
            return this.callSiteException;
        }

        /**
         * Add a program point that throws an exception.
         *
         * @param successor catch (or exit) basic block the exception is exiting to
         * @param exType type of exception being added
         * @param isGenerated whether this exception is a JVM generated runtime exception (e.g. NullPointerException)
         *
         * @return the exception exit (i.e. the program point that throws the exception)
         */
        ProgramPoint addException(ISSABasicBlock successor, TypeReference exType, boolean isGenerated,
                                  boolean isCalleeThrow) {
            assert successor.isCatchBlock() || successor.isExitBlock();
            assert !isGenerated || !isCalleeThrow;
            if (this.entry == this.normExit) {
                String name;
                if (isCalleeThrow) {
                    name = "(callee-normal-exit)";
                }
                else {
                    name = "(inst-norm-exit)";
                }
                this.normExit = new ProgramPoint(this.entry.containingProcedure(), name);
                this.entry.addSucc(this.normExit);
                this.preNormExit = this.entry;
            }

            ProgramPoint pred;
            Map<TypeReference, Map<ISSABasicBlock, ProgramPoint>> map;
            if (isGenerated) {
                if (!useSingleAllocForGenEx) {
                    assert !genExAllocations.containsKey(exType) : "existing generation site for " + exType;
                    // Create an allocation site for the generated exception
                    ProgramPoint genPP = new ProgramPoint(this.entry.containingProcedure(), "(gen-ex) "
                            + PrettyPrinter.typeString(exType));
                    genExAllocations.put(exType, genPP);
                    this.entry.addSucc(genPP);
                    pred = genPP;
                }
                else {
                    pred = this.entry;
                }
                map = generatedExceptions;
            }
            else if (isCalleeThrow) {
                assert callSiteException != null;
                this.entry.addSucc(this.callSiteException);
                pred = this.callSiteException;
                map = thrownExceptions;
            }
            else {
                // This is not a generated exception so put the exception exit right after the entry pp
                pred = this.entry;
                map = thrownExceptions;
            }

            assert map.get(exType) == null || !map.get(exType).containsKey(successor.getNumber()) : "Dupe exception PP "
                    + exType + " -> BB" + successor.getNumber() + " gen?" + isGenerated;

            String name = (isGenerated ? "(gen) " : "") + "throw " + PrettyPrinter.typeString(exType) + " -> BB"
                    + successor.getNumber();
            ProgramPoint pp = new ProgramPoint(this.entry.containingProcedure(), name);
            Map<ISSABasicBlock, ProgramPoint> m = map.get(exType);
            if (m == null) {
                m = new HashMap<>();
                map.put(exType, m);
            }
            m.put(successor, pp);

            pred.addSucc(pp);
            return pp;
        }

        /**
         * Add an intermediate program point between the entry and normal exit of the subgraph
         *
         * @param debugString pretty string for the intermediate node
         * @return intermediate program point
         */
        ProgramPoint addIntermediateNormal(String debugString) {
            ProgramPoint interNode = new ProgramPoint(this.entry.containingProcedure(), debugString);
            if (this.entry == this.normExit) {
                assert this.preNormExit == null;
                this.normExit = new ProgramPoint(this.entry.containingProcedure(), "(inst-norm-exit)");
                this.entry.addSucc(interNode);
            }
            else {
                this.preNormExit.removeSucc(this.normExit);
                this.preNormExit.addSucc(interNode);
            }
            interNode.addSucc(this.normExit);
            this.preNormExit = interNode;
            return interNode;
        }

        /**
         * Replace the normal exit node for an invoke instruction with a CallSiteProgramPoint
         */
        void replaceEntryWithCallSitePP(CallSiteProgramPoint cspp) {
            assert (this.entry != this.normExit) : "Should be an exception PP at this point.";

            Set<ProgramPoint> succs = this.entry.succs();
            this.entry = cspp;
            this.entry.addSuccs(succs);
        }

        /**
         *
         * @return True if the instruction can exit normally, false if not (for a "throw" instruction)
         */
        public boolean canExitNormally() {
            return this.canExitNormally;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            WorkQueue<ProgramPoint> q = new WorkQueue<>();
            Set<ProgramPoint> visited = new HashSet<>();
            q.add(entry);
            while (!q.isEmpty()) {
                ProgramPoint pp = q.poll();
                visited.add(pp);
                if (pp == entry && pp.succs().isEmpty()) {
                    sb.append(pp.toString());
                    break;
                }
                for (ProgramPoint succ : pp.succs()) {
                    sb.append(pp.toString() + " -> " + succ.toString() + "\n");
                    if (!visited.contains(succ)) {
                        q.add(succ);
                    }
                }
            }
            return sb.toString();
        }
    }

    /**
     * Get the program point for normal exit of a static class initialization method
     *
     * @param clinit method to get the pp for
     * @return program point
     */
    public CallSiteProgramPoint getClassInitPP(IMethod clinit) {
        CallSiteProgramPoint cspp = programPointForClassInit.get(clinit);
        assert cspp != null : "Missing program point for " + PrettyPrinter.methodString(clinit);
        return cspp;
    }

    /**
     * Compute the call-site ordering for the entry method (this is the only one that may change since class init
     * methods may be added during statement registration)
     */
    public void computeFakeRootOrdering() {
        Map<CallSiteProgramPoint, Set<CallSiteProgramPoint>> ordering = new CallSiteOrdering().getForMethod(getMethodSummary(this.entryMethod).getEntryPP());
        callSiteOrdering.put(this.entryMethod, ordering);
    }
}
