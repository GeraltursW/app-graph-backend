package com.geraltursw.appgraph.testcase;
import com.geraltursw.appgraph.report.CoverageReportService;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class CoveragePeriodTests {
    @Test void morningAndEveningMeetAtEightWithoutOverlap() {
        var day=LocalDate.of(2026,9,7);
        var morning=CoverageReportService.period(day,"MORNING");
        var evening=CoverageReportService.period(day,"EVENING");
        assertEquals(Instant.parse("2026-09-06T12:00:00Z"),morning[0]);
        assertEquals(morning[1],evening[0]);assertEquals(Duration.ofHours(12),Duration.between(morning[0],morning[1]));
        assertThrows(IllegalArgumentException.class,()->CoverageReportService.period(day,"INVALID"));
    }
}
