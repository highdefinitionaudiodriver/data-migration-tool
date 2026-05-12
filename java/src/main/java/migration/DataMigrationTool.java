package migration;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * データ移行ツール - デスクトップアプリのエクスポートデータ → ウェブアプリ用DB変換
 *
 * <p>コンプライアンス方針:
 * <ul>
 *   <li>リバースエンジニアリング一切不使用</li>
 *   <li>ユーザーが正規エクスポート機能で出力したファイルのみを入力とする</li>
 *   <li>元ソフトウェアの商標・UI模倣なし</li>
 * </ul>
 *
 * <p>対応入力: CSV, TSV, JSON, XML
 * <p>対応出力: JSON, Oracle SQL, PostgreSQL SQL
 * <p>マッピング: 外部設定ファイル（mapping_config.json）で定義
 */
public class DataMigrationTool {

    // ============================================================
    // フィールド定義
    // ============================================================

    enum FieldType { STR, INT, FLOAT, DATE, BOOL }

    record FieldSpec(
        String source,
        FieldType type,
        boolean required,
        Object defaultValue,
        int maxLen,
        String oracleType,
        String pgType
    ) {}

    /** 設定ファイルから読み込んだフィールドマップ（実行時に設定） */
    static LinkedHashMap<String, FieldSpec> fieldMap = new LinkedHashMap<>();

    // ============================================================
    // 設定ファイル読み込み
    // ============================================================

    record MigrationConfig(String tableName, LinkedHashMap<String, FieldSpec> fields) {}

    static MigrationConfig loadConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            throw new FileNotFoundException("設定ファイルが見つかりません: " + configPath);
        }

        String text = Files.readString(configPath, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        return parseConfigManually(text, configPath);
    }

    private static MigrationConfig parseConfigManually(String json, Path configPath) {
        // 軽量な設定ファイルパーサー
        String tableName = extractStringValue(json, "table_name", "imported_data");
        String fieldsBlock = extractObjectBlock(json, "fields");

        if (fieldsBlock == null || fieldsBlock.isBlank()) {
            throw new RuntimeException("設定ファイルに 'fields' オブジェクトが必要です");
        }

        LinkedHashMap<String, FieldSpec> fields = new LinkedHashMap<>();
        // フィールドブロックから各フィールドを抽出
        int pos = 0;
        while (pos < fieldsBlock.length()) {
            // 次のフィールド名を検索
            int keyStart = fieldsBlock.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = fieldsBlock.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String fieldName = fieldsBlock.substring(keyStart + 1, keyEnd);

            // フィールド定義のオブジェクトブロックを取得
            int objStart = fieldsBlock.indexOf('{', keyEnd);
            if (objStart < 0) break;
            int objEnd = findMatchingBrace(fieldsBlock, objStart);
            if (objEnd < 0) break;
            String fieldJson = fieldsBlock.substring(objStart, objEnd + 1);

            String source = extractStringValue(fieldJson, "source", "");
            String typeStr = extractStringValue(fieldJson, "type", "str");
            boolean required = extractBoolValue(fieldJson, "required", false);
            int maxLen = extractIntValue(fieldJson, "max_len", 0);
            String oracleType = extractStringValue(fieldJson, "oracle_type", "VARCHAR2(255)");
            String pgType = extractStringValue(fieldJson, "pg_type", "VARCHAR(255)");

            // default値の処理
            Object defaultValue = null;
            String defaultStr = extractRawValue(fieldJson, "default");
            if (defaultStr != null && !defaultStr.equals("null")) {
                FieldType ft = parseFieldType(typeStr);
                switch (ft) {
                    case BOOL -> defaultValue = defaultStr.equals("true");
                    case INT -> { try { defaultValue = Integer.parseInt(defaultStr); } catch (NumberFormatException ignored) {} }
                    case FLOAT -> { try { defaultValue = Double.parseDouble(defaultStr); } catch (NumberFormatException ignored) {} }
                    default -> defaultValue = defaultStr.replace("\"", "");
                }
            }

            FieldType type = parseFieldType(typeStr);
            fields.put(fieldName, new FieldSpec(source, type, required, defaultValue, maxLen, oracleType, pgType));

            pos = objEnd + 1;
        }

        if (fields.isEmpty()) {
            throw new RuntimeException("'fields' にフィールド定義が1つ以上必要です");
        }

        Set<String> validTypes = Set.of("str", "int", "float", "date", "bool");
        System.out.println("[INFO] 設定ファイル読み込み完了: " + configPath.getFileName()
            + " (テーブル: " + tableName + ", フィールド数: " + fields.size() + ")");

        return new MigrationConfig(tableName, fields);
    }

    private static FieldType parseFieldType(String typeStr) {
        return switch (typeStr.toLowerCase()) {
            case "int" -> FieldType.INT;
            case "float" -> FieldType.FLOAT;
            case "date" -> FieldType.DATE;
            case "bool" -> FieldType.BOOL;
            default -> FieldType.STR;
        };
    }

    // --- 簡易JSON値抽出ヘルパー ---

    private static String extractStringValue(String json, String key, String defaultVal) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultVal;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return defaultVal;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        // null チェック
        String afterColon = json.substring(colonIdx + 1, Math.min(colonIdx + 20, json.length())).trim();
        if (afterColon.startsWith("null")) return defaultVal;
        if (quoteStart < 0) return defaultVal;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return defaultVal;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static boolean extractBoolValue(String json, String key, boolean defaultVal) {
        String raw = extractRawValue(json, key);
        if (raw == null) return defaultVal;
        return raw.trim().equals("true");
    }

    private static int extractIntValue(String json, String key, int defaultVal) {
        String raw = extractRawValue(json, key);
        if (raw == null) return defaultVal;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private static String extractRawValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        // 文字列の場合
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end >= 0 ? json.substring(start + 1, end) : null;
        }
        // 非文字列: カンマ、改行、}まで
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
               && json.charAt(end) != '\n' && json.charAt(end) != '\r') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static String extractObjectBlock(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int braceStart = json.indexOf('{', idx + pattern.length());
        if (braceStart < 0) return null;
        int braceEnd = findMatchingBrace(json, braceStart);
        if (braceEnd < 0) return null;
        return json.substring(braceStart + 1, braceEnd);
    }

    private static int findMatchingBrace(String json, int openPos) {
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{') depth++;
            if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    // ============================================================
    // 文字コード自動判定
    // ============================================================

    static Charset detectEncoding(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);

        if (raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (raw.length >= 2 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (raw.length >= 2 && raw[0] == (byte) 0xFE && raw[1] == (byte) 0xFF) {
            return StandardCharsets.UTF_16BE;
        }

        if (isValidUtf8(raw)) {
            return StandardCharsets.UTF_8;
        }

        try {
            Charset ms932 = Charset.forName("MS932");
            new String(raw, ms932).getBytes(ms932);
            return ms932;
        } catch (Exception ignored) {}

        try {
            Charset eucjp = Charset.forName("EUC-JP");
            new String(raw, eucjp).getBytes(eucjp);
            return eucjp;
        } catch (Exception ignored) {}

        return StandardCharsets.UTF_8;
    }

    private static boolean isValidUtf8(byte[] data) {
        int i = 0;
        while (i < data.length) {
            int b = data[i] & 0xFF;
            int len;
            if (b <= 0x7F) { len = 1; }
            else if (b >= 0xC2 && b <= 0xDF) { len = 2; }
            else if (b >= 0xE0 && b <= 0xEF) { len = 3; }
            else if (b >= 0xF0 && b <= 0xF4) { len = 4; }
            else { return false; }
            if (i + len > data.length) return false;
            for (int j = 1; j < len; j++) {
                if ((data[i + j] & 0xC0) != 0x80) return false;
            }
            i += len;
        }
        return true;
    }

    // ============================================================
    // 入力パーサー
    // ============================================================

    static List<Map<String, String>> readCsv(Path path) throws IOException {
        Charset charset = detectEncoding(path);
        System.out.println("[INFO] 検出した文字コード: " + charset.name() + " (" + path.getFileName() + ")");

        List<String> lines = Files.readAllLines(path, charset);
        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }
        if (lines.isEmpty()) return Collections.emptyList();

        char delimiter = detectDelimiter(lines.get(0));
        String[] headers = splitLine(lines.get(0), delimiter);

        List<Map<String, String>> records = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] values = splitLine(line, delimiter);
            Map<String, String> record = new LinkedHashMap<>();
            for (int j = 0; j < headers.length; j++) {
                record.put(headers[j].trim(), j < values.length ? values[j].trim() : "");
            }
            records.add(record);
        }
        return records;
    }

    private static char detectDelimiter(String headerLine) {
        int tabs = count(headerLine, '\t');
        int commas = count(headerLine, ',');
        int semis = count(headerLine, ';');
        int pipes = count(headerLine, '|');
        if (tabs >= commas && tabs >= semis && tabs >= pipes && tabs > 0) return '\t';
        if (semis >= commas && semis >= pipes && semis > 0) return ';';
        if (pipes >= commas && pipes > 0) return '|';
        return ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char ch : s.toCharArray()) if (ch == c) n++;
        return n;
    }

    private static String[] splitLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    static List<Map<String, String>> readJson(Path path) throws IOException {
        Charset charset = detectEncoding(path);
        String text = Files.readString(path, charset);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return SimpleJsonParser.parseArray(text);
    }

    static List<Map<String, String>> readXml(Path path) throws Exception {
        Charset charset = detectEncoding(path);
        String text = Files.readString(path, charset);
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document doc = builder.parse(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));

        org.w3c.dom.Element root = doc.getDocumentElement();
        org.w3c.dom.NodeList children = root.getChildNodes();
        List<Map<String, String>> records = new ArrayList<>();

        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            org.w3c.dom.Element recordEl = (org.w3c.dom.Element) children.item(i);
            org.w3c.dom.NodeList fields = recordEl.getChildNodes();
            Map<String, String> record = new LinkedHashMap<>();
            for (int j = 0; j < fields.getLength(); j++) {
                if (fields.item(j).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                org.w3c.dom.Element field = (org.w3c.dom.Element) fields.item(j);
                record.put(field.getTagName(), field.getTextContent());
            }
            records.add(record);
        }
        return records;
    }

    static List<Map<String, String>> loadInput(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv") || name.endsWith(".tsv")) return readCsv(path);
        if (name.endsWith(".json")) return readJson(path);
        if (name.endsWith(".xml")) return readXml(path);
        throw new IllegalArgumentException("未対応のファイル形式: " + name + " (対応: .csv, .tsv, .json, .xml)");
    }

    // ============================================================
    // バリデーション & 変換
    // ============================================================

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy年MM月dd日"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
    };

    static Object convertValue(String value, String fieldName, FieldSpec spec) throws ValidationException {
        if (value == null || value.trim().isEmpty()) {
            if (spec.required()) throw new ValidationException("'" + fieldName + "' は必須項目です");
            return spec.defaultValue();
        }
        value = value.trim();

        switch (spec.type()) {
            case STR -> {
                if (spec.maxLen() > 0 && value.length() > spec.maxLen()) {
                    throw new ValidationException(
                        "'" + fieldName + "' が最大長 " + spec.maxLen() + " を超えています (" + value.length() + "文字)");
                }
                return value;
            }
            case INT -> {
                try { return Integer.parseInt(value.replace(",", "")); }
                catch (NumberFormatException e) {
                    throw new ValidationException("'" + fieldName + "' を整数に変換できません: '" + value + "'");
                }
            }
            case FLOAT -> {
                try { return Double.parseDouble(value.replace(",", "")); }
                catch (NumberFormatException e) {
                    throw new ValidationException("'" + fieldName + "' を数値に変換できません: '" + value + "'");
                }
            }
            case DATE -> {
                for (DateTimeFormatter fmt : DATE_FORMATS) {
                    try {
                        LocalDate d = LocalDate.parse(value, fmt);
                        return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
                    } catch (DateTimeParseException ignored) {}
                }
                throw new ValidationException("'" + fieldName + "' の日付形式を認識できません: '" + value + "'");
            }
            case BOOL -> {
                String lower = value.toLowerCase();
                if (Set.of("true", "1", "yes", "○", "はい").contains(lower)) return true;
                if (Set.of("false", "0", "no", "×", "いいえ", "").contains(lower)) return false;
                throw new ValidationException("'" + fieldName + "' を真偽値に変換できません: '" + value + "'");
            }
        }
        return value;
    }

    record TransformResult(Map<String, Object> record, List<String> errors) {}

    static TransformResult transformRecord(Map<String, String> row, int rowIndex) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();

        for (var entry : fieldMap.entrySet()) {
            String destField = entry.getKey();
            FieldSpec spec = entry.getValue();
            String rawValue = spec.source() != null ? row.get(spec.source()) : null;
            try {
                result.put(destField, convertValue(rawValue, destField, spec));
            } catch (ValidationException e) {
                errors.add("行" + rowIndex + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) return new TransformResult(null, errors);
        return new TransformResult(result, Collections.emptyList());
    }

    // ============================================================
    // 出力フォーマッター
    // ============================================================

    static void outputJson(List<Map<String, Object>> records, Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < records.size(); i++) {
            sb.append("  ").append(mapToJson(records.get(i)));
            if (i < records.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[INFO] JSON出力: " + outputPath + " (" + records.size() + "件)");
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var entry : map.entrySet()) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\": ");
            sb.append(valueToJson(entry.getValue()));
            i++;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String valueToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number) return v.toString();
        return "\"" + escapeJson(v.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    static String formatSqlValue(Object v, String fieldName, String dialect) {
        if (v == null) return "NULL";

        FieldSpec spec = fieldMap.get(fieldName);
        FieldType type = spec != null ? spec.type() : FieldType.STR;

        if (type == FieldType.BOOL || v instanceof Boolean) {
            boolean b = v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(v.toString());
            if ("oracle".equals(dialect)) return b ? "1" : "0";
            return b ? "TRUE" : "FALSE";
        }

        if (v instanceof Number) return v.toString();

        if (type == FieldType.DATE) {
            String escaped = v.toString().replace("'", "''");
            if ("oracle".equals(dialect)) return "TO_DATE('" + escaped + "', 'YYYY-MM-DD')";
            return "'" + escaped + "'::DATE";
        }

        String escaped = v.toString().replace("'", "''");
        if ("oracle".equals(dialect)) return "N'" + escaped + "'";
        return "'" + escaped + "'";
    }

    static void outputSql(List<Map<String, Object>> records, Path outputPath,
                           String tableName, String dialect) throws IOException {
        if (records.isEmpty()) {
            Files.writeString(outputPath, "-- No records\n", StandardCharsets.UTF_8);
            return;
        }

        String dbLabel = "oracle".equals(dialect) ? "Oracle" : "PostgreSQL";
        List<String> columns = new ArrayList<>(records.get(0).keySet());
        String colStr = String.join(", ", columns);

        StringBuilder sb = new StringBuilder();
        sb.append("-- データ移行ツール生成 (").append(dbLabel).append(")\n");
        sb.append("-- テーブル: ").append(tableName).append("\n");
        sb.append("-- レコード数: ").append(records.size()).append("\n\n");

        if ("postgresql".equals(dialect)) sb.append("BEGIN;\n\n");

        for (var rec : records) {
            String vals = columns.stream()
                .map(col -> formatSqlValue(rec.get(col), col, dialect))
                .collect(Collectors.joining(", "));
            sb.append("INSERT INTO ").append(tableName)
              .append(" (").append(colStr).append(") VALUES (").append(vals).append(");\n");
        }

        sb.append("\nCOMMIT;\n");
        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[INFO] SQL出力 (" + dbLabel + "): " + outputPath + " (" + records.size() + "件)");
    }

    static void outputCreateTable(Path outputPath, String tableName, String dialect) throws IOException {
        String dbLabel = "oracle".equals(dialect) ? "Oracle" : "PostgreSQL";
        StringBuilder sb = new StringBuilder();
        sb.append("-- CREATE TABLE (").append(dbLabel).append(")\n");
        sb.append("CREATE TABLE ").append(tableName).append(" (\n");

        int i = 0;
        for (var entry : fieldMap.entrySet()) {
            if (i > 0) sb.append(",\n");
            String colType = "oracle".equals(dialect) ? entry.getValue().oracleType() : entry.getValue().pgType();
            String notNull = entry.getValue().required() ? " NOT NULL" : "";
            sb.append("    ").append(entry.getKey()).append(" ").append(colType).append(notNull);
            i++;
        }
        sb.append("\n);\n");
        Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[INFO] CREATE TABLE出力 (" + dbLabel + "): " + outputPath);
    }

    // ============================================================
    // メイン処理
    // ============================================================

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("使用方法: java migration.DataMigrationTool <入力ファイル> [オプション]");
            System.out.println("  -c <設定ファイル>   マッピング設定ファイル (必須)");
            System.out.println("  -o <出力先>         出力ファイルパス (デフォルト: output.json)");
            System.out.println("  -f json|sql         出力形式 (デフォルト: json)");
            System.out.println("  -d oracle|postgresql SQL方言 (デフォルト: postgresql)");
            System.out.println("  -t <テーブル名>     テーブル名 (省略時は設定ファイルの値を使用)");
            System.out.println("  -e <エラーログ>     エラーログ出力先 (デフォルト: errors.log)");
            System.out.println("  --create-table <パス>  CREATE TABLE文の出力先");
            System.exit(1);
        }

        String inputFile = args[0];
        String configFile = null;
        String outputFile = "output.json";
        String format = "json";
        String dialect = "postgresql";
        String tableName = null;
        String errorLog = "errors.log";
        String createTable = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-c" -> configFile = args[++i];
                case "-o" -> outputFile = args[++i];
                case "-f" -> format = args[++i];
                case "-d" -> dialect = args[++i];
                case "-t" -> tableName = args[++i];
                case "-e" -> errorLog = args[++i];
                case "--create-table" -> createTable = args[++i];
                default -> System.err.println("不明なオプション: " + args[i]);
            }
        }

        if (configFile == null) {
            System.err.println("[ERROR] 設定ファイル(-c)は必須です");
            System.exit(1);
        }

        try {
            // 設定ファイル読み込み
            MigrationConfig config = loadConfig(Path.of(configFile));
            fieldMap = config.fields();

            // テーブル名: 引数 > 設定ファイル > デフォルト
            if (tableName == null) tableName = config.tableName();

            Path inputPath = Path.of(inputFile);
            if (!Files.exists(inputPath)) {
                System.err.println("[ERROR] 入力ファイルが見つかりません: " + inputPath);
                System.exit(1);
            }

            System.out.println("[INFO] 入力ファイル読み込み中: " + inputPath);
            List<Map<String, String>> rows = loadInput(inputPath);
            System.out.println("[INFO] 読み込み件数: " + rows.size());

            List<Map<String, Object>> converted = new ArrayList<>();
            List<String> allErrors = new ArrayList<>();

            for (int i = 0; i < rows.size(); i++) {
                TransformResult result = transformRecord(rows.get(i), i + 1);
                if (result.record() != null) {
                    converted.add(result.record());
                } else {
                    allErrors.addAll(result.errors());
                }
            }

            Path outputPath = Path.of(outputFile);
            if ("json".equals(format)) {
                outputJson(converted, outputPath);
            } else {
                outputSql(converted, outputPath, tableName, dialect);
            }

            if (createTable != null) {
                outputCreateTable(Path.of(createTable), tableName, dialect);
            }

            if (!allErrors.isEmpty()) {
                Files.writeString(Path.of(errorLog), String.join("\n", allErrors) + "\n", StandardCharsets.UTF_8);
                System.out.println("[WARN] エラー件数: " + allErrors.size() + " → " + errorLog);
            } else {
                System.out.println("[INFO] エラーなし");
            }

            int total = rows.size();
            int ok = converted.size();
            System.out.println("[INFO] 完了: 全" + total + "件 / 成功" + ok + "件 / スキップ" + (total - ok) + "件");

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static class ValidationException extends Exception {
        ValidationException(String message) { super(message); }
    }
}
