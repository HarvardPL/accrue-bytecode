package signatures.library.java.text;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

public abstract class SimpleDateFormat extends DateFormat {

    private void initializeCalendar(Locale loc) {
        if (calendar == null) {
            assert loc != null;
            // The format object must be constructed using the symbols for this zone.
            // However, the calendar should use the current default TimeZone.
            // If this is not contained in the locale zone strings, then the zone
            // will be formatted using generic GMT+/-H:MM nomenclature.
            calendar = new GregorianCalendar(loc);
        }
    }

    private void initialize(Locale loc) {
        numberFormat = new DecimalFormat("foo");
    }

}
