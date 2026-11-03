package com.smarttask.main;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {
    }

    static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException("Unexpected content after JSON value.");
        }
        return value;
    }

    static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(output, value);
        return output.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("A JSON object is required.");
        }
        return (Map<String, Object>) value;
    }

    private static void writeValue(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            writeString(output, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            output.append(value);
        } else if (value instanceof Map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    output.append(',');
                }
                writeString(output, String.valueOf(entry.getKey()));
                output.append(':');
                writeValue(output, entry.getValue());
                first = false;
            }
            output.append('}');
        } else if (value instanceof Iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    output.append(',');
                }
                writeValue(output, item);
                first = false;
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value.");
        }
    }

    private static void writeString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object readValue() {
            skipWhitespace();
            if (isAtEnd()) {
                throw invalidJson();
            }

            char character = input.charAt(index);
            if (character == '{') {
                return readObject();
            }
            if (character == '[') {
                return readArray();
            }
            if (character == '"') {
                return readString();
            }
            if (character == 't') {
                return readLiteral("true", Boolean.TRUE);
            }
            if (character == 'f') {
                return readLiteral("false", Boolean.FALSE);
            }
            if (character == 'n') {
                return readLiteral("null", null);
            }
            if (character == '-' || Character.isDigit(character)) {
                return readNumber();
            }
            throw invalidJson();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (consume('}')) {
                return object;
            }

            while (true) {
                skipWhitespace();
                if (isAtEnd() || input.charAt(index) != '"') {
                    throw invalidJson();
                }
                String key = readString();
                skipWhitespace();
                require(':');
                if (object.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate JSON object key.");
                }
                object.put(key, readValue());
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                require(',');
            }
        }

        private List<Object> readArray() {
            List<Object> array = new ArrayList<>();
            index++;
            skipWhitespace();
            if (consume(']')) {
                return array;
            }

            while (true) {
                array.add(readValue());
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                require(',');
            }
        }

        private String readString() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (!isAtEnd()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    if (isAtEnd()) {
                        throw invalidJson();
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            value.append(escaped);
                            break;
                        case 'b':
                            value.append('\b');
                            break;
                        case 'f':
                            value.append('\f');
                            break;
                        case 'n':
                            value.append('\n');
                            break;
                        case 'r':
                            value.append('\r');
                            break;
                        case 't':
                            value.append('\t');
                            break;
                        case 'u':
                            value.append(readUnicodeEscape());
                            break;
                        default:
                            throw invalidJson();
                    }
                } else {
                    if (character < 0x20) {
                        throw invalidJson();
                    }
                    value.append(character);
                }
            }
            throw invalidJson();
        }

        private char readUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw invalidJson();
            }
            try {
                char value = (char) Integer.parseInt(input.substring(index, index + 4), 16);
                index += 4;
                return value;
            } catch (NumberFormatException exception) {
                throw invalidJson();
            }
        }

        private Object readNumber() {
            int start = index;
            consume('-');
            if (consume('0')) {
                if (!isAtEnd() && Character.isDigit(input.charAt(index))) {
                    throw invalidJson();
                }
            } else {
                readDigits();
            }
            if (consume('.')) {
                readDigits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                readDigits();
            }
            try {
                return Double.valueOf(input.substring(start, index));
            } catch (NumberFormatException exception) {
                throw invalidJson();
            }
        }

        private void readDigits() {
            int start = index;
            while (!isAtEnd() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw invalidJson();
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw invalidJson();
            }
            index += literal.length();
            return value;
        }

        private void require(char character) {
            if (!consume(character)) {
                throw invalidJson();
            }
        }

        private boolean consume(char character) {
            if (!isAtEnd() && input.charAt(index) == character) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!isAtEnd() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private boolean isAtEnd() {
            return index >= input.length();
        }

        private IllegalArgumentException invalidJson() {
            return new IllegalArgumentException("Invalid JSON request body.");
        }
    }
}
