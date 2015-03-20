package signatures.library.sun.util.calendar;


public class CalendarSystem {
    static sun.util.calendar.CalendarSystem forName(java.lang.String name) {
        return new Gregorian();
    }
}
