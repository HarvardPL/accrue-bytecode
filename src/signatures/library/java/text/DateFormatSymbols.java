package signatures.library.java.text;

import java.lang.ref.SoftReference;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DateFormatSymbols {
    static final String patternChars = "GyMdkHmsSEDFwWahKzZYuX";

    static final int PATTERN_ERA = 0; // G
    static final int PATTERN_YEAR = 1; // y
    static final int PATTERN_MONTH = 2; // M
    static final int PATTERN_DAY_OF_MONTH = 3; // d
    static final int PATTERN_HOUR_OF_DAY1 = 4; // k
    static final int PATTERN_HOUR_OF_DAY0 = 5; // H
    static final int PATTERN_MINUTE = 6; // m
    static final int PATTERN_SECOND = 7; // s
    static final int PATTERN_MILLISECOND = 8; // S
    static final int PATTERN_DAY_OF_WEEK = 9; // E
    static final int PATTERN_DAY_OF_YEAR = 10; // D
    static final int PATTERN_DAY_OF_WEEK_IN_MONTH = 11; // F
    static final int PATTERN_WEEK_OF_YEAR = 12; // w
    static final int PATTERN_WEEK_OF_MONTH = 13; // W
    static final int PATTERN_AM_PM = 14; // a
    static final int PATTERN_HOUR1 = 15; // h
    static final int PATTERN_HOUR0 = 16; // K
    static final int PATTERN_ZONE_NAME = 17; // z
    static final int PATTERN_ZONE_VALUE = 18; // Z
    static final int PATTERN_WEEK_YEAR = 19; // Y
    static final int PATTERN_ISO_DAY_OF_WEEK = 20; // u
    static final int PATTERN_ISO_ZONE = 21; // X

    /* use serialVersionUID from JDK 1.1.4 for interoperability */
    static final long serialVersionUID = -5987973545549424702L;

    /**
     * Useful constant for defining time zone offsets.
     */
    static final int millisPerHour = 60 * 60 * 1000;

    /**
     * Cache to hold DateFormatSymbols instances per Locale.
     */
    private static final ConcurrentMap<Locale, SoftReference<DateFormatSymbols>> cachedInstances = new ConcurrentHashMap<Locale, SoftReference<DateFormatSymbols>>(3);

    static final DateFormatSymbols getInstanceRef(Locale locale) {
        return getCachedInstance(locale);
    }

    private static DateFormatSymbols getCachedInstance(Locale locale) {
        SoftReference<DateFormatSymbols> ref = cachedInstances.get(locale);
        DateFormatSymbols dfs = null;
        if (ref == null || (dfs = ref.get()) == null) {
            dfs = new DateFormatSymbols();
            ref = new SoftReference<DateFormatSymbols>(dfs);
            SoftReference<DateFormatSymbols> x = cachedInstances.putIfAbsent(locale, ref);
            if (x != null) {
                DateFormatSymbols y = x.get();
                if (y != null) {
                    dfs = y;
                }
                else {
                    // Replace the empty SoftReference with ref.
                    cachedInstances.put(locale, ref);
                }
            }
        }
        return dfs;
    }
}
