package com.fx.csvtest.runner;

import com.fx.csvtest.csv.CsvTestCaseLoader;
import com.fx.csvtest.execution.TestOrchestrator;
import com.fx.csvtest.model.TestCase;
import com.fx.csvtest.model.TestResult;
import com.fx.csvtest.report.CsvReportGenerator;
import com.fx.csvtest.report.HtmlReportGenerator;
import com.fx.csvtest.xml.Pacs009XmlFactory;
import com.fx.payment.FxPaymentProcessorApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Main JUnit 5 test runner for the CSV-driven component test suite.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li>Starts the full {@link FxPaymentProcessorApplication} Spring context
 *       (embedded Artemis + H2).</li>
 *   <li>Loads all {@code *.csv} files from the configured
 *       {@code fx.component.test.test-data-dir}.</li>
 *   <li>For each test case: builds the pacs.009 XML → sends it → waits →
 *       asserts the output via {@link TestOrchestrator}.</li>
 *   <li>Writes an HTML and CSV report to {@code target/component-test-report/}.</li>
 *   <li>Fails the JUnit test if any test case fails, so CI pipelines are
 *       notified correctly.</li>
 * </ol>
 *
 * <h3>One-click execution</h3>
 * <pre>
 *   ./run-tests.sh          # installs AUT, runs suite, opens report
 * </pre>
 *
 * Or directly:
 * <pre>
 *   mvn test
 * </pre>
 */
@SpringBootTest(classes = FxPaymentProcessorApplication.class)
@ActiveProfiles("default")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("FX Payment Processor – CSV Component Test Suite")
@Slf4j
class CsvComponentTestRunner {

    @Autowired private CsvTestCaseLoader loader;
    @Autowired private Pacs009XmlFactory xmlFactory;
    @Autowired private TestOrchestrator orchestrator;

    @Value("${report.dir:target/component-test-report}")
    private String reportDir;

    @Value("${fx.component.test.test-data-dir:src/test/resources/test-data}")
    private String testDataDir;

    private static final List<TestResult> ALL_RESULTS = new ArrayList<>();
    private static List<TestCase> allTestCases;

    // ── Load CSV files once before any test runs ───────────────────────────

    @BeforeAll
    static void banner() {
        System.out.println("""
                ╔══════════════════════════════════════════════════════════╗
                ║      FX CSV Component Test Suite                         ║
                ║      ISO 20022 pacs.009 → Domain Payment Assertions      ║
                ╚══════════════════════════════════════════════════════════╝
                """);
    }

    @Test
    @Order(1)
    @DisplayName("Load test cases from CSV files")
    void loadTestCases() throws Exception {
        log.info("Loading test cases from: {}", testDataDir);
        allTestCases = loader.loadAll();
        assertThat(allTestCases)
                .as("At least one test case must be present in " + testDataDir)
                .isNotEmpty();
        log.info("Loaded {} test cases", allTestCases.size());
        printTestPlan(allTestCases);
    }

    @Test
    @Order(2)
    @DisplayName("Execute all CSV test cases")
    void executeAllTestCases() throws Exception {
        assertThat(allTestCases)
                .as("Test cases must be loaded first")
                .isNotNull().isNotEmpty();

        for (TestCase tc : allTestCases) {
            String xml = xmlFactory.build(tc);
            TestResult result = orchestrator.execute(tc, xml);
            ALL_RESULTS.add(result);
        }
    }

    @Test
    @Order(3)
    @DisplayName("Generate HTML and CSV reports")
    void generateReports() throws Exception {
        Path dir = Paths.get(reportDir);
        Path htmlPath = HtmlReportGenerator.write(ALL_RESULTS, dir);
        Path csvPath  = CsvReportGenerator.write(ALL_RESULTS, dir);

        long passed  = ALL_RESULTS.stream().filter(TestResult::isPassed).count();
        long failed  = ALL_RESULTS.stream().filter(TestResult::hasFailed).count();
        long total   = ALL_RESULTS.size();

        printSummary(passed, failed, total, htmlPath, csvPath);
    }

    @Test
    @Order(4)
    @DisplayName("Assert suite passed – no test case failures")
    void assertSuitePassed() {
        List<TestResult> failures = ALL_RESULTS.stream()
                .filter(TestResult::hasFailed)
                .toList();

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder("\n")
                    .append(failures.size()).append(" test case(s) FAILED:\n");
            for (TestResult r : failures) {
                sb.append("  ✗ [").append(r.getTestId()).append("] ").append(r.getDescription()).append("\n");
                r.getAssertionFailures().forEach(f -> sb.append("      → ").append(f).append("\n"));
                if (r.getErrorMessage() != null)
                    sb.append("      ERROR: ").append(r.getErrorMessage()).append("\n");
            }
            Assertions.fail(sb.toString());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void printTestPlan(List<TestCase> cases) {
        System.out.printf("%n%-10s %-30s %-20s %-10s%n", "TestId", "Description", "Category", "Expected");
        System.out.println("─".repeat(74));
        cases.forEach(tc -> System.out.printf("%-10s %-30s %-20s %-10s%n",
                tc.getTestId(),
                trunc(tc.getDescription(), 28),
                trunc(tc.getCategory(), 18),
                tc.getExpectedOutcome()));
        System.out.println();
    }

    private void printSummary(long passed, long failed, long total, Path html, Path csv) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.printf( "║  Test Results: %3d total  /  %3d passed  /  %3d failed   ║%n", total, passed, failed);
        System.out.printf( "║  Pass Rate: %.1f%%%44s║%n", total > 0 ? passed * 100.0 / total : 0.0, "");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  HTML Report → %-43s║%n", trunc(html.toString(), 43));
        System.out.printf( "║  CSV  Report → %-43s║%n", trunc(csv.toString(), 43));
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private String trunc(String s, int max) {
        return s != null && s.length() > max ? "…" + s.substring(s.length() - (max - 1)) : s;
    }
}
