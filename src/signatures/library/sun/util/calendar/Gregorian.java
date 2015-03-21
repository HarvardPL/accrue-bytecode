package signatures.library.sun.util.calendar;

import java.util.TimeZone;

import sun.util.calendar.CalendarDate;

@SuppressWarnings("restriction")
public class Gregorian extends sun.util.calendar.BaseCalendar {
    public Gregorian() {
    }

    @Override
    public String getName() {
        return "gregorian";
    }

    @Override
    public CalendarDate newCalendarDate() {
        return new Date();
    }

    @Override
    public CalendarDate newCalendarDate(TimeZone zone) {
        return new Date(zone);
    }

    static class Date extends sun.util.calendar.BaseCalendar.Date {
        protected Date() {
            super();
        }

        protected Date(TimeZone zone) {
            super(zone);
        }

        @Override
        public int getNormalizedYear() {
            return getYear();
        }

        @Override
        public void setNormalizedYear(int normalizedYear) {
            setYear(normalizedYear);
        }
    }
}
