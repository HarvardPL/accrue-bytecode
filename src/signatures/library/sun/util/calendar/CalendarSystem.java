package signatures.library.sun.util.calendar;


public class CalendarSystem {
    @SuppressWarnings("restriction")
    static sun.util.calendar.CalendarSystem forName(java.lang.String name) {
        return new Gregorian();
    }
}
