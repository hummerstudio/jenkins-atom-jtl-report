package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The jmeter (jtl-report) atom, started the way the runner starts it.
 *
 * <p>What {@code tools/smoke.sh} checked: CSV and XML {@code .jtl} are both read, the percentiles are right against a
 * series whose answer is obvious, each budget fails with its own message, a breached gate still exports its numbers,
 * and a truncated file must fail by default instead of quietly shrinking the sample set the gate runs on.
 */
class JmeterIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** "timeStamp,elapsed,label,success" rows for 10 samples of 100…1000 ms, the last one failing. */
    private static String csvTenSamples() {
        StringBuilder csv = new StringBuilder("timeStamp,elapsed,label,responseCode,success\n");
        for (int i = 1; i <= 10; i++) {
            csv.append(1700000000000L + i).append(',').append(i * 100).append(",GET /jars,")
                    .append(i == 10 ? "500" : "200").append(',').append(i == 10 ? "false" : "true").append('\n');
        }
        return csv.toString();
    }

    private static String csv(int count, int stepMs) {
        StringBuilder csv = new StringBuilder("timeStamp,elapsed,label,success\n");
        for (int i = 1; i <= count; i++) {
            csv.append(1700000000000L + i).append(',').append(i * stepMs).append(",GET /jars,true\n");
        }
        return csv.toString();
    }

    @Test
    @DisplayName("CSV: samples, errors, the average and p90 against a series whose answer is obvious, and the report")
    void readsCsv() throws Exception {
        AtomResult run = atom.file("run.jtl", csvTenSamples())
                .input("maxErrorPercent", "0")
                .run();

        assertEquals("success", run.status(), run.console());
        assertEquals("10", run.field("samples"));
        assertEquals("1", run.field("errors"));
        assertEquals("550", run.field("avgMs"));
        assertEquals("900", run.field("p90Ms"));
        assertNotNull(run.file("out/jtl/index.html"), "no report");
    }

    @Test
    @DisplayName("an error rate over the limit fails the build and quotes both numbers")
    void errorRateLimit() throws Exception {
        AtomResult run = atom.file("x.jtl", "timeStamp,elapsed,label,success\n1,10,a,false\n2,20,a,false\n3,30,a,true\n")
                .input("maxErrorPercent", "10")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("the limit is 10.00%"),
                "message:\n" + run.file("output.json"));
    }

    @Test
    @DisplayName("the XML flavour is read too")
    void readsXml() throws Exception {
        AtomResult run = atom.file("run.jtl",
                        "<testResults><httpSample t=\"200\" s=\"true\" lb=\"GET /health\"/>"
                                + "<httpSample t=\"400\" s=\"false\" lb=\"GET /health\"/></testResults>\n")
                .input("{}")
                .run();

        run.assertOk();
        assertEquals("2", run.field("samples"));
        assertEquals("1", run.field("errors"));
    }

    @Test
    @DisplayName("no sample at all is a clear error")
    void refusesWithNoReport() throws Exception {
        AtomResult run = atom.input("{}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no .jtl file matched"),
                "message not readable:\n" + run.file("output.json"));
    }

    @Test
    @DisplayName("a p99 budget that is breached fails, and the numbers and the report still come out")
    void p99Budget() throws Exception {
        AtomResult run = atom.file("run.jtl", csv(100, 10))
                .input("maxP99Ms", "500")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("p99 is 990 ms, the limit is 500 ms"),
                "p99 message:\n" + run.file("output.json"));
        assertEquals("990", run.field("p99Ms"), "a breached gate must still export p99");
        assertEquals("100", run.field("samples"), "a breached gate must still export samples");
        assertNotNull(run.file("out/jtl/index.html"), "a breached gate must still write the report");
    }

    @Test
    @DisplayName("the average budget fails with its own message, and a budget that is met passes")
    void averageBudget() throws Exception {
        Atom twoSamples = atom.file("run.jtl", "timeStamp,elapsed,label,success\n1,100,a,true\n2,300,a,true\n");

        AtomResult breached = twoSamples.input("maxAverageMs", "100").run();
        breached.assertRefused();
        assertTrue(breached.file("output.json").contains("the average is 200 ms, the limit is 100 ms"),
                "average message:\n" + breached.file("output.json"));

        AtomResult met = twoSamples.input("maxAverageMs", "300")
                .input("maxP99Ms", "400")
                .input("maxErrorPercent", "5")
                .run();
        met.assertOk();
    }

    @Test
    @DisplayName("a truncated .jtl fails by default and names the file, allowUnreadableReports lets it through")
    void truncatedReport() throws Exception {
        Atom fixture = atom.file("good.jtl",
                        "timeStamp,elapsed,label,success\n1,100,GET /a,true\n2,200,GET /a,true\n")
                // jmeter was killed mid-write: the file ends inside a tag
                .file("half.jtl", "<?xml version=\"1.0\"?><testResults><httpSample t=\"100\" lt=\"100\" s=\"true\" "
                        + "lb=\"GET /a\"");

        AtomResult failed = fixture.input("pattern", "*.jtl").input("maxP99Ms", "5000").run();
        failed.assertRefused();
        assertTrue(failed.file("output.json").contains("\"errorCode\":40003"),
                "expected errorCode 40003:\n" + failed.file("output.json"));
        assertTrue(failed.file("output.json").contains("half.jtl"),
                "the failure must name the file:\n" + failed.file("output.json"));

        AtomResult allowed = fixture.input("pattern", "*.jtl")
                .input("maxP99Ms", "5000")
                .input("allowUnreadableReports", "true")
                .run();
        allowed.assertOk();
        assertEquals("2", allowed.field("samples"), "only the readable samples are counted");
    }
}
