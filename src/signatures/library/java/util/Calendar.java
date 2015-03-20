package signatures.library.java.util;

import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

public class Calendar {
    private static java.util.Calendar createCalendar(TimeZone zone, Locale aLocale) {
        return new GregorianCalendar(zone, aLocale);
    }
}
