package unit;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import junit.framework.TestCase;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import util.print.PrettyPrinter;
import analysis.AnalysisUtil;
import android.manifest.ManifestFile;
import brut.androlib.AndrolibException;
import brut.androlib.ApkDecoder;
import brut.androlib.res.data.ResPackage;
import brut.androlib.res.data.ResTable;
import brut.androlib.res.data.ResValuesFile;
import brut.androlib.res.decoder.ARSCDecoder;
import brut.androlib.res.decoder.ARSCDecoder.ARSCData;
import brut.androlib.res.decoder.ARSCDecoder.FlagsOffset;
import brut.androlib.res.decoder.AXmlResourceParser;
import brut.androlib.res.decoder.ResAttrDecoder;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.IClassHierarchy;

public class TestAndroid extends TestCase {

    String apkPath = "android/it.dancar.music.ligabue.apk";

    public void testOpenAPK() throws IOException {
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<?> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                System.err.println(entry.getName());
            }
        }
    }

    public void testOpenManifest() throws IOException {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            System.err.println("Found manifest");
            System.err.println("\t" + entry);
        }
    }

    public void testPrintManifest() throws XmlPullParserException, AndrolibException, IOException {
        ApkDecoder decoder = new ApkDecoder(new File(apkPath));
        ResTable table = decoder.getResTable();
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            AXmlResourceParser p = new AXmlResourceParser();
            ResAttrDecoder attrDecoder = new ResAttrDecoder();
            attrDecoder.setCurrentPackage(new ResPackage(table, 0, null));
            p.setAttrDecoder(attrDecoder);
            try (InputStream in = zip.getInputStream(entry)) {
                printManifest(p, in);
            }
        }
    }

    private static void printManifest(AXmlResourceParser p, InputStream in) throws XmlPullParserException, IOException {
        int element;
        String startElement = null;
        p.open(in);
        StringBuilder sb = new StringBuilder();
        while ((element = p.next()) != XmlPullParser.END_DOCUMENT) {
            switch (element) {
            case XmlPullParser.START_DOCUMENT:
                break;

            case XmlPullParser.START_TAG:
                char[] whitespace = new char[(p.getDepth() - 1) * 4];
                Arrays.fill(whitespace, ' ');
                sb.append(whitespace);
                sb.append("<");
                String tagNamespace = p.getNamespace();
                startElement = "";
                if (tagNamespace != null) {
                    startElement += tagNamespace.replace("http://schemas.android.com/apk/res/", "");
                    startElement += ":";
                }
                startElement += p.getName();
                sb.append(startElement);
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    sb.append(" ");
                    String attrNamespace = p.getAttributeNamespace(i);
                    if (!attrNamespace.isEmpty()) {
                        sb.append(attrNamespace.replace("http://schemas.android.com/apk/res/", ""));
                        sb.append(":");
                    }
                    sb.append(p.getAttributeName(i));
                    sb.append("=\"");
                    sb.append(p.getAttributeValue(i));
                    sb.append("\"");
                }
                sb.append(">\n");
                break;

            case XmlPullParser.END_TAG:
                tagNamespace = p.getNamespace();
                String endElement = "";
                if (tagNamespace != null) {
                    endElement += tagNamespace.replace("http://schemas.android.com/apk/res/", "");
                    endElement += ":";
                }
                endElement += p.getName();
                if (startElement != null && startElement.equals(endElement)) {
                    sb.deleteCharAt(sb.length() - 1);
                    sb.deleteCharAt(sb.length() - 1);
                    sb.append("/>\n");
                }
                else {
                    whitespace = new char[(p.getDepth() - 1) * 4];
                    Arrays.fill(whitespace, ' ');
                    sb.append(whitespace);
                    sb.append("</");
                    tagNamespace = p.getNamespace();
                    if (tagNamespace != null) {
                        sb.append(tagNamespace);
                        sb.append(":");
                    }
                    sb.append(p.getName());
                    sb.append(">\n");
                }
                break;

            // case XmlPullParser.CDSECT:
            // case XmlPullParser.COMMENT:
            // case XmlPullParser.DOCDECL:
            // case XmlPullParser.ENTITY_REF:
            // case XmlPullParser.IGNORABLE_WHITESPACE:
            // case XmlPullParser.PROCESSING_INSTRUCTION:
            case XmlPullParser.TEXT:
                System.err.println("Android docs do not contain tag " + element);
                break;

            case XmlPullParser.END_DOCUMENT:
                break;

            }
        }
        System.err.println(sb.toString());
    }

    public void testProcessManifest() {
        ManifestFile m = new ManifestFile(apkPath);
        System.err.println(m);
    }

    public void testClassHierarchy() throws ClassHierarchyException, IOException {
        AnalysisUtil.initDex("android/android-4.4.2_r1.jar", apkPath);
        IClassHierarchy cha = AnalysisUtil.getClassHierarchy();
        try (Writer out = new BufferedWriter(new FileWriter("tests/cha.txt"))) {
            for (IClass c : cha) {
                out.append(PrettyPrinter.typeString(c) + "\n");
            }
        }
    }

    public void testParseResourcesARSC() throws IOException, AndrolibException {
        ApkDecoder decoder = new ApkDecoder(new File(apkPath));
        ResTable table = decoder.getResTable();
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry("resources.arsc");
            try (InputStream in = zip.getInputStream(entry)) {
                ARSCData data = ARSCDecoder.decode(in, true, true, table);
                System.err.print("FLAGS: [");
                FlagsOffset[] flags = data.getFlagsOffsets();
                System.err.print(flagString(flags[0]));
                for (int i = 1; i < flags.length; i++) {
                    System.err.print(", " + flagString(flags[i]));
                }
                System.err.println("]");
                System.err.println("PACKAGES: " + Arrays.toString(data.getPackages()));
                System.err.println("RES TABLE:\n" + resTableString(data.getResTable()));
            }
        }
    }

    private static String flagString(FlagsOffset fo) {
        return new String("count: " + fo.count + " offset: " + fo.offset);
    }

    private static String resTableString(ResTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ANALYSIS MODE: " + table.getAnalysisMode() + "\n");
        try {
            sb.append("  CURRENT RES PACKAGE: " + table.getCurrentResPackage() + "\n");
            sb.append("  HIGHEST SPEC PACKAGE: " + table.getHighestSpecPackage() + "\n");
        }
        catch (AndrolibException e) {
            // intentionally blank
        }
        sb.append("  PACKAGE ID: " + table.getPackageId() + "\n");
        sb.append("  PACKAGE ORIGINAL: " + table.getPackageOriginal() + "\n");
        sb.append("  PACKAGE RENAMED: " + table.getPackageRenamed() + "\n");
        sb.append("  SDK INFO: " + table.getSdkInfo() + "\n");
        sb.append("  VERSION INFO: " + table.getVersionInfo() + "\n");
        sb.append("  FRAME PACKAGES:\n");
        for (ResPackage pkg : table.listFramePackages()) {
            sb.append("    NAME: " + pkg.getName() + "\n");
            sb.append("    CONFIGS: " + pkg.getConfigs() + "\n");
            sb.append("    ID: " + pkg.getId() + "\n");
            sb.append("    RES SPEC COUNT: " + pkg.getResSpecCount() + "\n");
            sb.append("    VALUE FACTORY: " + pkg.getValueFactory() + "\n");
            sb.append("    FILES: " + pkg.listFiles() + "\n");
            sb.append("    RES SPECS: " + pkg.listResSpecs() + "\n");
            sb.append("    TYPES: " + pkg.listTypes() + "\n");
            sb.append("    VALUE FILES:\n");
            for (ResValuesFile file : pkg.listValuesFiles()) {
                sb.append(resValuesFileString(file) + "\n");
            }
        }
        sb.append("  MAIN PACKAGES:\n");
        
        for (ResPackage pkg : table.listMainPackages()) {
            sb.append("    -------------------\n");
            sb.append("    NAME: " + pkg.getName() + "\n");
            sb.append("    CONFIGS: " + pkg.getConfigs() + "\n");
            sb.append("    ID: " + pkg.getId() + "\n");
            sb.append("    RES SPEC COUNT: " + pkg.getResSpecCount() + "\n");
            sb.append("    VALUE FACTORY: " + pkg.getValueFactory() + "\n");
            sb.append("    FILES: " + pkg.listFiles() + "\n");
            sb.append("    RES SPECS: " + pkg.listResSpecs() + "\n");
            sb.append("    TYPES: " + pkg.listTypes() + "\n");
            sb.append("    VALUE FILES:\n");
            for (ResValuesFile file : pkg.listValuesFiles()) {
                sb.append(resValuesFileString(file) + "\n");
            }
        }
        return sb.toString();
    }

    private static String resValuesFileString(ResValuesFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append("      -------------------\n");
        sb.append("      PATH: " + file.getPath() + "\n");
        sb.append("      CONFIG: " + file.getConfig() + "\n");
        sb.append("      TYPE: " + file.getType() + "\n");
        sb.append("      RESOURCES: " + file.listResources());
        return sb.toString();
    }

    public void testParseLayoutFiles() throws IOException, AndrolibException, XmlPullParserException {
        ApkDecoder decoder = new ApkDecoder(new File(apkPath));
        ResTable table = decoder.getResTable();
        ResAttrDecoder attrDecoder = new ResAttrDecoder();
        attrDecoder.setCurrentPackage(new ResPackage(table, 0, null));
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<?> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                if (entry.getName().startsWith("res/layout") && entry.getName().endsWith("xml")) {
                    // Note that there could be multiple layouts with the same name for different screen sizes
                    System.err.println(entry.getName());
                    try (InputStream in = zip.getInputStream(entry)) {
                        AXmlResourceParser p = new AXmlResourceParser();
                        p.setAttrDecoder(attrDecoder);
                        p.open(in);
                        int element;
                        while ((element = p.next()) != XmlPullParser.END_DOCUMENT) {
                            System.err.println("\t" + element + " " + p.getName());
                            for (int i = 0; i < p.getAttributeCount(); i++) {
                                System.err.println("\t\t" + p.getAttributeName(i) + "=" + p.getAttributeValue(i));
                            }
                        }
                    }
                }
            }
        }
    }
}
