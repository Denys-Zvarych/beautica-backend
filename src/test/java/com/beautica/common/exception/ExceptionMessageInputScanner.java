package com.beautica.common.exception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 500 audit (security LOW) — source scanner behind {@link BusinessExceptionMessageGuardTest}.
 *
 * <p>{@link #scan} flags every business-exception message that references a non-constant value — a concatenated or
 * {@code String.format}-ed variable, a method call, or a bare parameter passed through:
 * <ul>
 *   <li>{@code new BusinessException(...)} / {@code new ConflictException(...)}, simple or fully qualified;</li>
 *   <li>{@code BusinessException::new} / {@code ConflictException::new} (message unknowable — always flagged);</li>
 *   <li>{@code super(...)} in a class that {@code extends BusinessException} or {@code ConflictException}.</li>
 * </ul>
 * The message is the LAST argument. {@link BusinessException#withUserInput} is a static call, never matched. A message
 * made only of string literals, numbers and {@code UPPER_SNAKE} constants is not flagged.
 *
 * <p>{@link #scanHelperCalls} closes the pass-through hole: when a reviewed site's message is a helper PARAMETER, every
 * call of that helper must pass a constant as its last argument (or, inside another such helper's body, forward the
 * same parameter unchanged).
 *
 * <p>Pure JDK, string-aware: comments, string/char literals and text blocks are blanked before matching, so a match
 * inside Javadoc or a literal is ignored and line numbers are preserved.
 */
final class ExceptionMessageInputScanner {

    /** One flagged site: file, 1-based line, and the message/argument expression (whitespace-collapsed). */
    record Site(String file, int line, String message) {
    }

    /** Helper-call scan: how many calls were seen (to catch a stale entry) and which pass a non-constant. */
    record HelperCalls(int calls, List<Site> flagged) {
    }

    static final String METHOD_REFERENCE_MESSAGE = "::new";

    private static final String EXCEPTION_NAMES = "(?:BusinessException|ConflictException)";
    private static final Pattern THROW_SITE =
            Pattern.compile("\\bnew\\s+(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*" + EXCEPTION_NAMES + "\\s*\\(");
    private static final Pattern METHOD_REFERENCE = Pattern.compile("\\b" + EXCEPTION_NAMES + "\\s*::\\s*new\\b");
    private static final Pattern SUBCLASS_DECLARATION =
            Pattern.compile("\\bclass\\s+\\w+(?:<[^>{]*>)?\\s+extends\\s+(?:[\\w$]+\\.)*" + EXCEPTION_NAMES + "\\b");
    private static final Pattern SUPER_CALL = Pattern.compile("\\bsuper\\s*\\(");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final List<String> NEUTRAL_IDENTIFIERS = List.of(
            "String", "format", "formatted", "valueOf", "true", "false", "null");
    private static final Set<String> CALL_PREFIX_KEYWORDS = Set.of("return", "new", "throw", "else", "case", "yield");
    private static final char BLANK = ' ';

    private ExceptionMessageInputScanner() {
    }

    static List<Site> scan(String file, String source) {
        String code = blankCommentsAndLiterals(source, false);
        String codeWithLiterals = blankCommentsAndLiterals(source, true);
        List<Site> flagged = new ArrayList<>();
        collectLastArgumentSites(file, source, code, codeWithLiterals, THROW_SITE, flagged);
        if (SUBCLASS_DECLARATION.matcher(code).find()) {
            collectLastArgumentSites(file, source, code, codeWithLiterals, SUPER_CALL, flagged);
        }
        Matcher references = METHOD_REFERENCE.matcher(code);
        while (references.find()) {
            flagged.add(new Site(file, lineOf(source, references.start()), METHOD_REFERENCE_MESSAGE));
        }
        flagged.sort(Comparator.comparingInt(Site::line));
        return flagged;
    }

    /**
     * Calls of {@code helperNames} (method or constructor names; declarations skipped) whose LAST argument is not a
     * constant. Forwarding {@code forwardedParam} unchanged is allowed only inside the body of one of the helpers
     * themselves, since those bodies' own callers are checked here too.
     */
    static HelperCalls scanHelperCalls(String file, String source, Set<String> helperNames, String forwardedParam) {
        String code = blankCommentsAndLiterals(source, false);
        String codeWithLiterals = blankCommentsAndLiterals(source, true);
        Pattern call = Pattern.compile("\\b(" + String.join("|", helperNames) + ")\\s*\\(");
        List<int[]> helperBodies = new ArrayList<>();
        List<int[]> calls = new ArrayList<>();
        Matcher matcher = call.matcher(code);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = matchingParen(code, open);
            if (close < 0) {
                continue;
            }
            if (isDeclaration(code, matcher.start())) {
                int bodyOpen = code.indexOf('{', close);
                int semicolon = code.indexOf(';', close);
                if (bodyOpen >= 0 && (semicolon < 0 || bodyOpen < semicolon)) {
                    helperBodies.add(new int[] {bodyOpen, matchingBrace(code, bodyOpen)});
                }
            } else {
                calls.add(new int[] {matcher.start(), open, close});
            }
        }
        List<Site> flagged = new ArrayList<>();
        for (int[] c : calls) {
            int argumentStart = lastTopLevelArgumentStart(code, c[1], c[2]);
            String argument = code.substring(argumentStart, c[2]).strip();
            boolean forwarded = argument.equals(forwardedParam) && insideAny(helperBodies, c[0]);
            if (!forwarded && referencesNonConstant(argument)) {
                String text = codeWithLiterals.substring(argumentStart, c[2]).strip().replaceAll("\\s+", " ");
                flagged.add(new Site(file, lineOf(source, c[0]), text));
            }
        }
        return new HelperCalls(calls.size(), flagged);
    }

    private static void collectLastArgumentSites(
            String file, String source, String code, String codeWithLiterals, Pattern opener, List<Site> flagged) {
        Matcher matcher = opener.matcher(code);
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = matchingParen(code, open);
            if (close < 0) {
                continue;
            }
            int messageStart = lastTopLevelArgumentStart(code, open, close);
            if (referencesNonConstant(code.substring(messageStart, close))) {
                String message = codeWithLiterals.substring(messageStart, close).strip().replaceAll("\\s+", " ");
                flagged.add(new Site(file, lineOf(source, matcher.start()), message));
            }
        }
    }

    /** A declaration's name follows a type token ({@code void}, {@code String}, {@code T}, {@code public}...). */
    private static boolean isDeclaration(String code, int nameStart) {
        int i = nameStart - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        char previous = code.charAt(i);
        if (previous == ']') {
            return true;
        }
        if (previous == '>') {
            return !isLambdaArrow(code, i) && !isExplicitTypeArguments(code, i);
        }
        if (!Character.isJavaIdentifierPart(previous)) {
            return false;
        }
        int wordEnd = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) {
            i--;
        }
        return !CALL_PREFIX_KEYWORDS.contains(code.substring(i + 1, wordEnd));
    }

    /** {@code v -> helper(...)}: the {@code >} closes an arrow, so the name is a call. */
    private static boolean isLambdaArrow(String code, int closeAngle) {
        return closeAngle > 0 && code.charAt(closeAngle - 1) == '-';
    }

    /** {@code Foo.<T>helper(...)}: type arguments after a {@code .} make the name a call, not a declaration. */
    private static boolean isExplicitTypeArguments(String code, int closeAngle) {
        int depth = 0;
        for (int i = closeAngle; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '>') {
                depth++;
            } else if (c == '<' && --depth == 0) {
                int j = i - 1;
                while (j >= 0 && Character.isWhitespace(code.charAt(j))) {
                    j--;
                }
                return j >= 0 && code.charAt(j) == '.';
            }
        }
        return false;
    }

    private static boolean insideAny(List<int[]> ranges, int offset) {
        return ranges.stream().anyMatch(range -> offset > range[0] && offset < range[1]);
    }

    private static int matchingBrace(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    static boolean referencesNonConstant(String messageCode) {
        Matcher identifiers = IDENTIFIER.matcher(messageCode);
        while (identifiers.find()) {
            String token = identifiers.group();
            boolean qualifier = nextNonSpace(messageCode, identifiers.end()) == '.'
                    && Character.isUpperCase(token.charAt(0));
            if (!qualifier && !NEUTRAL_IDENTIFIERS.contains(token) && !CONSTANT.matcher(token).matches()) {
                return true;
            }
        }
        return false;
    }

    private static char nextNonSpace(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return text.charAt(i);
            }
        }
        return BLANK;
    }

    private static int matchingParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int lastTopLevelArgumentStart(String code, int open, int close) {
        int depth = 0;
        int start = open + 1;
        for (int i = open + 1; i < close; i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                start = i + 1;
            }
        }
        return start;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Same length as {@code source}, newlines kept: comments always blanked; string/char literal and text-block
     * CONTENTS blanked (quotes kept) unless {@code keepLiterals}.
     */
    static String blankCommentsAndLiterals(String source, boolean keepLiterals) {
        StringBuilder out = new StringBuilder(source);
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = blank(out, i, end < 0 ? n : end);
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = blank(out, i, end < 0 ? n : end + 2);
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end;
                if (!keepLiterals) {
                    blank(out, i + 3, stop);
                }
                i = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                int end = literalEnd(source, i, c);
                if (!keepLiterals) {
                    blank(out, i + 1, end);
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static int literalEnd(String source, int openQuote, char quote) {
        int i = openQuote + 1;
        while (i < source.length() && source.charAt(i) != quote) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i, source.length() - 1);
    }

    private static int blank(StringBuilder out, int from, int to) {
        for (int i = from; i < to; i++) {
            if (out.charAt(i) != '\n') {
                out.setCharAt(i, BLANK);
            }
        }
        return to;
    }
}
