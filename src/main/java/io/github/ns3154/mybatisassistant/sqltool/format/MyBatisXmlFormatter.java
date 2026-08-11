package io.github.ns3154.mybatisassistant.sqltool.format;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 仅调整结构行前导缩进的 MyBatis XML 格式化器。
 *
 * <p>换行、CDATA、注释和多行标签内部文本保持原样；结构不完整时不返回部分结果。</p>
 */
public final class MyBatisXmlFormatter {
    public static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;

    private MyBatisXmlFormatter() {
    }

    public static @NotNull MyBatisXmlFormatResult format(
            @NotNull String xml,
            int indentSize) {
        if (indentSize < 1 || indentSize > 8) {
            return failure(MyBatisXmlFormatDiagnosticCode.INVALID_INDENT_SIZE, 0,
                    "缩进宽度必须在 1 到 8 之间");
        }
        if (xml.length() > MAX_INPUT_BYTES
                || xml.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            return failure(MyBatisXmlFormatDiagnosticCode.INPUT_TOO_LARGE, 0,
                    "XML 超过 2 MiB 本地格式化上限");
        }
        List<SourceLine> lines = lines(xml);
        Scanner scanner = new Scanner();
        StringBuilder formatted = new StringBuilder(xml.length());
        try {
            for (int index = 0; index < lines.size(); index++) {
                ProgressManager.checkCanceled();
                SourceLine line = lines.get(index);
                boolean protectedLine = scanner.mode != Mode.NORMAL;
                String body = line.content().stripLeading();
                if (protectedLine || body.isBlank()) {
                    formatted.append(line.content());
                } else {
                    int depth = scanner.depth();
                    if (body.startsWith("</")) {
                        depth--;
                    }
                    formatted.append(" ".repeat(Math.max(0, depth) * indentSize))
                            .append(body);
                }
                formatted.append(line.separator());
                scanner.scan(line.content(), index + 1);
            }
            scanner.finish(lines.size());
            return new MyBatisXmlFormatResult.Success(formatted.toString());
        } catch (MalformedXmlException malformed) {
            return failure(MyBatisXmlFormatDiagnosticCode.MALFORMED_XML,
                    malformed.lineNumber, malformed.getMessage());
        }
    }

    private static MyBatisXmlFormatResult.Failure failure(
            MyBatisXmlFormatDiagnosticCode code,
            int lineNumber,
            String message) {
        return new MyBatisXmlFormatResult.Failure(code, lineNumber, message);
    }

    private static List<SourceLine> lines(String text) {
        List<SourceLine> lines = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current != '\r' && current != '\n') {
                continue;
            }
            int end = index + 1;
            if (current == '\r' && end < text.length() && text.charAt(end) == '\n') {
                end++;
            }
            lines.add(new SourceLine(text.substring(start, index), text.substring(index, end)));
            start = end;
            index = end - 1;
        }
        if (start < text.length() || text.isEmpty()) {
            lines.add(new SourceLine(text.substring(start), ""));
        }
        return lines;
    }

    private record SourceLine(String content, String separator) {
    }

    private enum Mode {
        NORMAL,
        TAG,
        COMMENT,
        CDATA,
        PROCESSING_INSTRUCTION,
        DECLARATION
    }

    private static final class Scanner {
        private final Deque<String> elements = new ArrayDeque<>();
        private Mode mode = Mode.NORMAL;
        private String tagName;
        private boolean closingTag;
        private char quote;
        private char lastTagCharacter;
        private int declarationBrackets;

        private int depth() {
            return elements.size();
        }

        private void scan(String line, int lineNumber) throws MalformedXmlException {
            int index = 0;
            while (index < line.length()) {
                if ((index & 255) == 0) {
                    ProgressManager.checkCanceled();
                }
                switch (mode) {
                    case NORMAL -> index = scanNormal(line, index, lineNumber);
                    case TAG -> index = scanTag(line, index, lineNumber);
                    case COMMENT -> index = closeOpaque(line, index, "-->", Mode.COMMENT);
                    case CDATA -> index = closeOpaque(line, index, "]]>", Mode.CDATA);
                    case PROCESSING_INSTRUCTION -> index = closeOpaque(
                            line, index, "?>", Mode.PROCESSING_INSTRUCTION);
                    case DECLARATION -> index = scanDeclaration(line, index);
                    default -> throw new IllegalStateException("未识别的 XML 扫描状态");
                }
            }
        }

        private int scanNormal(String line, int index, int lineNumber)
                throws MalformedXmlException {
            int opening = line.indexOf('<', index);
            if (opening < 0) {
                return line.length();
            }
            if (line.startsWith("<!--", opening)) {
                mode = Mode.COMMENT;
                return opening + 4;
            }
            if (line.startsWith("<![CDATA[", opening)) {
                mode = Mode.CDATA;
                return opening + 9;
            }
            if (line.startsWith("<?", opening)) {
                mode = Mode.PROCESSING_INSTRUCTION;
                return opening + 2;
            }
            if (line.startsWith("<!", opening)) {
                mode = Mode.DECLARATION;
                quote = 0;
                declarationBrackets = 0;
                return opening + 2;
            }
            closingTag = line.startsWith("</", opening);
            int nameStart = opening + (closingTag ? 2 : 1);
            int nameEnd = nameStart;
            while (nameEnd < line.length() && isNameCharacter(line.charAt(nameEnd))) {
                nameEnd++;
            }
            if (nameEnd == nameStart) {
                throw malformed(lineNumber, "XML 标签缺少合法名称");
            }
            tagName = line.substring(nameStart, nameEnd);
            quote = 0;
            lastTagCharacter = 0;
            mode = Mode.TAG;
            return nameEnd;
        }

        private int scanTag(String line, int index, int lineNumber)
                throws MalformedXmlException {
            for (int cursor = index; cursor < line.length(); cursor++) {
                char current = line.charAt(cursor);
                if (quote != 0) {
                    if (current == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (current == '\'' || current == '"') {
                    quote = current;
                } else if (current == '>') {
                    completeTag(lineNumber);
                    return cursor + 1;
                } else if (!Character.isWhitespace(current)) {
                    lastTagCharacter = current;
                }
            }
            return line.length();
        }

        private void completeTag(int lineNumber) throws MalformedXmlException {
            if (closingTag) {
                if (elements.isEmpty() || !elements.getLast().equals(tagName)) {
                    throw malformed(lineNumber, "XML 结束标签与当前结构不匹配：" + tagName);
                }
                elements.removeLast();
            } else if (lastTagCharacter != '/') {
                elements.addLast(tagName);
            }
            mode = Mode.NORMAL;
            tagName = null;
            closingTag = false;
            lastTagCharacter = 0;
        }

        private int closeOpaque(String line, int index, String delimiter, Mode expected) {
            int closing = line.indexOf(delimiter, index);
            if (closing < 0) {
                return line.length();
            }
            if (mode != expected) {
                throw new IllegalStateException("XML 不透明区状态异常");
            }
            mode = Mode.NORMAL;
            return closing + delimiter.length();
        }

        private int scanDeclaration(String line, int index) {
            for (int cursor = index; cursor < line.length(); cursor++) {
                char current = line.charAt(cursor);
                if (quote != 0) {
                    if (current == quote) {
                        quote = 0;
                    }
                } else if (current == '\'' || current == '"') {
                    quote = current;
                } else if (current == '[') {
                    declarationBrackets++;
                } else if (current == ']') {
                    declarationBrackets = Math.max(0, declarationBrackets - 1);
                } else if (current == '>' && declarationBrackets == 0) {
                    mode = Mode.NORMAL;
                    return cursor + 1;
                }
            }
            return line.length();
        }

        private void finish(int lineCount) throws MalformedXmlException {
            if (mode != Mode.NORMAL) {
                throw malformed(lineCount, "XML 包含未闭合的标签、注释、CDATA 或声明");
            }
            if (!elements.isEmpty()) {
                throw malformed(lineCount, "XML 标签未闭合：" + elements.getLast());
            }
        }

        private static boolean isNameCharacter(char character) {
            return Character.isLetterOrDigit(character)
                    || character == '_' || character == ':'
                    || character == '.' || character == '-';
        }

        private static MalformedXmlException malformed(int lineNumber, String message) {
            return new MalformedXmlException(lineNumber, message);
        }
    }

    private static final class MalformedXmlException extends Exception {
        private final int lineNumber;

        private MalformedXmlException(int lineNumber, String message) {
            super(message);
            this.lineNumber = lineNumber;
        }
    }
}
