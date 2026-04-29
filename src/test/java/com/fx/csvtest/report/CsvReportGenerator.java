package com.fx.csvtest.report;

import com.fx.csvtest.model.TestResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes a machine-readable CSV summary of all test results.
 * Suitable for CI integration and trend analysis.
 */
@Slf4j
public class CsvReportGenerator {

    public static Path write(List<TestResult> results, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("component-test-results.csv");

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out))) {
            // Header
            pw.println("testId,category,description,expectedOutcome,status,persistedStatus," +
                       "persistedPaymentId,durationMs,executedAt,assertionFailures,errorMessage");

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (TestResult r : results) {
                pw.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%d,\"%s\",\"%s\",\"%s\"%n",
                        safe(r.getTestId()),
                        safe(r.getCategory()),
                        safe(r.getDescription()),
                        r.getExpectedOutcome() != null ? r.getExpectedOutcome().name() : "",
                        r.getStatus() != null ? r.getStatus().name() : "",
                        safe(r.getPersistedStatus()),
                        safe(r.getPersistedPaymentId()),
                        r.getDurationMs(),
                        r.getExecutedAt() != null ? r.getExecutedAt().format(fmt) : "",
                        safe(String.join(" | ", r.getAssertionFailures())),
                        safe(r.getErrorMessage())
                );
            }
        }
        log.info("CSV report written: {}", out.toAbsolutePath());
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "\"\"").replace("\n", " ").replace("\r", "");
    }
}
