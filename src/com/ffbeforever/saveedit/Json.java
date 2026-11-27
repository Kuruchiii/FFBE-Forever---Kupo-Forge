package com.ffbeforever.saveedit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader/writer that keeps key order and the exact text of every number,
 * and writes the same layout as Python's json.dumps(indent=1, ensure_ascii=False).
 * Objects are LinkedHashMap, arrays ArrayList, numbers Num, null is Json.NULL.
 */
public final class Json {
    public static final Object NULL = new Object() { public String toString() { return "null"; } };

    /** A number kept as its source text, so writing it back is exact. */
    public static final class Num {
        public final String raw;
        public Num(String raw) { this.raw = raw; }
        public Num(long v) { this.raw = Long.toString(v); }
        public long asLong() {
            try { return Long.parseLong(raw); } catch (NumberFormatException e) { return (long) Double.parseDouble(raw); }
        }
        public String toString() { return raw; }
    }

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("extra text after JSON");
        return v;
    }

    private RuntimeException err(String m) { return new IllegalArgumentException(m + " at char " + i); }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++; else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return num();
                throw err("bad character '" + c + "'");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("expected " + w);
        i += w.length();
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (s.charAt(i) != '"') throw err("expected key");
            String k = str();
            ws();
            if (s.charAt(i) != ':') throw err("expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> arr() {
        List<Object> a = new ArrayList<Object>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String str() {
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Num num() {
        int st = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++; else break;
        }
        return new Num(s.substring(st, i));
    }

    // ---- writer: Python json.dumps(obj, indent=1, ensure_ascii=False) ----

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        put(b, v, 0);
        return b.toString();
    }

    private static void nl(StringBuilder b, int depth) {
        b.append('\n');
        for (int k = 0; k < depth; k++) b.append(' ');
    }

    @SuppressWarnings("unchecked")
    private static void put(StringBuilder b, Object v, int depth) {
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, depth + 1);
                quote(b, e.getKey());
                b.append(": ");
                put(b, e.getValue(), depth + 1);
            }
            nl(b, depth);
            b.append('}');
        } else if (v instanceof List) {
            List<Object> a = (List<Object>) v;
            if (a.isEmpty()) { b.append("[]"); return; }
            b.append('[');
            for (int k = 0; k < a.size(); k++) {
                if (k > 0) b.append(',');
                nl(b, depth + 1);
                put(b, a.get(k), depth + 1);
            }
            nl(b, depth);
            b.append(']');
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Num) {
            b.append(((Num) v).raw);
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v) ? "true" : "false");
        } else if (v == NULL || v == null) {
            b.append("null");
        } else if (v instanceof Number) {
            b.append(v.toString());
        } else {
            throw new IllegalArgumentException("cannot write " + v.getClass());
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void quote(StringBuilder b, String t) {
        b.append('"');
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        b.append("\\u00").append(HEX[(c >> 4) & 15]).append(HEX[c & 15]);
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
