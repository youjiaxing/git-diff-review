package io.github.youjiaxing.diffnav;

import java.util.List;

/** Maps source selections only through unchanged, unambiguous index lines. */
final class SourceLineMapping {
    record Range(int start, int end) {}

    static Range toIndex(String index, String local, List<SelectedLines.Change> changes, int start, int end) {
        if (start < 0 || end <= start) return null;
        var lines = SelectedLines.lines(local);
        if (end > lines.size()) return null;
        if (index.equals(local)) return new Range(start, end);
        int first = -1;
        for (int line = start; line < end; line++) {
            int mapped = unchangedIndexLine(changes, line);
            if (mapped < 0 || first >= 0 && mapped != first + line - start) return null;
            if (first < 0) first = mapped;
        }
        String fragment = String.join("", lines.subList(start, end));
        // Repeated text cannot establish which index occurrence the user meant after editing.
        if (!uniqueLines(index, fragment) || !uniqueLines(local, fragment)) return null;
        return new Range(first, first + end - start);
    }

    static int unchangedIndexLine(List<SelectedLines.Change> changes, int localLine) {
        int delta = 0;
        for (var change : changes) {
            if (localLine < change.desiredStart()) break;
            if (localLine < change.desiredEnd()) return -1;
            delta = change.baseEnd() - change.desiredEnd();
        }
        return localLine + delta;
    }

    static boolean containsStaged(List<SelectedLines.Change> localChanges, List<SelectedLines.Change> stagedChanges, int start, int end) {
        for (int line = start; line < end; line++) {
            int mapped = unchangedIndexLine(localChanges, line);
            for (var staged : stagedChanges) {
                if (mapped >= staged.baseStart() && mapped < staged.baseEnd()) return true;
                if (staged.baseStart() == staged.baseEnd() && mapped == staged.baseStart()) return true;
                if (mapped < 0) for (var local : localChanges) {
                    if (line < local.desiredStart() || line >= local.desiredEnd()) continue;
                    if (Math.max(local.baseStart(), staged.baseStart()) < Math.min(local.baseEnd(), staged.baseEnd())) return true;
                    if (staged.baseStart() == staged.baseEnd() && staged.baseStart() >= local.baseStart() && staged.baseStart() < local.baseEnd()) return true;
                    if (local.baseStart() == local.baseEnd() && staged.baseStart() == staged.baseEnd() && local.baseStart() == staged.baseStart()) return true;
                }
            }
        }
        return false;
    }

    private static boolean uniqueLines(String text, String fragment) {
        if (fragment.isBlank()) return false;
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) {
            int stop = at + fragment.length();
            if ((at == 0 || text.charAt(at - 1) == '\n')
                    && (fragment.endsWith("\n") || stop == text.length() || text.charAt(stop) == '\n')) count++;
        }
        return count == 1;
    }
}
