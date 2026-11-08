package com.playwrightdebug.locator.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser (objects, arrays, strings, numbers, booleans, null).
 * Used so Recorder conversion does not depend on Groovy.
 */
final class SimpleJson {

    private SimpleJson() {
    }

    static Object parse(String json) {
        return new Parser(json).parseValue();
    }

    private static final class Parser {
        private final String json;
        private int index;

        Parser(String json) {
            this.json = json;
        }

        Object parseValue() {
            skipWhitespace();
            if (index >= json.length()) {
                throw error("Unexpected end of JSON");
            }
            char c = json.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || Character.isDigit(c)) {
                        yield parseNumber();
                    }
                    throw error("Unexpected character: " + c);
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (index >= json.length()) {
                        throw error("Unterminated escape");
                    }
                    char esc = json.charAt(index++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (index + 4 > json.length()) {
                                throw error("Invalid unicode escape");
                            }
                            String hex = json.substring(index, index + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw error("Invalid escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Number parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (peek('0')) {
                index++;
            } else {
                if (!Character.isDigit(peekChar())) {
                    throw error("Invalid number");
                }
                while (Character.isDigit(peekChar())) {
                    index++;
                }
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                index++;
                if (!Character.isDigit(peekChar())) {
                    throw error("Invalid number");
                }
                while (Character.isDigit(peekChar())) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                decimal = true;
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                if (!Character.isDigit(peekChar())) {
                    throw error("Invalid number");
                }
                while (Character.isDigit(peekChar())) {
                    index++;
                }
            }
            String raw = json.substring(start, index);
            if (decimal) {
                return Double.parseDouble(raw);
            }
            long value = Long.parseLong(raw);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
            return value;
        }

        private Object parseLiteral(String literal, Object value) {
            if (!json.startsWith(literal, index)) {
                throw error("Expected " + literal);
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < json.length()) {
                char c = json.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!peek(expected)) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }

        private char peekChar() {
            return index < json.length() ? json.charAt(index) : '\0';
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + index);
        }
    }
}
