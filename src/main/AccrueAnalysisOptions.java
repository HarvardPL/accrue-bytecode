package main;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.CrossProduct;
import analysis.pointer.analyses.HeapAbstractionFactory;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public final class AccrueAnalysisOptions {

    /**
     * Classpath to use when none is specified
     */
    private static final String DEFAULT_CLASSPATH = "classes/test:classes/signatures";

    /**
     * Flag for printing useage information
     */
    @Parameter(names = { "-h", "-help", "-useage", "--help" }, description = "Print useage information")
    private boolean help = false;

    /**
     * Flag for running the pointer analysis single-threaded after reaching a fixed point in the multi-threaded analysis
     */
    @Parameter(
        names = { "-paranoidPointerAnalysis" },
        description = "If set, Set the analysis to reprocess all statements (single-threaded) after running the multi-threaded analysis.")
    private boolean paranoidPointerAnalysis = false;

    /**
     * Flag for redundant variable names
     */
    @Parameter(
        names = { "-useDebugNames" },
        description = "If set, use both names from source code (when available) and numerical values for variables, otherwise just use names from source code.")
    private boolean useDebugNames = false;

    /**
     * Flag for writing the PDG to a graphviz "dot" file as well as a JSON file
     */
    @Parameter(
        names = { "-writeDotPDG" },
        description = "If set, write a graphviz .dot file for the PDG in addition to a JSON file")
    private boolean writeDotPDG = false;

    /**
     * Level of output
     */
    @Parameter(names = { "-output", "-o" }, description = "Level of output (higher means more console output)")
    private Integer outputLevel = 0;

    /**
     * Level of file output
     */
    @Parameter(names = "-fileLevel", description = "Level of file output (higher means more files will be written)")
    private Integer fileLevel = 0;

    /**
     * Analysis entry point (contains the main method)
     */
    @Parameter(names = { "-e", "-entry", "-s", "-start" }, description = "The entry point (containing a main method) written as a full class name with packages separated by dots (e.g. java.lang.String)")
    private String entryPoint;

    /**
     * Name of class to print CFG for if cfg-for-class analysis is being run
     */
    @Parameter(
        names = { "-cn", "-classname" },
        description = "Name of class to print CFGs for if cfg-for-class analysis is being run")
    private String className;

    /**
     * If false then register points-to statements during the points-to analysis. If true then register them before the
     * points-to analysis. The latter will register many more statements since there is less information about the types
     * of the receivers of virtual methods.
     */
    @Parameter(names = { "-offline" }, description = "Whether to register the points-to statements during points-to analysis or before points-to analysis")
    private boolean offline = false;

    /**
     * Name of the analysis to be run
     */
    @Parameter(names = { "-n", "-analyisName" }, validateWith = AccrueAnalysisOptions.AnalysisNameValidator.class, description = "Name of the analysis to run.")
    private String analyisName;

    /**
     * Validate the requested analysis name
     */
    public static class AnalysisNameValidator implements IParameterValidator {

        @Override
        public void validate(String name, String value) throws ParameterException {
            if (value.equals("pointsto")) {
                return;
            }
            if (value.equals("maincfg")) {
                return;
            }
            if (value.equals("precise-ex")) {
                return;
            }
            if (value.equals("cfg")) {
                return;
            }
            if (value.equals("pdg")) {
                return;
            }
            if (value.equals("bool")) {
                return;
            }
            if (value.equals("android-cfg")) {
                return;
            }
            if (value.equals("string-main")) {
                return;
            }
            if (value.equals("reachability")) {
                return;
            }
            if (value.equals("cfg-for-class")) {
                return;
            }
            System.err.println("Invalid analysis name: " + value);
            System.err.println(analysisNameUsage());
            throw new ParameterException("Invalid analysis name: " + value);
        }
    }

    /**
     * Heap abstraction factory definition
     */
    @Parameter(names = { "-haf", "-heapAbstractionFactory" }, validateWith = AccrueAnalysisOptions.HafValidator.class, description = "The HeapAbstractionFactory class defining how analysis contexts are created.")
    private String hafString = "[type(2,1), scs(2)]";
    /**
     * {@link HeapAbstractionFactory} defining how analysis contexts are created
     */
    private HeapAbstractionFactory haf;

    /**
     * Validate the requested {@link HeapAbstractionFactory} name. SIDE EFFECT: If the parameter is valid then this sets
     * the {@link HeapAbstractionFactory} in {@link AccrueAnalysisOptions}.
     */
    public static class HafValidator implements IParameterValidator {
        @Override
        public void validate(String name, String value) throws ParameterException {
            // validate by attempting to construct the heap abstraction factory.
            // This will unfortunately end up creating the heap abstraction factory multiple times
            parseHaf(value);
        }
    }

    /**
     * Class path to be used by the analysis
     */
    @Parameter(names = { "-analysisClassPath", "-cp" }, description = "Classpath containing code for application and libraries to be analyzed.")
    private String analysisClassPath = DEFAULT_CLASSPATH;

    public AccrueAnalysisOptions() {
        // Do not instantiate
    }

    /**
     * Parse the options for the given args
     *
     * @param args
     * @return
     */
    @SuppressWarnings("unused")
    public static AccrueAnalysisOptions getOptions(String[] args) {
        AccrueAnalysisOptions o = new AccrueAnalysisOptions();
        new JCommander(o, args);
        return o;
    }

    /**
     * Should we print numerical variable names as well as names from the souce
     *
     * @return true if we should print both types of variable names
     */
    public boolean useDebugVariableNames() {
        return useDebugNames;
    }

    /**
     * If set, Set the analysis to reprocess all statements (single-threaded) after running the multi-threaded analysis.
     *
     * @return true if we want to double check the multi-threaded pointer analysis results
     */
    public boolean isParanoidPointerAnalysis() {
        return paranoidPointerAnalysis;
    }

    public Integer getOutputLevel() {
        return outputLevel;
    }

    public Integer getFileLevel() {
        return fileLevel;
    }

    public boolean registerOnline() {
        return !offline;
    }

    public String getEntryPoint() {
        if (analyisName == null) {
            throw new ParameterException("Must specify an entry point.");
        }
        return entryPoint;
    }

    public String getAnalysisName() {
        if (analyisName == null) {
            throw new ParameterException("Must specify the analysis to run.");
        }
        return analyisName;
    }

    public String getAnalysisClassPath() {
        if (!analysisClassPath.contains("classes/signatures")) {
            analysisClassPath += ":classes/signatures";
        }
        return analysisClassPath;
    }


    public HeapAbstractionFactory getHaf() {
        if (this.haf == null) {
            this.haf = parseHaf(this.hafString);
        }
        return this.haf;
    }

    /*
     * The grammar is:
     *
     * hafs ::=
     *         haf                        // a single heap abstraction factory
     *       | haf "," hafs               // a cross-product heap abstraction factory
     *       | haf "x" hafs               // a cross-product heap abstraction factory
     *
     * haf ::=
     *         hafClassName               // a heap abstraction factory class, default constructor
     *       | hafClassName "(" args ")"  // a heap abstraction factory class, pass args to constructor

     * hafClassName ::=
     *         "type"                     // synonym for "analysis.pointer.analyses.TypeSensitive"
     *       | "scs"                      // synonym for "analysis.pointer.analyses.StaticCallSiteSensitive"
     *       | "cs"                       // synonym for "analysis.pointer.analyses.CallSiteSensitive"
     *       | "full"                     // synonym for "analysis.pointer.analyses.FullObjSensitive"
     *       | cn                         // name of heap abstraction factory class. Either a fully qualified class name, or short name of a class in package analysis.pointer.analyses.
     *
     * cn ::= id
     *      | cn "." id                   // a class name
     *
     * args ::= arg | arg "," args        // a list of arguments
     *
     * arg ::= int | "{" hafs "}"         // an arg is either an int or a heap abstraction factory.
     *
     * @param hafString
     * @return
     */

    /**
     * Parse a heap abstraction factory.
     *
     */
    protected static HeapAbstractionFactory parseHaf(String hafString) {
        try {
            OrderedPair<HeapAbstractionFactory, Integer> p = parseHafs(hafString, 0);
            if (p.snd() != hafString.length()) {
                throw new ParseException();
            }
            return p.fst();

        }
        catch (ParseException e) {
            System.err.println("Could not parse HeapAbstractionFactory name: " + hafString);
            System.err.println(heapAbstractionUsage());
            throw new ParameterException(e);
        }
    }

    private static class ParseException extends Exception {
        public ParseException() {
        }

        public ParseException(String m) {
            super(m);
        }

    }

    protected static OrderedPair<HeapAbstractionFactory, Integer> parseHafs(String hafString, int ind)
                                                                                                      throws ParseException {
        List<HeapAbstractionFactory> hafs = new ArrayList<>();
        OrderedPair<HeapAbstractionFactory, Integer> op = parseBaseHaf(hafString, ind);
        hafs.add(op.fst());
        ind = op.snd();
        while (ind < hafString.length() && (hafString.charAt(ind) == ',' || hafString.charAt(ind) == 'x')) {
            ind++;
            OrderedPair<HeapAbstractionFactory, Integer> next = parseBaseHaf(hafString, ind);
            hafs.add(next.fst());
            ind = next.snd();
        }

        HeapAbstractionFactory haf = null;
        for (HeapAbstractionFactory h : hafs) {
            if (haf == null) {
                haf = h;
            }
            else {
                haf = new CrossProduct(h, haf);
            }
        }
        return new OrderedPair<HeapAbstractionFactory, Integer>(haf, ind);

    }

    protected static OrderedPair<HeapAbstractionFactory, Integer> parseBaseHaf(String hafString, int ind)
                                                                                                         throws ParseException {
        ind = consumeWhiteSpace(hafString, ind);
        OrderedPair<String, Integer> op = parseClassName(hafString, ind);
        if (op == null) {
            throw new ParseException();
        }
        String hafClassname = op.fst();
        ind = op.snd();

        // try to parse args
        List<Object> args = null;
        if (ind < hafString.length() && hafString.charAt(ind) == '(') {
            ind++; // consume the paren
            OrderedPair<List<Object>, Integer> argsParse = parseArgs(hafString, ind);
            args = argsParse.fst();
            ind = argsParse.snd();
            if (hafString.charAt(ind) != ')') {
                throw new ParseException();
            }
            ind++; // consume the close paren
        }
        ind = consumeWhiteSpace(hafString, ind);
        HeapAbstractionFactory haf = constructHaf(hafClassname, args);
        return new OrderedPair<>(haf, ind);
    }


    private static OrderedPair<String, Integer> parseClassName(String s, int ind) {
        StringBuffer cn = new StringBuffer();
        OrderedPair<String, Integer> p = parseId(s, ind);
        cn.append(p.fst());
        ind = p.snd();
        while (s.charAt(ind) == '.') {
            ind++; // consume ","
            cn.append('.');
            p = parseId(s, ind);
            cn.append(p.fst());
            ind = p.snd();
        }
        ind = consumeWhiteSpace(s, ind);
        return new OrderedPair<String, Integer>(cn.toString(), ind);
    }

    private static OrderedPair<String, Integer> parseId(String s, int ind) {
        StringBuffer id = new StringBuffer();
        while (ind < s.length() && Character.isAlphabetic(s.charAt(ind))) {
            id.append(s.charAt(ind++));
        }
        return new OrderedPair<String, Integer>(id.toString(), ind);
    }

    private static OrderedPair<List<Object>, Integer> parseArgs(String s, int ind) throws ParseException {
        List<Object> args = new ArrayList<>();
        OrderedPair<Object, Integer> p = parseArg(s, ind);
        args.add(p.fst());
        ind = p.snd();
        ind = consumeWhiteSpace(s, ind);
        while (s.charAt(ind) == ',') {
            ind++; // consume ","
            ind = consumeWhiteSpace(s, ind);
            p = parseArg(s, ind);
            args.add(p.fst());
            ind = p.snd();
        }
        return new OrderedPair<>(args, ind);
    }

    private static OrderedPair<Object, Integer> parseArg(String s, int ind) throws ParseException {
        ind = consumeWhiteSpace(s, ind);
        if (s.charAt(ind) == '{') {
            ind++; // consume '{'
            OrderedPair<HeapAbstractionFactory, Integer> h = parseHafs(s, ind);
            ind = h.snd();
            ind = consumeWhiteSpace(s, ind);
            if (s.charAt(ind) != '}') {
                throw new ParseException();
            }
            ind++; // consume '}'
            ind = consumeWhiteSpace(s, ind);
            return new OrderedPair<Object, Integer>(h.fst(), ind);
        }
        // we should have an int
        StringBuffer sb = new StringBuffer();
        while (ind < s.length() && Character.isDigit(s.charAt(ind))) {
            sb.append(s.charAt(ind++));
        }
        return new OrderedPair<Object, Integer>(Integer.valueOf(sb.toString()), ind);

    }

    private static int consumeWhiteSpace(String s, int ind) {
        while (ind < s.length() && Character.isWhitespace(s.charAt(ind))) {
            ind++;
        }
        return ind;
    }

    protected static HeapAbstractionFactory constructHaf(String hafClassname, List<Object> args) throws ParseException {
        Class<?> c;
        try {
            if (hafClassname == null) {
                throw new ParseException();
            }
            if ("type".equals(hafClassname)) {
                hafClassname = "analysis.pointer.analyses.TypeSensitive";
            }
            else if ("scs".equals(hafClassname)) {
                hafClassname = "analysis.pointer.analyses.StaticCallSiteSensitive";
            }
            else if ("cs".equals(hafClassname)) {
                hafClassname = "analysis.pointer.analyses.CallSiteSensitive";
            }
            else if ("full".equals(hafClassname)) {
                hafClassname = "analysis.pointer.analyses.FullObjSensitive";
            }
            c = Class.forName(hafClassname);
        }
        catch (ClassNotFoundException e) {
            // try again but prepend the analysis package
            try {
                c = Class.forName("analysis.pointer.analyses." + hafClassname);
            }
            catch (ClassNotFoundException e1) {
                throw new ParseException("HeapAbstractionFactory class not found: " + hafClassname);
            }
        }
        if (args == null) {
            args = Collections.emptyList();
        }
        Class<?>[] argClasses = new Class[args.size()];
        int j = 0;
        for (Object a : args) {
            if (a instanceof Integer) {
                argClasses[j++] = int.class;
            }
            else {
                argClasses[j++] = HeapAbstractionFactory.class;
            }
        }

        Constructor<?> cons;
        try {
            cons = c.getConstructor(argClasses);
        }
        catch (NoSuchMethodException | SecurityException e) {
            throw new ParseException("HeapAbstractionFactory constructor not found: " + hafClassname + " with "
                    + args.size() + " arguments");
        }

        try {
            return (HeapAbstractionFactory) cons.newInstance(args.toArray());
        }
        catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new ParseException("Could not invoke HeapAbstractionFactory constructor: " + hafClassname + " with "
                    + args.size() + " arguments; arguments were: " + args);
        }

    }

    /**
     * Should we print the useage information
     *
     * @return true if we should print useage
     */
    public boolean shouldPrintUseage() {
        return help;
    }

    public static String getUseage() {
        StringBuilder sb = new StringBuilder();
        AccrueAnalysisOptions o = new AccrueAnalysisOptions();
        JCommander jc = new JCommander(o);
        jc.usage(sb);
        return sb.toString() + "\n" + analysisNameUsage() + "\n" + heapAbstractionUsage();
    }

    /**
     * Print the parameter mapping for the main method
     *
     * @return String containing the documentation
     */
    protected static String analysisNameUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Supported analyses:\n");
        sb.append("\tpointsto - runs the points-to analysis, saves graph in tests folder with the name: \"entryClassName_ptg.dot\"\n");
        sb.append("\tmaincfg - prints the cfg for the main method to the tests folder with the name: \"entryClassName_main_cfg.dot\"\n");
        sb.append("\tnonnull - prints the results of an interprocedural non-null analysis to the tests folder prepended with \"nonnull_\" \n");
        sb.append("\tprecise-ex - prints the results of an interprocedural precise exception analysis to the tests folder prepended with \"precise_ex_\"\n");
        sb.append("\treachability - prints the results of an interprocedural reachability analysis to the tests folder prepended with \"reachability_\"\n");
        sb.append("\tcfg - prints the cfg for the all methods to the tests folder prepended with : \"cfg_\"\n");
        sb.append("\tpdg - prints the pdg in graphviz dot format to the tests folder prepended with : \"pdg_\"\n");
        sb.append("\tbool - prints the results of an analysis determining which variables are boolean constants in graphviz dot format to the tests folder prepended with : \"bool_\"\n");
        return sb.toString();
    }

    protected static String heapAbstractionUsage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Supported analyses:\n");
        sb.append("\ttype - Type Sensitive analysis (default parameters 2Type+1H)\n");
        sb.append("\tscs - Analysis that tracks call-sites for static methods (default parameter 2 call-sites)\n");
        sb.append("\tcs - Analysis that tracks call-sites (default parameter 2 call-sites)\n");
        sb.append("\tfull - Full Object Sensitive analyis (default parameter 2 allocation sites -- always 1H)\n");
        sb.append("\t(OTHER) - specify the full class name or simple class name if it is in the analysis.pointer.analyses package plus any integer parameters\n");
        return sb.toString();
    }

    public boolean shouldWriteDotPDG() {
        return writeDotPDG;
    }

    /**
     * Get the class name to write control graphs for when cfg-for-class analysis is selected
     *
     * @return name name of class to write CFG files for
     */
    public String getClassNameForCFG() {
        if (className == null) {
            throw new RuntimeException("Specify the class name to print CFGs for with the -cn or -className options");
        }
        return className;
    }

}
