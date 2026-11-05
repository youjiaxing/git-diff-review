package io.github.youjiaxing.diffnav;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.util.TextRange;

/** A live position in its document, or an unambiguous context match in a new version. */
final class NavigationPosition implements AutoCloseable {
    private final RangeMarker marker;
    private final String token;
    private final String context;
    private final int relativeOffset;
    private final String lineText;
    private final int column;
    private final boolean uniqueContext;
    private final boolean uniqueLine;

    private NavigationPosition(RangeMarker marker, String token, String context, int relativeOffset, String lineText, int column,
                               boolean uniqueContext, boolean uniqueLine) {
        this.marker = marker;
        this.token = token;
        this.context = context;
        this.relativeOffset = relativeOffset;
        this.lineText = lineText;
        this.column = column;
        this.uniqueContext = uniqueContext;
        this.uniqueLine = uniqueLine;
    }

    static NavigationPosition capture(Document document, int offset) {
        offset = Math.clamp(offset, 0, document.getTextLength());
        int end = offset;
        var text = document.getImmutableCharSequence();
        while (end < Math.min(text.length(), offset + 128) && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        String token = document.getText(new TextRange(offset, end));
        int line = document.getLineNumber(offset);
        int start = document.getLineStartOffset(Math.max(0, line - 1));
        int stop = document.getLineEndOffset(Math.min(document.getLineCount() - 1, line + 1));
        // Bound retained context even for generated files with extremely long lines.
        start = Math.max(start, offset - 512);
        stop = Math.min(stop, Math.max(end, offset + 512));
        String context = document.getText(new TextRange(start, stop));
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        String lineText = lineEnd - lineStart <= 1024 ? document.getText(new TextRange(lineStart, lineEnd)) : "";
        String fullText = document.getText();
        return new NavigationPosition(document.createRangeMarker(offset, end), token, context, offset - start, lineText, offset - lineStart,
                unique(fullText, context), unique(fullText, lineText));
    }

    NavigationPosition copy() {
        RangeMarker duplicate = marker != null && marker.isValid()
                ? marker.getDocument().createRangeMarker(marker.getStartOffset(), marker.getEndOffset())
                : null;
        return new NavigationPosition(duplicate, token, context, relativeOffset, lineText, column, uniqueContext, uniqueLine);
    }

    int resolve(Document document) {
        if (marker == null || !marker.isValid()) return -1;
        if (marker.getDocument() == document) {
            return token.equals(document.getText(marker.getTextRange())) ? marker.getStartOffset() : -1;
        }
        if (context.isBlank()) return -1;
        String text = document.getText();
        int match = text.indexOf(context);
        if (uniqueContext && match >= 0 && text.indexOf(context, match + 1) < 0) return match + relativeOffset;
        // A nearby insertion may change the context while leaving the exact symbol line intact.
        // Only a unique whole line with a nontrivial identifier can bridge versions.
        if (!uniqueLine || token.length() < 2 || lineText.isBlank()) return -1;
        int found = -1;
        for (int at = text.indexOf(lineText); at >= 0; at = text.indexOf(lineText, at + 1)) {
            int end = at + lineText.length();
            if ((at == 0 || text.charAt(at - 1) == '\n') && (end == text.length() || text.charAt(end) == '\n')) {
                if (found >= 0) return -1;
                found = at;
            }
        }
        return found < 0 ? -1 : found + column;
    }

    private static boolean unique(String text, String fragment) {
        if (fragment.isBlank()) return false;
        int first = text.indexOf(fragment);
        return first >= 0 && text.indexOf(fragment, first + 1) < 0;
    }

    @Override public void close() { if (marker != null) marker.dispose(); }
}
