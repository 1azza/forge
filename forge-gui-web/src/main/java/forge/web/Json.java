package forge.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer and reader.
 *
 * <p>Forge has no JSON library on the classpath and the wire format here is small and
 * fully under our control, so pulling in a dependency for it isn't worth the build
 * weight. The writer is a thin builder over a {@link StringBuilder}; the reader handles
 * the subset of JSON the browser client actually sends (objects, arrays, strings,
 * numbers, booleans, null).
 */
public final class Json {

    private Json() { }

    // ---------------------------------------------------------------- writing

    /** Fluent object writer. {@link #end()} closes the object and returns the buffer. */
    public static final class Obj {
        private final StringBuilder sb;
        private boolean first = true;

        Obj(final StringBuilder sb) {
            this.sb = sb;
            sb.append('{');
        }

        private Obj key(final String name) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            escape(sb, name);
            sb.append(':');
            return this;
        }

        public Obj put(final String name, final String value) {
            if (value == null) {
                return this;
            }
            key(name);
            escape(sb, value);
            return this;
        }

        public Obj put(final String name, final int value) {
            key(name);
            sb.append(value);
            return this;
        }

        public Obj put(final String name, final long value) {
            key(name);
            sb.append(value);
            return this;
        }

        public Obj put(final String name, final boolean value) {
            key(name);
            sb.append(value);
            return this;
        }

        /** Only writes the key when {@code value} is true, keeping payloads small. */
        public Obj putIf(final String name, final boolean value) {
            return value ? put(name, true) : this;
        }

        /** Writes an already-serialised JSON fragment verbatim. */
        public Obj putRaw(final String name, final String jsonFragment) {
            if (jsonFragment == null) {
                return this;
            }
            key(name);
            sb.append(jsonFragment);
            return this;
        }

        public Obj obj(final String name) {
            key(name);
            return new Obj(sb);
        }

        public Arr arr(final String name) {
            key(name);
            return new Arr(sb);
        }

        public StringBuilder end() {
            sb.append('}');
            return sb;
        }
    }

    /** Fluent array writer. */
    public static final class Arr {
        private final StringBuilder sb;
        private boolean first = true;

        Arr(final StringBuilder sb) {
            this.sb = sb;
            sb.append('[');
        }

        private void comma() {
            if (!first) {
                sb.append(',');
            }
            first = false;
        }

        public Arr add(final String value) {
            comma();
            escape(sb, value);
            return this;
        }

        public Arr add(final int value) {
            comma();
            sb.append(value);
            return this;
        }

        public Arr addRaw(final String jsonFragment) {
            comma();
            sb.append(jsonFragment);
            return this;
        }

        public Obj obj() {
            comma();
            return new Obj(sb);
        }

        public StringBuilder end() {
            sb.append(']');
            return sb;
        }
    }

    public static Obj obj() {
        return new Obj(new StringBuilder(256));
    }

    public static Obj obj(final StringBuilder sb) {
        return new Obj(sb);
    }

    public static Arr arr(final StringBuilder sb) {
        return new Arr(sb);
    }

    public static void escape(final StringBuilder sb, final String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- reading

    /** Parses {@code text} into nested {@link Map}/{@link List}/String/Double/Boolean/null. */
    public static Object parse(final String text) {
        final Parser p = new Parser(text);
        p.skipWs();
        final Object value = p.value();
        p.skipWs();
        return value;
    }

    /** Parses {@code text} expecting a JSON object; returns an empty map on anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(final String text) {
        try {
            final Object parsed = parse(text);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        } catch (final RuntimeException e) {
            return Map.of();
        }
    }

    public static String str(final Map<String, Object> map, final String key) {
        final Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static int integer(final Map<String, Object> map, final String key, final int fallback) {
        final Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(final Map<String, Object> map, final String key) {
        return Boolean.TRUE.equals(map.get(key));
    }

    /** Returns the value at {@code key} as a list of ints, tolerating numbers-as-strings. */
    public static List<Integer> intList(final Map<String, Object> map, final String key) {
        final List<Integer> out = new ArrayList<>();
        if (map.get(key) instanceof List<?> list) {
            for (final Object o : list) {
                if (o instanceof Number n) {
                    out.add(n.intValue());
                } else if (o instanceof String s) {
                    try {
                        out.add(Integer.parseInt(s.trim()));
                    } catch (final NumberFormatException ignored) {
                        // skip malformed entries rather than failing the whole message
                    }
                }
            }
        }
        return out;
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(final String src) {
            this.src = src;
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            skipWs();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            final char c = src.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(final String token, final Object value) {
            if (!src.startsWith(token, pos)) {
                throw new IllegalArgumentException("bad literal at " + pos);
            }
            pos += token.length();
            return value;
        }

        private Map<String, Object> object() {
            final Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                final String key = string();
                skipWs();
                expect(':');
                map.put(key, value());
                skipWs();
                final char c = expectOneOf(',', '}');
                if (c == '}') {
                    return map;
                }
            }
        }

        private List<Object> array() {
            final List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWs();
                final char c = expectOneOf(',', ']');
                if (c == ']') {
                    return list;
                }
            }
        }

        private String string() {
            expect('"');
            final StringBuilder sb = new StringBuilder();
            while (true) {
                final char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                final char esc = src.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + esc);
                }
            }
        }

        private Double number() {
            final int start = pos;
            while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.valueOf(src.substring(start, pos));
        }

        private void expect(final char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + pos);
            }
            pos++;
        }

        private char expectOneOf(final char a, final char b) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            final char c = src.charAt(pos);
            if (c != a && c != b) {
                throw new IllegalArgumentException("expected " + a + " or " + b + " at " + pos);
            }
            pos++;
            return c;
        }
    }
}
