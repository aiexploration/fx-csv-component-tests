package com.fx.csvtest.report;

import com.fx.csvtest.model.TestResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates a self-contained HTML report for a single test run and keeps the
 * shared index ({@code component-test-reports.html}) up to date.
 *
 * <p>Each run produces:
 * <ul>
 *   <li>{@code component-test-report-<yyyyMMdd_HHmmss>.html} — full run report</li>
 *   <li>{@code component-test-reports.html} — index listing all runs, latest first</li>
 *   <li>{@code xml/<runId>_<testId>.xml} — raw XML payloads linked from the report</li>
 * </ul>
 */
@Slf4j
public class HtmlReportGenerator {

    private static final DateTimeFormatter RUN_ID_FMT  = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static Path write(List<TestResult> results, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        LocalDateTime now   = LocalDateTime.now();
        String runId        = now.format(RUN_ID_FMT);
        String runLabel     = now.format(DISPLAY_FMT);

        // Save individual XML payloads so they can be opened in a browser
        Path xmlDir = outputDir.resolve("xml");
        Files.createDirectories(xmlDir);
        writeXmlFiles(results, xmlDir, runId);

        // Write the per-run report
        Path out = outputDir.resolve("component-test-report-" + runId + ".html");
        Files.writeString(out, buildPage(results, runId, runLabel), StandardCharsets.UTF_8);
        log.info("HTML report written (runId={}): {}", runId, out.toAbsolutePath());

        // Update the shared index
        updateIndex(results, outputDir, runId, runLabel);

        return out;
    }

    // ── XML files ─────────────────────────────────────────────────────────────

    private static void writeXmlFiles(List<TestResult> results, Path xmlDir, String runId)
            throws IOException {
        for (TestResult r : results) {
            String xml = r.getReceivedDomainXml() != null ? r.getReceivedDomainXml()
                       : r.getSentXml()           != null ? r.getSentXml() : null;
            if (xml == null || xml.isBlank()) continue;
            String filename = runId + "_" + sanitizeId(r.getTestId()) + ".xml";
            Files.writeString(xmlDir.resolve(filename), xml, StandardCharsets.UTF_8);
        }
    }

    // ── Page builder ──────────────────────────────────────────────────────────

    private static String buildPage(List<TestResult> results, String runId, String runLabel) {
        long total   = results.size();
        long passed  = results.stream().filter(TestResult::isPassed).count();
        long failed  = results.stream().filter(r -> r.getStatus() == TestResult.Status.FAIL).count();
        long errored = results.stream().filter(r -> r.getStatus() == TestResult.Status.ERROR).count();
        long totalMs = results.stream().mapToLong(TestResult::getDurationMs).sum();
        double passRate = total > 0 ? passed * 100.0 / total : 0;
        boolean allOk   = failed + errored == 0;

        Map<String, List<TestResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getCategory() == null ? "General" : r.getCategory(),
                        LinkedHashMap::new, Collectors.toList()));

        StringBuilder html = new StringBuilder();

        // ── Head & CSS ────────────────────────────────────────────────────────
        html.append("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FX Component Test Report &mdash; """).append(esc(runId)).append("""
</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f4f8;color:#1a202c;padding:1.5rem}
h1{font-size:1.7rem;font-weight:700;color:#2d3748}
.subtitle{color:#718096;font-size:.85rem;margin:.3rem 0 1.5rem}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:1rem;margin-bottom:1.5rem}
.card{background:#fff;border-radius:10px;padding:1.1rem;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.1)}
.num{font-size:2rem;font-weight:700}.label{font-size:.72rem;color:#718096;text-transform:uppercase;letter-spacing:.06em;margin-top:.2rem}
.pass{color:#38a169}.fail{color:#e53e3e}.error{color:#dd6b20}.info{color:#3182ce}
.progress{height:10px;background:#e2e8f0;border-radius:5px;margin-bottom:2rem;overflow:hidden}
.fill{height:100%;border-radius:5px}.fill-pass{background:#38a169}.fill-fail{background:#e53e3e}
section{margin-bottom:1.5rem}
.sec-head{display:flex;align-items:center;justify-content:space-between;background:#fff;border-radius:8px 8px 0 0;padding:.7rem 1rem;border-bottom:2px solid #edf2f7}
.sec-title{font-weight:600;font-size:.9rem}
.badges{display:flex;gap:.4rem}
.badge{padding:.12rem .45rem;border-radius:10px;font-size:.72rem;font-weight:700}
.bp{background:#c6f6d5;color:#22543d}.bf{background:#fed7d7;color:#822727}.be{background:#feebc8;color:#7b341e}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:0 0 8px 8px;font-size:.8rem}
th{background:#edf2f7;padding:.55rem .7rem;text-align:left;font-weight:600;color:#4a5568;text-transform:uppercase;font-size:.7rem;letter-spacing:.04em}
td{padding:.5rem .7rem;border-bottom:1px solid #f7fafc;vertical-align:top}
tr:hover td{background:#fafafa}
.sp{color:#38a169;font-weight:700}.sf{color:#e53e3e;font-weight:700}.se{color:#dd6b20;font-weight:700}
.pill{padding:.1rem .4rem;border-radius:4px;font-size:.7rem;font-weight:600}
.pv{background:#ebf8ff;color:#2b6cb0}.pi{background:#fff5f5;color:#c53030}
.mono{font-family:monospace;font-size:.72rem;color:#e53e3e;white-space:pre-wrap;word-break:break-all;max-width:600px}
.uuid{font-size:.68rem;color:#718096;font-family:monospace}
.xml-link{color:#3182ce;font-size:.72rem;font-weight:600;text-decoration:none;white-space:nowrap;
  border:1px solid #bee3f8;background:#ebf8ff;padding:.15rem .4rem;border-radius:4px}
.xml-link:hover{background:#bee3f8}
footer{text-align:center;color:#a0aec0;font-size:.75rem;margin-top:2rem}
</style>
</head>
<body>
""");

        // ── Header ────────────────────────────────────────────────────────────
        html.append("<h1>FX Payment Processor &mdash; CSV Component Test Report</h1>\n");
        html.append("<p class=\"subtitle\">Run ID: <code>").append(esc(runId)).append("</code>")
                .append(" &nbsp;|&nbsp; ").append(esc(runLabel))
                .append(" &nbsp;|&nbsp; Duration: ").append(fmtMs(totalMs))
                .append(" &nbsp;|&nbsp; Total: ").append(total).append("</p>\n");

        // ── Summary cards ─────────────────────────────────────────────────────
        html.append("<div class=\"grid\">\n");
        html.append(card("num pass",  passed,  "Passed"));
        html.append(card("num fail",  failed,  "Failed"));
        html.append(card("num error", errored, "Errors"));
        html.append(card("num info",  total,   "Total"));
        html.append(String.format(
                "<div class=\"card\"><div class=\"num %s\">%.1f%%</div><div class=\"label\">Pass Rate</div></div>%n",
                allOk ? "pass" : "fail", passRate));
        html.append(String.format(
                "<div class=\"card\"><div class=\"num info\">%s</div><div class=\"label\">Duration</div></div>%n",
                fmtMs(totalMs)));
        html.append("</div>\n");

        // ── Progress bar ──────────────────────────────────────────────────────
        int fill = (int) Math.round(passRate);
        html.append("<div class=\"progress\"><div class=\"fill ")
                .append(allOk ? "fill-pass" : "fill-fail")
                .append("\" style=\"width:").append(fill).append("%\"></div></div>\n");

        // ── Per-category tables ───────────────────────────────────────────────
        int rowIdx = 0;
        for (Map.Entry<String, List<TestResult>> entry : byCategory.entrySet()) {
            List<TestResult> rows = entry.getValue();
            long cp = rows.stream().filter(TestResult::isPassed).count();
            long cf = rows.stream().filter(r -> r.getStatus() == TestResult.Status.FAIL).count();
            long ce = rows.stream().filter(r -> r.getStatus() == TestResult.Status.ERROR).count();

            html.append("<section>\n<div class=\"sec-head\">\n");
            html.append("  <span class=\"sec-title\">").append(esc(entry.getKey())).append("</span>\n");
            html.append("  <span class=\"badges\">");
            if (cp > 0) html.append("<span class=\"badge bp\">&#10003; ").append(cp).append("</span>");
            if (cf > 0) html.append("<span class=\"badge bf\">&#10007; ").append(cf).append("</span>");
            if (ce > 0) html.append("<span class=\"badge be\">&#9888; ").append(ce).append("</span>");
            html.append("</span>\n</div>\n");

            html.append("<table><thead><tr>");
            for (String h : List.of("#", "Test ID", "Description", "Expected", "Status",
                    "DB Status", "UUID", "Failures", "Duration", "XML"))
                html.append("<th>").append(h).append("</th>");
            html.append("</tr></thead><tbody>\n");

            for (TestResult r : rows) {
                rowIdx++;
                String statusCss = switch (r.getStatus()) {
                    case PASS  -> "sp"; case FAIL -> "sf"; default -> "se";
                };
                String statusTxt = switch (r.getStatus()) {
                    case PASS  -> "&#10003; PASS"; case FAIL -> "&#10007; FAIL";
                    case ERROR -> "&#9888; ERROR";  default   -> "&#8856; SKIP";
                };
                String outcomePill = r.getExpectedOutcome() != null
                        ? ("VALID".equals(r.getExpectedOutcome().name())
                                ? "<span class=\"pill pv\">VALID</span>"
                                : "<span class=\"pill pi\">INVALID</span>")
                        : "";

                html.append("<tr>");
                html.append("<td>").append(rowIdx).append("</td>");
                html.append("<td>").append(esc(r.getTestId())).append("</td>");
                html.append("<td>").append(esc(r.getDescription())).append("</td>");
                html.append("<td>").append(outcomePill).append("</td>");
                html.append("<td class=\"").append(statusCss).append("\">").append(statusTxt).append("</td>");
                html.append("<td>").append(r.getPersistedStatus() != null ? esc(r.getPersistedStatus()) : "&mdash;").append("</td>");
                html.append("<td class=\"uuid\">")
                        .append(r.getPersistedPaymentId() != null
                                ? esc(r.getPersistedPaymentId().substring(
                                        0, Math.min(8, r.getPersistedPaymentId().length()))) + "&hellip;"
                                : "&mdash;")
                        .append("</td>");

                // Failures
                html.append("<td>");
                if (!r.getAssertionFailures().isEmpty()) {
                    html.append("<div class=\"mono\">");
                    r.getAssertionFailures().forEach(f -> html.append(esc(f)).append("\n"));
                    html.append("</div>");
                } else if (r.getErrorMessage() != null) {
                    html.append("<span class=\"mono\">").append(esc(trunc(r.getErrorMessage(), 200))).append("</span>");
                } else {
                    html.append("&mdash;");
                }
                html.append("</td>");

                html.append("<td>").append(fmtMs(r.getDurationMs())).append("</td>");

                // XML hyperlink — opens saved file in new browser tab
                html.append("<td>");
                String xml = r.getReceivedDomainXml() != null ? r.getReceivedDomainXml()
                           : r.getSentXml()           != null ? r.getSentXml() : null;
                if (xml != null && !xml.isBlank()) {
                    String xmlHref = "xml/" + runId + "_" + sanitizeId(r.getTestId()) + ".xml";
                    html.append("<a class=\"xml-link\" href=\"").append(esc(xmlHref))
                            .append("\" target=\"_blank\">view &#8599;</a>");
                } else {
                    html.append("&mdash;");
                }
                html.append("</td></tr>\n");
            }
            html.append("</tbody></table></section>\n");
        }

        html.append("<footer>FX CSV Component Tests &mdash; fx-payment-processor</footer>\n");
        html.append("</body></html>\n");
        return html.toString();
    }

    // ── Index (component-test-reports.html) ──────────────────────────────────
    // Rebuilt fresh every run by scanning the folder — no markers, no append logic needed
    // because the report folder lives outside target/ and survives mvn clean.

    private static void updateIndex(List<TestResult> results, Path outputDir,
                                    String runId, String runLabel) throws IOException {
        long total   = results.size();
        long passed  = results.stream().filter(TestResult::isPassed).count();
        long failed  = results.stream().filter(r -> r.getStatus() == TestResult.Status.FAIL).count();
        long errored = results.stream().filter(r -> r.getStatus() == TestResult.Status.ERROR).count();
        long totalMs = results.stream().mapToLong(TestResult::getDurationMs).sum();
        double rate  = total > 0 ? passed * 100.0 / total : 0;

        // Scan folder for all per-run report files, sorted newest first
        List<Path> reportFiles;
        try (Stream<Path> files = Files.list(outputDir)) {
            reportFiles = files
                    .filter(p -> p.getFileName().toString().matches("component-test-report-\\d{8}_\\d{6}\\.html"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        }

        StringBuilder rows = new StringBuilder();
        for (Path file : reportFiles) {
            String fname  = file.getFileName().toString();
            String fRunId = fname.replace("component-test-report-", "").replace(".html", "");
            // Derive a display timestamp from the runId (yyyyMMdd_HHmmss)
            String ts = fRunId.length() == 15
                    ? fRunId.substring(0, 4) + "-" + fRunId.substring(4, 6) + "-" + fRunId.substring(6, 8)
                      + " " + fRunId.substring(9, 11) + ":" + fRunId.substring(11, 13) + ":" + fRunId.substring(13, 15)
                    : fRunId;
            boolean isCurrent = fRunId.equals(runId);
            String rowCss = isCurrent ? (failed + errored == 0 ? "row-pass" : "row-fail") : "";

            rows.append(String.format("""
                    <tr class="%s">
                      <td><code>%s</code>%s</td>
                      <td>%s</td>
                      <td class="num-cell">%s</td>
                      <td class="num-cell pass">%s</td>
                      <td class="num-cell %s">%s</td>
                      <td class="num-cell %s">%s</td>
                      <td class="num-cell">%s</td>
                      <td><a class="open-link" href="%s" target="_blank">open &#8599;</a></td>
                    </tr>%n""",
                    rowCss,
                    esc(fRunId),
                    isCurrent ? " <span class=\"badge-new\">latest</span>" : "",
                    esc(ts),
                    isCurrent ? total    : "&mdash;",
                    isCurrent ? passed   : "&mdash;",
                    isCurrent && failed + errored > 0 ? "fail" : "pass",
                    isCurrent ? failed + errored : "&mdash;",
                    isCurrent ? (failed + errored == 0 ? "pass" : "fail") : "",
                    isCurrent ? String.format("%.1f%%", rate) : "&mdash;",
                    isCurrent ? fmtMs(totalMs) : "&mdash;",
                    esc(fname)));
        }

        String page = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FX Component Test Runs</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f4f8;color:#1a202c;padding:1.5rem}
h1{font-size:1.6rem;font-weight:700;color:#2d3748;margin-bottom:.3rem}
.subtitle{color:#718096;font-size:.85rem;margin-bottom:1.5rem}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;
  box-shadow:0 1px 4px rgba(0,0,0,.12);overflow:hidden;font-size:.82rem}
th{background:#2d3748;color:#e2e8f0;padding:.65rem .9rem;text-align:left;font-size:.72rem;
  text-transform:uppercase;letter-spacing:.05em;font-weight:600}
td{padding:.6rem .9rem;border-bottom:1px solid #edf2f7;vertical-align:middle}
tr:last-child td{border-bottom:none}
tr.row-pass:hover td{background:#f0fff4}
tr.row-fail:hover td{background:#fff5f5}
.num-cell{text-align:center}
.pass{color:#38a169;font-weight:700}.fail{color:#e53e3e;font-weight:700}
code{font-family:monospace;background:#edf2f7;padding:.1rem .35rem;border-radius:3px;font-size:.8rem}
.badge-new{background:#c6f6d5;color:#22543d;font-size:.65rem;font-weight:700;
  padding:.1rem .35rem;border-radius:8px;margin-left:.4rem;vertical-align:middle}
.open-link{display:inline-block;color:#3182ce;font-weight:600;text-decoration:none;
  border:1px solid #bee3f8;background:#ebf8ff;padding:.2rem .55rem;border-radius:5px;font-size:.78rem}
.open-link:hover{background:#bee3f8}
footer{text-align:center;color:#a0aec0;font-size:.75rem;margin-top:1.5rem}
</style>
</head>
<body>
<h1>FX Payment Processor &mdash; Component Test Runs</h1>
<p class="subtitle">All runs listed below &mdash; latest first. Click <em>open</em> to view the full report.</p>
<table>
<thead>
<tr>
  <th>Run ID</th><th>Timestamp</th><th>Total</th><th>Passed</th>
  <th>Failed / Errors</th><th>Pass Rate</th><th>Duration</th><th>Report</th>
</tr>
</thead>
<tbody>
""" + rows + """
</tbody>
</table>
<footer>FX CSV Component Tests &mdash; fx-payment-processor</footer>
</body></html>
""";

        Path indexFile = outputDir.resolve("component-test-reports.html");
        Files.writeString(indexFile, page, StandardCharsets.UTF_8);
        log.info("Run index written ({} runs): {}", reportFiles.size(), indexFile.toAbsolutePath());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String card(String css, long n, String label) {
        return String.format(
                "<div class=\"card\"><div class=\"%s\">%d</div><div class=\"label\">%s</div></div>%n",
                css, n, label);
    }

    private static String fmtMs(long ms) {
        return ms < 1000 ? ms + "ms" : String.format("%.2fs", ms / 1000.0);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String trunc(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String sanitizeId(String id) {
        return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
