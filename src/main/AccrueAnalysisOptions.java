package main;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedList;
import java.util.List;

import util.OrderedPair;
import analysis.pointer.analyses.CrossProduct;
import analysis.pointer.analyses.HeapAbstractionFactory;
import analysis.pointer.analyses.StaticCallSiteSensitive;
import analysis.pointer.analyses.TypeSensitive;
import analysis.pointer.analyses.parser.HeapAbstractionFactoryParser;

import com.beust.jcommander.IParameterValidator;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

public final class AccrueAnalysisOptions {

    /**
     * Classpath to use when none is specified
     */
    private static final String DEFAULT_CLASSPATH = "classes/test:classes/signatures";

    @Parameter(names = { "-h", "-help", "-useage", "--help" }, description = "Print useage information")
    private boolean help = false;

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
    @Parameter(names = { "-e", "-entry" }, description = "The entry point (containing a main method) written as a full class name with packages separated by dots (e.g. java.lang.String)")
    private String entryPoint;

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
            System.err.println("Invalid analysis name: " + value);
            System.err.println(analysisNameUsage());
            throw new ParameterException("Invalid analysis name: " + value);
        }
    }

    /**
     * Heap abstraction factory definition
     */
    @Parameter(names = { "-haf", "-heapAbstractionFactory" }, description = "The HeapAbstractionFactory class defining how analysis contexts are created.")
    private String hafString = "[type(2,1), scs(2)]";
    /**
     * {@link HeapAbstractionFactory} defining how analysis contexts are created
     */
    private HeapAbstractionFactory haf;

    /**
     * Validate the requested {@link HeapAbstractionFactory} name. SIDE EFFECT: If the parameter is valid then this sets
     * the {@link HeapAbstractionFactory} in {@link AccrueAnalysisOptions}.
     */
    public class HafValidator implements IParameterValidator {
        @Override
        public void validate(String name, String value) throws ParameterException {
            List<OrderedPair<String, List<Integer>>> classes;
            try {
                classes = HeapAbstractionFactoryParser.parse(value);
            } catch (Exception e) {
                System.err.println("Could not parse HeapAbstractionFactory name: " + value);
                System.err.println(heapAbstractionUsage());
                throw new ParameterException(e);
            }

            List<HeapAbstractionFactory> children = new LinkedList<>();
            for (OrderedPair<String, List<Integer>> constructor : classes) {
                String className = constructor.fst();
                List<Integer> args = constructor.snd();
                Class<?> c;
                try {
                    if (className == null) {
                        System.err.println(value);
                        System.err.println();
                    }
                    c = Class.forName(className);
                } catch (ClassNotFoundException e) {
                    // try again but prepend the analysis package
                    try {
                        c = Class.forName("analysis.pointer.analyses." + className);
                    } catch (ClassNotFoundException e1) {
                        System.err.println("HeapAbstractionFactory class not found: " + className);
                        System.err.println("\tin: " + value);
                        System.err.println(heapAbstractionUsage());
                        throw new ParameterException(e1);
                    }
                }
                Class<?>[] intClasses = new Class[args.size()];
                int j = 0;
                for (@SuppressWarnings("unused")
                Integer i : args) {
                    intClasses[j] = int.class;
                    j++;
                }

                Constructor<?> cons;
                try {
                    cons = c.getConstructor(intClasses);
                } catch (NoSuchMethodException | SecurityException e) {
                    System.err.println("HeapAbstractionFactory constructor not found: " + className + " with "
                                                    + args.size() + " int arguments");
                    System.err.println("\tin: " + value);
                    System.err.println(heapAbstractionUsage());
                    throw new ParameterException(e);
                }

                try {
                    children.add((HeapAbstractionFactory) cons.newInstance(args.toArray()));
                } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                                                | InvocationTargetException e) {
                    System.err.println("Could not invoke HeapAbstractionFactory constructor: " + className + " with "
                                                    + args.size() + " int arguments");
                    System.err.println("Arguments were: " + args);
                    System.err.println("\tin: " + value);
                    System.err.println(heapAbstractionUsage());
                    throw new ParameterException(e);
                }
            }

            if (children.size() == 0) {
                System.err.println("Parser parsed no HeapAbstractionFactory for: " + value);
                System.err.println(heapAbstractionUsage());
                throw new ParameterException("Parser parsed no HeapAbstractionFactory for: " + value);
            }

            HeapAbstractionFactory hf = null;
            for (HeapAbstractionFactory haf : children) {
                if (hf == null) {
                    hf = haf;
                } else {
                    hf = new CrossProduct(hf, haf);
                }
            }
            setHaf(hf);
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

    public Integer getOutputLevel() {
        return outputLevel;
    }

    public Integer getFileLevel() {
        return fileLevel;
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

    void setHaf(HeapAbstractionFactory haf) {
        this.haf = haf;
    }

    public HeapAbstractionFactory getHaf() {
        if (haf == null) {
            // No HeapAbstractionFactory was specified use the default
            TypeSensitive ts = new TypeSensitive();
            StaticCallSiteSensitive scs = new StaticCallSiteSensitive();
            setHaf(new CrossProduct(ts, scs));
        }
        return haf;
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

}
