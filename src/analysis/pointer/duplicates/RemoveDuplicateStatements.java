package analysis.pointer.duplicates;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import util.OrderedPair;
import analysis.AnalysisUtil;
import analysis.pointer.registrar.ReferenceVariableFactory.ReferenceVariable;
import analysis.pointer.statements.ArrayToLocalStatement;
import analysis.pointer.statements.CallStatement;
import analysis.pointer.statements.ExceptionAssignmentStatement;
import analysis.pointer.statements.FieldToLocalStatement;
import analysis.pointer.statements.PhiStatement;
import analysis.pointer.statements.PointsToStatement;
import analysis.pointer.statements.StaticFieldToLocalStatement;

/**
 * Analysis to find and remove duplicate points-to statements
 */
public class RemoveDuplicateStatements {

    /**
     * Print out the sets before and after removing duplicates
     */
    public static boolean DEBUG = false;

    /**
     * Map from variable to the points-to statements that use the variable and the index into the use list
     */
    private final Map<ReferenceVariable, Set<OrderedPair<PointsToStatement, Integer>>> useIndex = new LinkedHashMap<>();
    /**
     * non-static field accesses. This will be modified as duplicate statements are removed.
     */
    private final List<FieldToLocalStatement> fieldToLocals = new ArrayList<>();
    /**
     * SSA phi statements. This will be modified as duplicate statements are removed.
     */
    private final List<PhiStatement> phiStatements = new ArrayList<>();
    /**
     * array access statements. This will be modified as duplicate statements are removed.
     */
    private final List<ArrayToLocalStatement> arrayToLocals = new ArrayList<>();
    /**
     * static field accesses. This will be modified as duplicate statements are removed.
     */
    private final List<StaticFieldToLocalStatement> staticFieldToLocals = new ArrayList<>();
    /**
     * procedure calls. This will be modified as duplicate statements are removed.
     */
    private final List<CallStatement> callStatements = new ArrayList<>();
    /**
     * assignments into exit blocks or catch blocks. This will be modified as duplicate statements are removed.
     */
    private final List<ExceptionAssignmentStatement> exceptions = new ArrayList<>();

    // TODO remove duplicate check cast statements, need to separate from formal assignments first
    /**
     * Set of points-to statements. This will be modified as duplicate statements are removed.
     */
    private final Set<PointsToStatement> allStatements;

    /**
     * Mapping from variables to their replacement
     */
    private final VariableIndex variableIndex = new VariableIndex();

    /**
     * Create analysis that will find and remove duplicate points-to statements in the given set
     *
     * @param statements
     *            set of points-to statements
     */
    private RemoveDuplicateStatements(Set<PointsToStatement> statements) {
        this.allStatements = statements;
    }

    /**
     * Initialize the reverse index from variable to the set of statements that use them and the sets of different types
     * of statements
     *
     * @param statements
     *            set of all points-to statements
     */
    private void initializeIndices() {
        for (PointsToStatement s : allStatements) {
            List<ReferenceVariable> uses = s.getUses();
            int useNum = 0;
            for (ReferenceVariable rv : uses) {
                Set<OrderedPair<PointsToStatement, Integer>> pairs = this.useIndex.get(rv);
                if (pairs == null) {
                    pairs = AnalysisUtil.createConcurrentSet();
                    this.useIndex.put(rv, pairs);
                }
                pairs.add(new OrderedPair<>(s, useNum));
                useNum++;
            }
            if (s instanceof FieldToLocalStatement) {
                this.fieldToLocals.add((FieldToLocalStatement) s);
                continue;
            }

            if (s instanceof PhiStatement) {
                this.phiStatements.add((PhiStatement) s);
                continue;
            }

            if (s instanceof ArrayToLocalStatement) {
                this.arrayToLocals.add((ArrayToLocalStatement) s);
                continue;
            }

            if (s instanceof StaticFieldToLocalStatement) {
                this.staticFieldToLocals.add((StaticFieldToLocalStatement) s);
                continue;
            }

            if (s instanceof CallStatement) {
                this.callStatements.add((CallStatement) s);
                continue;
            }

            if (s instanceof ExceptionAssignmentStatement) {
                this.exceptions.add((ExceptionAssignmentStatement) s);
                continue;
            }
        }
    }

    /**
     * Find and remove duplicate points-to statements in the given set. The input set will be modified.
     *
     * @param statements
     *            set of points-to statements (will be modified)
     * @return set with duplicate statements removed
     */
    public static OrderedPair<Set<PointsToStatement>, VariableIndex> removeDuplicates(Set<PointsToStatement> statements) {
        long startTime = System.currentTimeMillis();
        int startSize = statements.size();

        if (DEBUG) {
            System.err.println("BEFORE:");
            for (PointsToStatement s : statements) {
                System.err.println("\t" + s);
            }
        }

        RemoveDuplicateStatements analysis = new RemoveDuplicateStatements(statements);
        analysis.initializeIndices();
        analysis.handleExceptions();

        boolean changed = true;
        while (changed) {
            changed = false;
            changed |= analysis.handleFieldToLocal();
            changed |= analysis.handlePhi();
            changed |= analysis.handleArrayToLocal();
            changed |= analysis.handleStaticFieldToLocal();
            changed |= analysis.handleCall();
        }

        if (DEBUG && startSize != analysis.allStatements.size()) {
            System.err.println("AFTER:");
            for (PointsToStatement s : analysis.allStatements) {
                System.err.println("\t" + s);
            }
            System.err.println("Finished removing " + (startSize - analysis.allStatements.size()) + " duplicates: "
                                            + (System.currentTimeMillis() - startTime) + "ms");
        }
        return new OrderedPair<>(analysis.allStatements, analysis.variableIndex);
    }

    private boolean handleExceptions() {
        // Here we will only remove identical statements since the assignee will usually have multiple assignments
        // (breaking the SSA invariant)
        for (int i = 0; i < this.exceptions.size(); i++) {
            ExceptionAssignmentStatement s1 = this.exceptions.get(i);

            if (s1 == null) {
                // We've already replaced this statement
                continue;
            }

            for (int j = i + 1; j < this.exceptions.size(); j++) {
                ExceptionAssignmentStatement s2 = this.exceptions.get(j);

                if (s2 == null) {
                    // We've already replaced this statement
                    continue;
                }

                if (s1.getCaughtException().equals(s2.getCaughtException()) && s1.getUses().equals(s2.getUses())
                                                && s1.getNotTypes().equals(s2.getNotTypes())) {
                    if (DEBUG) {
                        System.err.println("REMOVING " + s2);
                    }
                    this.exceptions.set(j, null);
                    this.allStatements.remove(s2);
                }
            }

        }
        // Only exact copies are removed so no need to iterate as it doesn't affect the results of detecting other
        // duplicates
        return false;
    }

    @SuppressWarnings("static-method")
    private boolean handleCall() {
        // TODO determine duplicates from the HeapAbstractionFactory
        return false;
        // boolean changed = false;
        // for (int i = 0; i < this.callStatements.size(); i++) {
        // CallStatement s1 = this.callStatements.get(i);
        //
        // if (s1 == null) {
        // // We've already replaced this statement
        // continue;
        // }
        //
        // for (int j = i + 1; j < this.callStatements.size(); j++) {
        // CallStatement s2 = this.callStatements.get(j);
        //
        // if (s2 == null) {
        // // We've already replaced this statement
        // continue;
        // }
        //
        // if (s1.getCallee().equals(s2.getCallee()) && s1.getUses().equals(s2.getUses())) {
        // changed = true;
        // this.callStatements.set(j, null);
        // this.allStatements.remove(s2);
        // replaceVariable(s2.getDef(), s1.getDef());
        // }
        // }
        //
        // }
        // return changed;
    }

    private boolean handleStaticFieldToLocal() {
        boolean changed = false;
        for (int i = 0; i < this.staticFieldToLocals.size(); i++) {
            StaticFieldToLocalStatement s1 = this.staticFieldToLocals.get(i);

            if (s1 == null) {
                // We've already replaced this statement
                continue;
            }

            for (int j = i + 1; j < this.staticFieldToLocals.size(); j++) {
                StaticFieldToLocalStatement s2 = this.staticFieldToLocals.get(j);

                if (s2 == null) {
                    // We've already replaced this statement
                    continue;
                }

                if (s1.getStaticField().equals(s2.getStaticField()) && s1.getUses().equals(s2.getUses())) {
                    if (DEBUG) {
                        System.err.println("REMOVING " + s2);
                        System.err.println("\tREPLACING WITH " + s1);
                    }
                    changed = true;
                    this.staticFieldToLocals.set(j, null);
                    this.allStatements.remove(s2);
                    replaceVariable(s2.getDef(), s1.getDef());
                }
            }

        }
        return changed;
    }

    private boolean handleArrayToLocal() {
        boolean changed = false;
        for (int i = 0; i < this.arrayToLocals.size(); i++) {
            ArrayToLocalStatement s1 = this.arrayToLocals.get(i);

            if (s1 == null) {
                // We've already replaced this statement
                continue;
            }

            for (int j = i + 1; j < this.arrayToLocals.size(); j++) {
                ArrayToLocalStatement s2 = this.arrayToLocals.get(j);

                if (s2 == null) {
                    // We've already replaced this statement
                    continue;
                }

                if (s1.getUses().equals(s2.getUses())) {
                    if (DEBUG) {
                        System.err.println("REMOVING " + s2);
                        System.err.println("\tREPLACING WITH " + s1);
                    }
                    changed = true;
                    this.arrayToLocals.set(j, null);
                    this.allStatements.remove(s2);
                    replaceVariable(s2.getDef(), s1.getDef());
                }
            }

        }
        return changed;
    }

    private boolean handlePhi() {
        boolean changed = false;
        for (int i = 0; i < this.phiStatements.size(); i++) {
            PhiStatement s1 = this.phiStatements.get(i);

            if (s1 == null) {
                // We've already replaced this statement
                continue;
            }

            for (int j = i + 1; j < this.phiStatements.size(); j++) {
                PhiStatement s2 = this.phiStatements.get(j);

                if (s2 == null) {
                    // We've already replaced this statement
                    continue;
                }

                Set<ReferenceVariable> uses1 = new HashSet<>(s1.getUses());
                Set<ReferenceVariable> uses2 = new HashSet<>(s2.getUses());
                if (uses1.containsAll(uses2) && uses2.containsAll(uses1)) {
                    if (DEBUG) {
                        System.err.println("REMOVING " + s2);
                        System.err.println("\tREPLACING WITH " + s1);
                    }
                    changed = true;
                    this.phiStatements.set(j, null);
                    this.allStatements.remove(s2);
                    replaceVariable(s2.getDef(), s1.getDef());
                }
            }

        }
        return changed;
    }

    private boolean handleFieldToLocal() {
        boolean changed = false;
        for (int i = 0; i < this.fieldToLocals.size(); i++) {
            FieldToLocalStatement s1 = this.fieldToLocals.get(i);

            if (s1 == null) {
                // We've already replaced this statement
                continue;
            }

            for (int j = i + 1; j < this.fieldToLocals.size(); j++) {
                FieldToLocalStatement s2 = this.fieldToLocals.get(j);

                if (s2 == null) {
                    // We've already replaced this statement
                    continue;
                }

                if (s1.getField().equals(s2.getField()) && s1.getUses().equals(s2.getUses())) {
                    if (DEBUG) {
                        System.err.println("REMOVING " + s2);
                        System.err.println("\tREPLACING WITH " + s1);
                    }
                    changed = true;
                    this.fieldToLocals.set(j, null);
                    this.allStatements.remove(s2);
                    replaceVariable(s2.getDef(), s1.getDef());
                }
            }

        }
        return changed;
    }

    /**
     * Replace all uses of <code>replaced</code> with <code>replacement</code>
     *
     * @param replaced
     *            variable to be replaced
     * @param replacement
     *            replacement variable
     */
    @SuppressWarnings("synthetic-access")
    private void replaceVariable(ReferenceVariable replaced, ReferenceVariable replacement) {
        variableIndex.recordReplacement(replaced, replacement);
        Set<OrderedPair<PointsToStatement, Integer>> pairs = this.useIndex.get(replaced);
        if (pairs == null) {
            // There are no uses of that variable
            return;
        }
        Set<OrderedPair<PointsToStatement, Integer>> setForNew = this.useIndex.get(replacement);
        if (setForNew == null) {
            setForNew = new LinkedHashSet<>();
            useIndex.put(replacement, setForNew);
        }

        for (OrderedPair<PointsToStatement, Integer> pair : pairs) {
            PointsToStatement s = pair.fst();
            if (!allStatements.contains(s)) {
                // This statement has already been removed
                continue;
            }
            s.replaceUse(pair.snd(), replacement);
            // The use is in the same place but is now using the replacement variable
            setForNew.add(pair);
        }
        // We do not need the set of uses for the replaced variable as it no longer exists
        this.useIndex.remove(replaced);
    }

    /**
     * Index mapping reference variable to other reference variables that have the same points-to sets. This allows us
     * to only compute the points-to sets once for (logically) duplicate statements.
     */
    public static class VariableIndex {
        private final Map<ReferenceVariable, ReferenceVariable> index = new LinkedHashMap<>();

        private void recordReplacement(ReferenceVariable replaced, ReferenceVariable replacement) {
            index.put(replaced, replacement);
        }

        /**
         * Get the reference variable the points-to analysis is using for the given reference variable. This may be the
         * same as the reference variable passed in or may be a variable that has the same points-to set.
         *
         * @param rv non-null reference variable to look up
         * @return reference variable with the same points-to set as <code>rv</code>
         */
        public ReferenceVariable lookup(ReferenceVariable rv) {
            ReferenceVariable replacement = index.get(rv);
            while (replacement != null) {
                rv = replacement;
                replacement = index.get(rv);
            }
            assert rv != null;
            return rv;
        }

    }
}
