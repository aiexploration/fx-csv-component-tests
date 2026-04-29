package com.fx.csvtest.report;

import com.fx.csvtest.model.TestResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a self-contained HTML test report from the list of {@link TestResult}s.
 */
@Slf4j
public class HtmlReportGenerator {

    public static Path write(List<TestResult> results, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("component-test-report.html");

        long total   = results.size();
        long passed  = results.stream().filter(TestResult::isPassed).count();
        long failed  = results.stream().filter(r -> r.getStatus() == TestResult.Status.FAIL).count();
        long errored = results.stream().filter(r -> r.getStatus() == TestResult.Status.ERROR).count();
        long totalMs = results.stream().mapToLong(TestResult::getDurationMs).sum();
        double passRate = total > 0 ? passed * 100.0 / total : 0;
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Group by category for per-section tables
        Map<String, List<TestResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(r -> r.getCategory() == null ? "General" : r.getCategory(),
                        LinkedHashMap::new, Collectors.toList()));

        StringBuilder html = new StringBuilder();
        html.append("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FX CSV Component Test Report</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f0f4f8;color:#1a202c;padding:1.5rem}
h1{font-size:1.7rem;font-weight:700;color:#2d3748}
.subtitle{color:#718096;font-size:.85rem;margin:.3rem 0 1.5rem}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:1rem;margin-bottom:1.5rem}
.card{background:#fff;border-radius:10px;padding:1.1rem;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.1)}
.num{font-size:2rem;font-weight:700}.label{font-size:.72rem;color:#718096;text-transform:uppercase;letter-spacing:.06em;margin-top:.2rem}
.pass{color:#38a169}.fail{color:#e53e3e}.error{color:#dd6b20}.skip{color:#d69e2e}.info{color:#3182ce}
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
.xml-toggle{cursor:pointer;color:#3182ce;font-size:.72rem;text-decoration:underline;border:none;background:none;padding:0}
.xml-block{font-family:monospace;font-size:.68rem;background:#1a202c;color:#a0e2ff;padding:.6rem;border-radius:4px;
  white-space:pre-wrap;word-break:break-all;max-height:200px;overflow-y:auto;display:none;margin-top:.3rem}
.uuid{font-size:.68rem;color:#718096;font-family:monospace}
footer{text-align:center;color:#a0aec0;font-size:.75rem;margin-top:2rem}
</style>
</head>
<body>
""");

        html.append("<h1>FX Payment Processor — CSV Component Test Report</h1>\n");
        html.append("<p class=\"subtitle\">Generated: ").append(now)
                .append(" &nbsp;|&nbsp; Duration: ").append(fmtMs(totalMs))
                .append(" &nbsp;|&nbsp; Total tests: ").append(total).append("</p>\n");

        // Summary cards
        html.append("<div class=\"grid\">\n");
        html.append(card("num pass", passed,  "Passed"));
        html.append(card("num fail", failed,  "Failed"));
        html.append(card("num error",errored, "Errors"));
        html.append(card("num info", total,   "Total"));
        html.append(String.format("<div class=\"card\"><div class=\"num %s\">%.1f%%</div><div class=\"label\">Pass Rate</div></div>%n",
                failed + errored == 0 ? "pass" : "fail", passRate));
        html.append(String.format("<div class=\"card\"><div class=\"num info\">%s</div><div class=\"label\">Duration</div></div>%n",
                fmtMs(totalMs)));
        html.append("</div>\n");

        // Progress bar
        int fill = (int) Math.round(passRate);
        html.append("<div class=\"progress\"><div class=\"fill ")
                .append(failed + errored == 0 ? "fill-pass" : "fill-fail")
                .append("\" style=\"width:").append(fill).append("%\"></div></div>\n");

        // Per-category sections
        int rowIdx = 0;
        for (Map.Entry<String, List<TestResult>> entry : byCategory.entrySet()) {
            List<TestResult> rows = entry.getValue();
            long cp = rows.stream().filter(TestResult::isPassed).count();
            long cf = rows.stream().filter(r -> r.getStatus() == TestResult.Status.FAIL).count();
            long ce = rows.stream().filter(r -> r.getStatus() == TestResult.Status.ERROR).count();

            html.append("<section>\n<div class=\"sec-head\">\n");
            html.append("<span class=\"sec-title\">").append(esc(entry.getKey())).append("</span>\n");
            html.append("<span class=\"badges\">");
            if (cp > 0) html.append("<span class=\"badge bp\">✓ ").append(cp).append("</span>");
            if (cf > 0) html.append("<span class=\"badge bf\">✗ ").append(cf).append("</span>");
            if (ce > 0) html.append("<span class=\"badge be\">⚠ ").append(ce).append("</span>");
            html.append("</span></div>\n");

            html.append("<table><thead><tr>");
            for (String h : List.of("#","Test ID","Description","Expected","Status","DB Status","UUID","Failures","Duration","XML"))
                html.append("<th>").append(h).append("</th>");
            html.append("</tr></thead><tbody>\n");

            for (TestResult r : rows) {
                rowIdx++;
                String statusCss = switch (r.getStatus()) {
                    case PASS -> "sp"; case FAIL -> "sf"; default -> "se";
                };
                String statusTxt = switch (r.getStatus()) {
                    case PASS -> "✓ PASS"; case FAIL -> "✗ FAIL";
                    case ERROR -> "⚠ ERROR"; default -> "⊘ SKIP";
                };
                String outcomePill = r.getExpectedOutcome() != null
                        ? (r.getExpectedOutcome().name().equals("VALID")
                                ? "<span class=\"pill pv\">VALID</span>"
                                : "<span class=\"pill pi\">INVALID</span>") : "";

                html.append("<tr>");
                html.append("<td>").append(rowIdx).append("</td>");
                html.append("<td>").append(esc(r.getTestId())).append("</td>");
                html.append("<td>").append(esc(r.getDescription())).append("</td>");
                html.append("<td>").append(outcomePill).append("</td>");
                html.append("<td class=\"").append(statusCss).append("\">").append(statusTxt).append("</td>");
                html.append("<td>").append(r.getPersistedStatus() != null ? esc(r.getPersistedStatus()) : "—").append("</td>");
                html.append("<td class=\"uuid\">").append(r.getPersistedPaymentId() != null
                        ? esc(r.getPersistedPaymentId().substring(0, Math.min(8, r.getPersistedPaymentId().length()))) + "…" : "—")
                        .append("</td>");

                // Failures
                html.append("<td>");
                if (!r.getAssertionFailures().isEmpty()) {
                    html.append("<div class=\"mono\">");
                    for (String f : r.getAssertionFailures())
                        html.append(esc(f)).append("\n");
                    html.append("</div>");
                } else if (r.getErrorMessage() != null) {
                    html.append("<span class=\"mono\">").append(esc(trunc(r.getErrorMessage(), 200))).append("</span>");
                } else {
                    html.append("—");
                }
                html.append("</td>");

                html.append("<td>").append(fmtMs(r.getDurationMs())).append("</td>");

                // XML toggle
                String xmlId = "xml-" + rowIdx;
                String xml = r.getReceivedDomainXml() != null
                        ? r.getReceivedDomainXml()
                        : r.getSentXml() != null ? r.getSentXml() : "";
                html.append("<td>");
                if (!xml.isEmpty()) {
                    html.append("<button class=\"xml-toggle\" onclick=\"toggle('").append(xmlId).append("')\">view</button>");
                    html.append("<div class=\"xml-block\" id=\"").append(xmlId).append("\">")
                            .append(esc(trunc(xml, 1000))).append("</div>");
                } else {
                    html.append("—");
                }
                html.append("</td></tr>\n");
            }
            html.append("</tbody></table></section>\n");
        }

        html.append("""
<footer>FX CSV Component Tests — fx-payment-processor v1.0.0</footer>
<script>
function toggle(id){var el=document.getElementById(id);el.style.display=el.style.display==='block'?'none':'block';}
</script>
</body></html>
""");

        Files.writeString(out, html.toString());
        log.info("HTML report written: {}", out.toAbsolutePath());
        return out;
    }

    private static String card(String css, long n, String label) {
        return String.format("<div class=\"card\"><div class=\"%s\">%d</div><div class=\"label\">%s</div></div>%n", css, n, label);
    }

    private static String fmtMs(long ms) {
        return ms < 1000 ? ms + "ms" : String.format("%.2fs", ms / 1000.0);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private static String trunc(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
