package migration;

import java.util.*;

/**
 * 外部ライブラリ不要の簡易JSONパーサー。
 * エクスポートデータ（配列 of オブジェクト）の読み込みに特化。
 */
public class SimpleJsonParser {

    private final String json;
    private int pos;

    private SimpleJsonParser(String json) {
        this.json = json;
        this.pos = 0;
    }

    public static List<Map<String, String>> parseArray(String json) {
        SimpleJsonParser parser = new SimpleJsonParser(json.trim());
        return parser.readArray();
    }

    private List<Map<String, String>> readArray() {
        skipWhitespace();
        expect('[');
        List<Map<String, String>> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') { advance(); return list; }

        list.add(readObject());
        skipWhitespace();
        while (peek() == ',') {
            advance();
            skipWhitespace();
            list.add(readObject());
            skipWhitespace();
        }
        expect(']');
        return list;
    }

    private Map<String, String> readObject() {
        skipWhitespace();
        expect('{');
        Map<String, String> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') { advance(); return map; }

        readKeyValue(map);
        skipWhitespace();
        while (peek() == ',') {
            advance();
            skipWhitespace();
            readKeyValue(map);
            skipWhitespace();
        }
        expect('}');
        return map;
    }

    private void readKeyValue(Map<String, String> map) {
        skipWhitespace();
        String key = readString();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        String value = readValue();
        map.put(key, value);
    }

    private String readValue() {
        skipWhitespace();
        char c = peek();
        if (c == '"') return readString();
        if (c == 'n') { expectLiteral("null"); return ""; }
        if (c == 't') { expectLiteral("true"); return "true"; }
        if (c == 'f') { expectLiteral("false"); return "false"; }
        // 数値
        return readNumber();
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"') { pos++; return sb.toString(); }
            if (c == '\\') {
                pos++;
                char esc = json.charAt(pos);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = json.substring(pos + 1, pos + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
            pos++;
        }
        throw new RuntimeException("文字列が閉じられていません");
    }

    private String readNumber() {
        int start = pos;
        while (pos < json.length() && "0123456789.eE+-".indexOf(json.charAt(pos)) >= 0) {
            pos++;
        }
        return json.substring(start, pos);
    }

    private void expectLiteral(String literal) {
        for (int i = 0; i < literal.length(); i++) {
            if (pos >= json.length() || json.charAt(pos) != literal.charAt(i)) {
                throw new RuntimeException("'" + literal + "' を期待しましたが一致しません (位置 " + pos + ")");
            }
            pos++;
        }
    }

    private void expect(char c) {
        skipWhitespace();
        if (pos >= json.length() || json.charAt(pos) != c) {
            throw new RuntimeException("'" + c + "' を期待しましたが '" +
                (pos < json.length() ? json.charAt(pos) : "EOF") + "' が見つかりました (位置 " + pos + ")");
        }
        pos++;
    }

    private char peek() {
        if (pos >= json.length()) throw new RuntimeException("予期しないファイル終端");
        return json.charAt(pos);
    }

    private void advance() { pos++; }

    private void skipWhitespace() {
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
    }
}
