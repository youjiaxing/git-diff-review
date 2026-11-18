package io.github.youjiaxing.diffnav;

import com.intellij.openapi.vcs.ex.Range;
import java.util.*;

/** Projects HEAD→INDEX state onto LOCAL without inheriting a combined native block's flags. */
final class SourceMarkerRanges {
    static final class Mark extends Range {
        final boolean staged, unstaged;
        Mark(int start, int end, byte type, boolean staged, boolean unstaged) {
            super(start, end, 0, type == INSERTED ? 0 : Math.max(1, end - start));
            this.staged = staged; this.unstaged = unstaged;
        }
    }
    private record State(byte type, boolean staged, boolean unstaged) {}

    static List<Mark> build(int indexLines, int localLines, List<SelectedLines.Change> staged, List<SelectedLines.Change> local) {
        byte[] stagedTypes = new byte[indexLines];
        for (var c : staged) {
            byte type = c.baseStart() == c.baseEnd() ? Range.INSERTED : Range.MODIFIED;
            Arrays.fill(stagedTypes, c.desiredStart(), c.desiredEnd(), type);
        }
        State[] rows = new State[localLines];
        var points = new TreeMap<Integer, State>();
        int fromIndex = 0, fromLocal = 0;
        for (var c : local) {
            unchanged(rows, stagedTypes, fromIndex, fromLocal, c.desiredStart() - fromLocal);
            boolean hasStaged = false;
            for (int k = c.baseStart(); k < c.baseEnd(); k++) hasStaged |= stagedTypes[k] != Range.EQUAL;
            int oldCount = c.baseEnd() - c.baseStart(), newCount = c.desiredEnd() - c.desiredStart();
            if (newCount == 0) mergePoint(points, c.desiredStart(), new State(Range.DELETED, hasStaged, true));
            else for (int line = c.desiredStart(); line < c.desiredEnd(); line++) {
                // Equal-sized replacements preserve per-line staging membership. Otherwise the changed hunk is indivisible.
                byte prior = oldCount == newCount ? stagedTypes[c.baseStart() + line - c.desiredStart()]
                        : hasStaged && allInserted(stagedTypes, c.baseStart(), c.baseEnd()) ? Range.INSERTED : Range.EQUAL;
                boolean isStaged = oldCount == newCount ? prior != Range.EQUAL : hasStaged;
                byte type = isStaged && prior != Range.EQUAL ? prior : oldCount == 0 ? Range.INSERTED : Range.MODIFIED;
                rows[line] = new State(type, isStaged, true);
            }
            fromIndex = c.baseEnd(); fromLocal = c.desiredEnd();
        }
        unchanged(rows, stagedTypes, fromIndex, fromLocal, localLines - fromLocal);
        for (var c : staged) if (c.desiredStart() == c.desiredEnd()) {
            int at = projectBoundary(c.desiredStart(), local);
            boolean overlapping = false;
            for (var l : local) overlapping |= l.baseStart() < c.desiredStart() && c.desiredStart() < l.baseEnd();
            mergePoint(points, at, new State(Range.DELETED, true, overlapping));
        }
        var result = new ArrayList<Mark>();
        for (int start = 0; start < rows.length;) {
            State state = rows[start]; int end = start + 1;
            while (end < rows.length && Objects.equals(state, rows[end])) end++;
            if (state != null) result.add(new Mark(start, end, state.type, state.staged, state.unstaged));
            start = end;
        }
        points.forEach((line, state) -> result.add(new Mark(line, line, Range.DELETED, state.staged, state.unstaged)));
        result.sort(Comparator.comparingInt(Range::getLine1).thenComparingInt(Range::getLine2));
        return List.copyOf(result);
    }

    private static boolean allInserted(byte[] types, int start, int end) {
        for (int k = start; k < end; k++) if (types[k] != Range.INSERTED) return false;
        return start < end;
    }

    // ComparisonManager includes an empty logical line for empty text and after a terminal LF.
    static int lineCount(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    private static void unchanged(State[] rows, byte[] staged, int fromIndex, int fromLocal, int count) {
        for (int k = 0; k < count; k++) if (staged[fromIndex + k] != Range.EQUAL)
            rows[fromLocal + k] = new State(staged[fromIndex + k], true, false);
    }
    private static void mergePoint(Map<Integer, State> points, int line, State state) {
        points.merge(line, state, (a, b) -> new State(Range.DELETED, a.staged || b.staged, a.unstaged || b.unstaged));
    }
    private static int projectBoundary(int indexLine, List<SelectedLines.Change> changes) {
        int delta = 0;
        for (var c : changes) {
            if (indexLine < c.baseStart()) break;
            if (indexLine < c.baseEnd()) return c.desiredStart();
            delta = c.desiredEnd() - c.baseEnd();
        }
        return indexLine + delta;
    }
}
