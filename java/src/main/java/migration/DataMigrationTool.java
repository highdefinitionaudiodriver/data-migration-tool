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
 */
public class DataMigrationTool {

    // ============================================================
    // フィールド定義（★ プロジェクトに合わせてカスタマイズ ★）
    // ============================================================

    enum FieldType { STR, INT, FLOAT, DATE, BOOL }

    record FieldSpec(
        String source,       // 元データのカラム名
        FieldType type,
        boolean required,
        Object defaultValue,
        int maxLen,          // 0 = 制限なし
        String oracleType,
        String pgType
    ) {}

    /** 移行先カラム名 → フィールド仕様 */
    static final LinkedHashMap<String, FieldSpec> FIELD_MAP = new LinkedHashMap<>();
    static {
        FIELD_MAP.put("id",         new FieldSpec("ID",           FieldType.INT,  true,  null, 0,   "NUMBER(10)",      "INTEGER"));
        FIELD_MAP.put("name",       new FieldSpec("名前",         FieldType.STR,  true,  null, 200, "NVARCHAR2(200)",  "VARCHAR(200)"));
        FIELD_MAP.put("email",      new FieldSpec("メールアドレス", FieldType.STR,  false, null, 254, "NVARCHAR2(254)",  "VARCHAR(254)"));
        FIELD_MAP.put("created_at", new FieldSpec("作成日",        FieldType.DATE, false, null, 0,   "DATE",            "DATE"));
        FIELD_MAP.put("is_active",  new FieldSpec("有効",          FieldType.BOOL, false, true, 0,   "NUMBER(1)",       "BOOLEAN"));
    }

    // ============================================================
    // 文字コード自動判定
    // ============================================================

    static Charset detectEncoding(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);

        // BOM付きUTF-8
        if (raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        // BOM付きUTF-16 LE
        if (raw.length >= 2 && raw[0] == (byte) 0xFF && raw[1] == (byte) 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        // BOM付きUTF-16 BE
        if (raw.length >= 2 && raw[0] == (byte) 0xFE && raw[1] == (byte) 0xFF) {
            return StandardCharsets.UTF_16BE;
        }

        // UTF-8として試行
        if (isValidUtf8(raw)) {
            return StandardCharsets.UTF_8;
        }

        // Shift-JIS (Windows-31J)
        try {
            Charset ms932 = Charset.forName("MS932");
            new String(raw, ms932).getBytes(ms932); // ラウンドトリップ確認
            return ms932;
        } catch (Exception ignored) {}

        // EUC-JP
        try {
            Charset eucjp = Charset.forName("EUC-JP");
            new String(raw, eucjp).getBytes(eucjp);
            return eucjp;
        } catch (Exception ignored) {}

        return StandardCharsets.UTF_8; // フォールバック
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
        // BOM除去
        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }
        if (lines.isEmpty()) return Collections.emptyList();

        // 区切り文字自動判定
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
        // クォート対応の簡易CSVパーサー
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
        // BOM除去
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return SimpleJsonParser.parseArray(text);
    }

    static List<Map<String, String>> readXml(Path path) throws Exception {
        Charset charset = detectEncoding(path);
        String text = Files.readString(path, charset);
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        // XXE対策
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

        for (var entry : FIELD_MAP.entrySet()) {
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

        FieldSpec spec = FIELD_MAP.get(fieldName);
        FieldType type = spec != null ? spec.type() : FieldType.STR;

        // 真偽値
        if (type == FieldType.BOOL || v instanceof Boolean) {
            boolean b = v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(v.toString());
            if ("oracle".equals(dialect)) return b ? "1" : "0";
            return b ? "TRUE" : "FALSE";
        }

        // 数値
        if (v instanceof Number) return v.toString();

        // 日付
        if (type == FieldType.DATE) {
            String escaped = v.toString().replace("'", "''");
            if ("oracle".equals(dialect)) return "TO_DATE('" + escaped + "', 'YYYY-MM-DD')";
            return "'" + escaped + "'::DATE";
        }

        // 文字列
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
        for (var entry : FIELD_MAP.entrySet()) {
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
            System.out.println("  -o <出力先>         出力ファイルパス (デフォルト: output.json)");
            System.out.println("  -f json|sql         出力形式 (デフォルト: json)");
            System.out.println("  -d oracle|postgresql SQL方言 (デフォルト: postgresql)");
            System.out.println("  -t <テーブル名>     テーブル名 (デフォルト: imported_data)");
            System.out.println("  -e <エラーログ>     エラーログ出力先 (デフォルト: errors.log)");
            System.out.println("  --create-table <パス>  CREATE TABLE文の出力先");
            System.exit(1);
        }

        // 引数パース
        String inputFile = args[0];
        String outputFile = "output.json";
        String format = "json";
        String dialect = "postgresql";
        String tableName = "imported_data";
        String errorLog = "errors.log";
        String createTable = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "-o" -> outputFile = args[++i];
                case "-f" -> format = args[++i];
                case "-d" -> dialect = args[++i];
                case "-t" -> tableName = args[++i];
                case "-e" -> errorLog = args[++i];
                case "--create-table" -> createTable = args[++i];
                default -> System.err.println("不明なオプション: " + args[i]);
            }
        }

        try {
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

            // 出力
            Path outputPath = Path.of(outputFile);
            if ("json".equals(format)) {
                outputJson(converted, outputPath);
            } else {
                outputSql(converted, outputPath, tableName, dialect);
            }

            // CREATE TABLE
            if (createTable != null) {
                outputCreateTable(Path.of(createTable), tableName, dialect);
            }

            // エラーログ
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

    // カスタム例外
    static class ValidationException extends Exception {
        ValidationException(String message) { super(message); }
    }
}
