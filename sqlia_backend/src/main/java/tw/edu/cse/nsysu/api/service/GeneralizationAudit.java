package tw.edu.cse.nsysu.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import org.apache.commons.csv.*;
import tw.edu.cse.nsysu.preprocess.PayloadNormalizer;

/** CSV record audit. Originals are only opened for reading; no database is involved. */
final class GeneralizationAudit {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PROVENANCE_URL =
            "https://www.kaggle.com/datasets/gambleryu/biggest-sql-injection-dataset?resource=download/clean_sql_dataset.csv";
    static final String INDEPENDENCE = "overlap-filtered retrospective/independence-unverified";
    static final List<String> LIMITATIONS = List.of(
            "Uploader provenance supplied by user; independent collection and chronology are unverified.",
            "External data may share upstream sources with old corpora; exclusion cannot establish independence.",
            "Normalization and identifier/literal template grouping are deterministic heuristics, not semantic proof.",
            "Only binary benign/malicious labels exist; no inferred attack-family ground truth is reported.",
            "Label definitions are user assertions; semantically unusual examples are not silently removed and require manual label audit.",
            "Confidence intervals reflect heuristic template-cluster sampling, not distribution-shift uncertainty.");
    record Source(String name, Path path, Charset charset) {}
    record Row(String text, int label, String source, long record, List<String> fields,
            List<String> errors) {
        String normalized() { return PayloadNormalizer.groupKey(text); }
        String template() { return templateKey(text); }
    }
    private static final Pattern TOKEN = Pattern.compile(
            "'(?:''|\\\\.|[^'])*'|\"(?:\"\"|\\\\.|[^\"])*\"|`[^`]*`|\\[[^\\]]*\\]"
            + "|\\b0x[0-9a-f]+\\b|\\b\\d+(?:\\.\\d+)?\\b|\\b[a-z_$][a-z0-9_$]*\\b");
    private static final Set<String> KEYWORDS = Set.of(
            "select","from","where","and","or","not","null","is","in","like","union","all",
            "insert","into","values","update","set","delete","drop","table","create","alter",
            "join","left","right","inner","outer","on","as","case","when","then","else","end",
            "having","group","by","order","asc","desc","limit","offset","distinct","exists",
            "between","true","false","exec","execute","declare","waitfor","delay","sleep",
            "benchmark","pg_sleep","concat","cast","convert","char","substring","substr",
            "count","sum","min","max","avg","information_schema","database","version","user",
            "load_file","outfile","xp_cmdshell","if","extractvalue","updatexml");

    static String templateKey(String text) {
        String normalized = PayloadNormalizer.groupKey(text);
        Matcher matcher = TOKEN.matcher(normalized);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(), replacement;
            char first = token.charAt(0);
            if (first == '\'' || first == '"') replacement = "<str>";
            else if (Character.isDigit(first)) replacement = "<num>";
            else replacement = KEYWORDS.contains(token) ? token : "<id>";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static List<Source> oldSources(Path root) {
        return List.of(new Source("Modified_SQL_Dataset", root.resolve("data/raw/Modified_SQL_Dataset.csv"), StandardCharsets.UTF_8),
                new Source("sqli", root.resolve("data/raw/sqli.csv"), StandardCharsets.UTF_16),
                new Source("SQLiV3", root.resolve("data/raw/SQLiV3.csv"), StandardCharsets.UTF_8));
    }

    static List<Row> read(Source source) throws Exception {
        List<Row> result = new ArrayList<>();
        try (var reader = Files.newBufferedReader(source.path(), source.charset());
                var parser = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(false).get().parse(reader)) {
            Iterator<CSVRecord> records = parser.iterator();
            if (!records.hasNext()) throw new IllegalArgumentException("Empty CSV: " + source.path());
            CSVRecord header = records.next();
            List<String> names = new ArrayList<>();
            header.forEach(name -> names.add(name.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT)));
            if (new HashSet<>(names).size() != names.size())
                throw new IllegalArgumentException("Duplicate CSV headers: " + source.path());
            int textIndex = find(names, List.of("query","sentence","payload","sql","text"));
            int labelIndex = find(names, List.of("label"));
            if (textIndex < 0 || labelIndex < 0)
                throw new IllegalArgumentException("Required SQL/label headers missing: " + source.path());
            while (records.hasNext()) {
                CSVRecord record = records.next();
                List<String> fields = new ArrayList<>();
                record.forEach(fields::add);
                List<String> errors = new ArrayList<>();
                if (record.size() != header.size()) errors.add("width_error");
                String text = record.size() > textIndex ? record.get(textIndex) : "";
                String value = record.size() > labelIndex ? record.get(labelIndex).trim() : "";
                if (text.isBlank()) errors.add("blank_payload");
                int label = -1;
                if (value.isBlank()) errors.add("blank_label");
                else if (value.equals("0") || value.equals("1")) label = Integer.parseInt(value);
                else errors.add("invalid_label");
                result.add(new Row(text, label, source.name(), record.getRecordNumber(), fields, errors));
            }
        }
        return result;
    }

    private static int find(List<String> names, List<String> candidates) {
        for (String candidate : candidates) if (names.contains(candidate)) return names.indexOf(candidate);
        return -1;
    }

    static Path run(Path root, Path output) throws Exception {
        Files.createDirectories(output);
        Path reportPath = output.resolve("audit-report.json");
        Map<String, Object> report = reserve(reportPath, "sqlia-generalization-audit-v1");
        try {
            Map<String, Object> protocol = Map.of(
                    "qualityGate", "Complete strictly parsable CSV; structural/label errors quarantined with record-level reasons; "
                            + "malformed quoting fails the entire audit without guessing records. "
                            + "After exact dedup/conflict/old raw normalized+template exclusions, require both labels, "
                            + "at least one template group per class and at least two retained template groups overall.",
                    "lowPositiveGroupDisclosure", "At most 20 positive template groups triggers a limitation, not post-score exclusion.",
                    "oddSemanticRows", "Not removed on subjective semantic suspicion; manual label audit remains a limitation.",
                    "independence", "Always unverified absent independent collection evidence; never a blind badge.",
                    "externalLabelsNeverAffectOldTrainingExport", true);
            Files.write(output.resolve("audit-protocol.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(protocol), StandardOpenOption.CREATE_NEW);
            report.put("prespecifiedProtocol", protocol);
            report.put("auditProtocolSha256", sha(output.resolve("audit-protocol.json")));
            List<Source> sources = oldSources(root);
            Source external = new Source("clean_sql_dataset", root.resolve("data/raw/clean_sql_dataset.csv"), StandardCharsets.UTF_8);
            List<Row> old = new ArrayList<>();
            List<Map<String, Object>> oldFiles = new ArrayList<>();
            for (Source source : sources) {
                List<Row> rows = read(source); old.addAll(rows);
                oldFiles.add(sourceDescription(source, rows));
            }
            List<Row> rows = read(external);
            Set<String> exactConflicts = conflicts(old, rows, false);
            Set<String> normalizedConflicts = conflicts(old, rows, true);
            Set<String> oldExactConflicts = conflicts(old, List.of(), false);
            Set<String> oldNormalizedConflicts = conflicts(old, List.of(), true);
            Set<String> oldExact = new HashSet<>(), oldNormalized = new HashSet<>(), oldTemplates = new HashSet<>();
            for (Row row : old) if (!row.text().isBlank()) {
                oldExact.add(row.text()); oldNormalized.add(row.normalized()); oldTemplates.add(row.template());
            }
            Map<String, Integer> exclusions = new TreeMap<>(), oldExclusions = new TreeMap<>();
            Map<String, Row> retainedOld = new LinkedHashMap<>();
            Set<String> externalSeen = new HashSet<>();
            List<Row> retained = new ArrayList<>();
            try (CSVPrinter excluded = printer(output.resolve("exclusions.csv"),
                    "source","record","label","reasons","payload","originalFieldsJson");
                    CSVPrinter oldExcluded = printer(output.resolve("old-exclusions.csv"),
                            "source","record","label","reasons","payload","originalFieldsJson")) {
                for (Row row : old) {
                    List<String> reasons = reasons(row, oldExactConflicts, oldNormalizedConflicts);
                    if (reasons.isEmpty() && retainedOld.containsKey(row.text())) {
                        Row previous = retainedOld.get(row.text());
                        Set<String> provenance = new TreeSet<>(List.of(previous.source().split("\\|")));
                        provenance.add(row.source());
                        retainedOld.put(row.text(), new Row(previous.text(), previous.label(),
                                String.join("|", provenance), previous.record(), previous.fields(), List.of()));
                        reasons.add("duplicate_exact");
                    } else if (reasons.isEmpty()) retainedOld.put(row.text(), row);
                    recordExclusion(oldExcluded, row, reasons, oldExclusions);
                }
                for (Row row : rows) {
                    List<String> reasons = reasons(row, exactConflicts, normalizedConflicts);
                    if (!externalSeen.add(row.text())) reasons.add("duplicate_exact");
                    if (oldExact.contains(row.text())) reasons.add("old_exact_overlap");
                    if (oldNormalized.contains(row.normalized())) reasons.add("old_normalized_overlap");
                    if (oldTemplates.contains(row.template())) reasons.add("old_template_overlap");
                    if (reasons.isEmpty()) retained.add(row);
                    recordExclusion(excluded, row, reasons, exclusions);
                }
            }
            export(output.resolve("old-valid.csv"), new ArrayList<>(retainedOld.values()));
            export(output.resolve("external-valid.csv"), retained);
            report.put("oldSources", oldFiles);
            report.put("external", sourceDescription(external, rows));
            report.put("provenance", Map.of("userProvidedUrl", PROVENANCE_URL,
                    "userStatement", "Not old-data augmentation; overlap unknown",
                    "labelDefinitionsUserAsserted", Map.of("0", "benign", "1", "malicious"),
                    "publicPageMetadata", Map.of("title", "Biggest SQL Injection Dataset",
                            "creator", "Gambler yu", "version", 1,
                            "dateModified", "2023-05-10T05:33:22.36Z", "license", "Unknown",
                            "collectionMethodology", "Not independently verifiable from fetched page"),
                    "verifiedIndependentCollection", false, "classification", INDEPENDENCE));
            report.put("blindTestEligible", false);
            List<String> limitations = new ArrayList<>(LIMITATIONS);
            long positiveGroups = retained.stream().filter(row -> row.label() == 1).map(Row::template).distinct().count();
            long negativeGroups = retained.stream().filter(row -> row.label() == 0).map(Row::template).distinct().count();
            long templateGroups = retained.stream().map(Row::template).distinct().count();
            if (positiveGroups <= 20) limitations.add("Only " + positiveGroups
                    + " positive heuristic template groups remain; effective positive sample size is limited.");
            report.put("limitations", limitations);
            report.put("positiveTemplateGroups", positiveGroups);
            report.put("benignTemplateGroups", negativeGroups);
            report.put("retainedTemplateGroups", templateGroups);
            report.put("exclusionPolicy", "Prespecified structural errors, all exact/normalized label-conflict rows, "
                    + "exact deduplication, then old raw exact/normalized/template overlap; no scores used. "
                    + "Reason counts overlap; excludedRows is the unique removed-record count.");
            report.put("normalizationPolicy", "PayloadNormalizer.groupKey, URL decode, case/whitespace/punctuation normalization");
            report.put("templatePolicy", "v1: preserve SQL keyword/operator structure; replace identifiers, "
                    + "numeric and quoted-string literals; heuristic, not semantic equivalence");
            report.put("exactConflictGroupCount", exactConflicts.size());
            report.put("normalizedConflictGroupCount", normalizedConflicts.size());
            report.put("originalRows", rows.size());
            report.put("evaluatedRows", retained.size());
            report.put("benignCount", retained.stream().filter(row -> row.label() == 0).count());
            report.put("maliciousCount", retained.stream().filter(row -> row.label() == 1).count());
            report.put("excludedRows", rows.size() - retained.size());
            report.put("exclusionCounts", exclusions);
            report.put("oldExclusionCounts", oldExclusions);
            report.put("oldRetainedRows", retainedOld.size());
            Map<String, String> hashes = new LinkedHashMap<>();
            for (String file : List.of("exclusions.csv","old-exclusions.csv","old-valid.csv","external-valid.csv"))
                hashes.put(file, sha(output.resolve(file)));
            report.put("exportSha256", hashes);
            report.put("status", "complete");
            report.put("qualityGate", positiveGroups > 0 && negativeGroups > 0 && templateGroups >= 2
                    ? "pass" : "blocked_insufficient_external_labels_or_groups");
            finish(reportPath, report);
            return reportPath;
        } catch (Exception failure) {
            fail(reportPath, report, failure); throw failure;
        }
    }

    private static List<String> reasons(Row row, Set<String> exact, Set<String> normalized) {
        List<String> reasons = new ArrayList<>(row.errors());
        if (exact.contains(row.text())) reasons.add("conflicting_exact_labels");
        if (normalized.contains(row.normalized())) reasons.add("conflicting_normalized_labels");
        return reasons;
    }

    private static Set<String> conflicts(List<Row> old, List<Row> external, boolean normalized) {
        Map<String, Integer> labels = new HashMap<>();
        for (List<Row> rows : List.of(old, external)) for (Row row : rows)
            if (row.label() >= 0 && !row.text().isBlank())
                labels.merge(normalized ? row.normalized() : row.text(), 1 << row.label(), (a,b) -> a | b);
        Set<String> conflicts = new HashSet<>();
        labels.forEach((key, mask) -> { if (mask == 3) conflicts.add(key); });
        return conflicts;
    }

    private static Map<String, Object> sourceDescription(Source source, List<Row> rows) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source.name()); result.put("path", source.path().toString());
        result.put("fileSha256", sha(source.path())); result.put("charset", source.charset().name());
        result.put("records", rows.size());
        Map<String, Long> errors = new TreeMap<>();
        for (Row row : rows) for (String error : row.errors()) errors.merge(error, 1L, Long::sum);
        result.put("structuralErrors", errors); return result;
    }

    private static void recordExclusion(CSVPrinter printer, Row row, List<String> reasons,
            Map<String, Integer> counts) throws Exception {
        if (reasons.isEmpty()) return;
        for (String reason : reasons) counts.merge(reason, 1, Integer::sum);
        printer.printRecord(row.source(), row.record(), row.label(), String.join("|", reasons),
                row.text(), JSON.writeValueAsString(row.fields()));
    }

    static CSVPrinter printer(Path path, String... headers) throws Exception {
        return new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW),
                CSVFormat.DEFAULT.builder().setHeader(headers).get());
    }

    static void export(Path path, List<Row> rows) throws Exception {
        try (CSVPrinter printer = printer(path, "payload","label","source","record","template_group")) {
            for (Row row : rows) printer.printRecord(row.text(), row.label(), row.source(),
                    row.record(), sha(row.template().getBytes(StandardCharsets.UTF_8)));
        }
    }

    static String sha(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536]; int read;
            while ((read = stream.read(bytes)) >= 0) digest.update(bytes, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Map<String, Object> reserve(Path path, String schema) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", schema); report.put("status", "running");
        report.put("createdAt", Instant.now().toString());
        Files.write(path, JSON.writeValueAsBytes(report), StandardOpenOption.CREATE_NEW);
        return report;
    }
    static void finish(Path path, Map<String, Object> report) throws Exception {
        report.put("completedAt", Instant.now().toString());
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),
                StandardOpenOption.TRUNCATE_EXISTING);
    }
    static void fail(Path path, Map<String, Object> report, Exception failure) throws Exception {
        report.put("status", "failed");
        report.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        finish(path, report);
    }
    static Map<String, Object> object(Path path) throws Exception {
        return JSON.readValue(Files.readAllBytes(path), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
}
