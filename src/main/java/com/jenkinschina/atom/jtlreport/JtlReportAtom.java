package com.jenkinschina.atom.jtlreport;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * JMeter results as an atom: read the .jtl a run left behind and turn it into latency percentiles and an error rate.
 *
 * <p>Reads both the CSV and the XML flavour of the same file. No JMeter needed: the load test has already run, and a
 * report atom that wanted to run it again would be the wrong shape.
 */
public class JtlReportAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/*.jtl");
        String label = context.getParam("label", "Load test");
        String outDir = context.getParam("outputDir", "out/jtl");
        double maxErrorPercent = parse(context.getParam("maxErrorPercent", "0"), 0);
        double maxP99Ms = parse(context.getParam("maxP99Ms", "0"), 0);
        double maxAverageMs = parse(context.getParam("maxAverageMs", "0"), 0);

        List<Path> files = find(workspace, pattern);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no .jtl file matched " + pattern);
        }

        Stats stats = new Stats();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.stripLeading().startsWith("<")) {
                readXml(text, stats);
            } else {
                readCsv(text, stats);
            }
        }
        if (stats.samples == 0) {
            return AtomResult.error(ErrorType.USER, 40002, "the .jtl files contain no sample");
        }

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(label, pattern, files.size(), stats),
                StandardCharsets.UTF_8);

        double errorPercent = 100.0 * stats.errors / stats.samples;
        context.log(String.format(Locale.ROOT,
                "[jtl] %d samples, %d errors (%.2f%%), avg %.0f ms, p90 %.0f ms, p99 %.0f ms, max %.0f ms",
                stats.samples, stats.errors, errorPercent, stats.mean(), stats.percentile(90), stats.percentile(99),
                stats.max));

        // every number goes out either way: a breached gate should still leave the figures in the build
        List<String> breaches = new ArrayList<>();
        if (maxErrorPercent > 0 && errorPercent > maxErrorPercent) {
            breaches.add(String.format(Locale.ROOT, "%.2f%% of the samples failed, the limit is %.2f%%",
                    errorPercent, maxErrorPercent));
        }
        if (maxP99Ms > 0 && stats.percentile(99) > maxP99Ms) {
            breaches.add(String.format(Locale.ROOT, "p99 is %.0f ms, the limit is %.0f ms",
                    stats.percentile(99), maxP99Ms));
        }
        if (maxAverageMs > 0 && stats.mean() > maxAverageMs) {
            breaches.add(String.format(Locale.ROOT, "the average is %.0f ms, the limit is %.0f ms",
                    stats.mean(), maxAverageMs));
        }

        AtomResult result = breaches.isEmpty()
                ? AtomResult.success()
                : AtomResult.failure("the load test missed its budget: " + String.join("; ", breaches));
        result = result
                .data("samples", String.valueOf(stats.samples))
                .data("errors", String.valueOf(stats.errors))
                .data("errorPercent", String.format(Locale.ROOT, "%.2f", errorPercent))
                .data("avgMs", String.format(Locale.ROOT, "%.0f", stats.mean()))
                .data("p90Ms", String.format(Locale.ROOT, "%.0f", stats.percentile(90)))
                .data("p99Ms", String.format(Locale.ROOT, "%.0f", stats.percentile(99)))
                .data("maxMs", String.format(Locale.ROOT, "%.0f", stats.max))
                .report("report", label, dir.toString(), "index.html");
        for (String breach : breaches) {
            context.log("[jtl] budget missed: " + breach);
        }
        return result;
    }

    /** CSV: the header names the columns; label,elapsed,success (and friends) are the only ones used. */
    private static void readCsv(String text, Stats stats) {
        String[] lines = text.split("\n");
        if (lines.length < 2) {
            return;
        }
        List<String> header = splitCsv(lines[0]);
        int elapsed = header.indexOf("elapsed");
        int success = header.indexOf("success");
        int labelColumn = header.indexOf("label");
        if (elapsed < 0) {
            return;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> values = splitCsv(lines[i]);
            if (values.size() <= elapsed) {
                continue;
            }
            long millis = (long) parse(values.get(elapsed), 0);
            boolean ok = success < 0 || values.size() <= success || "true".equalsIgnoreCase(values.get(success));
            String name = labelColumn >= 0 && values.size() > labelColumn ? values.get(labelColumn) : "all";
            stats.add(name, millis, ok);
        }
    }

    private static void readXml(String text, Stats stats) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            NodeList samples = document.getElementsByTagName("httpSample");
            if (samples.getLength() == 0) {
                samples = document.getElementsByTagName("sample");
            }
            for (int i = 0; i < samples.getLength(); i++) {
                Element sample = (Element) samples.item(i);
                stats.add(sample.getAttribute("lb"), (long) parse(sample.getAttribute("t"), 0),
                        "true".equalsIgnoreCase(sample.getAttribute("s")));
            }
        } catch (Exception ignored) {
            // an unreadable file contributes nothing
        }
    }

    private static List<String> splitCsv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static List<Path> find(Path workspace, String pattern) throws java.io.IOException {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path)) || m.matches(java.nio.file.Path.of("_").resolve(workspace.relativize(path)))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(100)
                    .forEach(found::add);
        }
        return found;
    }

    private static String render(String label, String pattern, int files, Stats stats) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(label).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:110px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}")
                .append("th{background:#fafbfc;color:#6b7280}td.n{text-align:right;font-variant-numeric:tabular-nums}")
                .append("</style></head><body>");
        html.append("<h1>").append(label).append("</h1><p class=\"meta\">").append(files)
                .append(" file(s), ").append(stats.samples).append(" samples · <code>").append(pattern)
                .append("</code></p><div class=\"cards\">");
        card(html, "avg", String.format(Locale.ROOT, "%.0f ms", stats.mean()));
        card(html, "p90", String.format(Locale.ROOT, "%.0f ms", stats.percentile(90)));
        card(html, "p99", String.format(Locale.ROOT, "%.0f ms", stats.percentile(99)));
        card(html, "errors", String.format(Locale.ROOT, "%d (%.2f%%)", stats.errors,
                100.0 * stats.errors / Math.max(1, stats.samples)));
        html.append("</div><table><tr><th>Label</th><th class=\"n\">Samples</th><th class=\"n\">Errors</th>")
                .append("<th class=\"n\">Avg</th><th class=\"n\">p90</th></tr>");
        stats.byLabel.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Stats.Bucket> e) -> e.getValue().count).reversed())
                .forEach(entry -> {
                    Stats.Bucket bucket = entry.getValue();
                    html.append("<tr><td>").append(escape(entry.getKey()))
                            .append("</td><td class=\"n\">").append(bucket.count)
                            .append("</td><td class=\"n\">").append(bucket.errors)
                            .append("</td><td class=\"n\">").append(String.format(Locale.ROOT, "%.0f ms", bucket.mean()))
                            .append("</td><td class=\"n\">")
                            .append(String.format(Locale.ROOT, "%.0f ms", bucket.percentile(90))).append("</td></tr>");
                });
        return html.append("</table></body></html>").toString();
    }

    private static void card(StringBuilder html, String label, String value) {
        html.append("<div class=\"card\"><b>").append(value).append("</b><span>").append(label).append("</span></div>");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static double parse(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Per-label buckets so one slow endpoint is visible instead of hidden in the global average. */
    private static final class Stats {
        final Map<String, Bucket> byLabel = new LinkedHashMap<>();
        final List<Double> all = new ArrayList<>();
        long samples;
        long errors;
        double max;

        void add(String label, long millis, boolean ok) {
            samples++;
            if (!ok) {
                errors++;
            }
            max = Math.max(max, millis);
            all.add((double) millis);
            byLabel.computeIfAbsent(label == null || label.isBlank() ? "all" : label, key -> new Bucket())
                    .add(millis, ok);
        }

        double mean() {
            return all.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }

        double percentile(int percentile) {
            if (all.isEmpty()) {
                return 0;
            }
            List<Double> sorted = new ArrayList<>(all);
            sorted.sort(Double::compare);
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        }

        private static final class Bucket {
            long count;
            long errors;
            final List<Double> values = new ArrayList<>();

            void add(long millis, boolean ok) {
                count++;
                if (!ok) {
                    errors++;
                }
                values.add((double) millis);
            }

            double mean() {
                return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            }

            double percentile(int percentile) {
                if (values.isEmpty()) {
                    return 0;
                }
                List<Double> sorted = new ArrayList<>(values);
                sorted.sort(Double::compare);
                int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
                return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
            }
        }
    }
}
