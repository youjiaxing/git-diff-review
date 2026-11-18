package io.github.youjiaxing.diffnav;

import java.util.ArrayList;
import java.util.List;

/** Applies selected line changes to the index version, never the worktree. */
public final class SelectedLines {
    public record Change(int baseStart, int baseEnd, int desiredStart, int desiredEnd) {}
    public record Result(String text, boolean exists, boolean needsPreview, boolean changed, List<Integer> baseOrigins, boolean unresolved) {}

    /** Apply the one complete native hunk, including any physical EOF line its virtual range represents. */
    public static Result applyHunk(String base, boolean baseExists, String desired, boolean desiredExists, Change change) {
        return apply(base, baseExists, desired, desiredExists, List.of(change),
                change.desiredStart < change.desiredEnd, 0, Integer.MAX_VALUE);
    }

    static Change physicalHunk(String base, String desired, Change change) {
        return normalize(base, desired, lines(base), lines(desired), List.of(change)).ranges.getFirst();
    }

    private record Normalized(List<Change> ranges, boolean newlineTail) {}

    private static Normalized normalize(String base, String desired, List<String> old, List<String> wanted, List<Change> changes) {
        var effective = new ArrayList<Change>();
        boolean newlineTail = false;
        for (Change c : changes) {
            boolean crossedOldTerminal = c.baseStart > old.size(), crossedNewTerminal = c.desiredStart > wanted.size();
            c = new Change(Math.min(c.baseStart, old.size()), Math.min(c.baseEnd, old.size()),
                    Math.min(c.desiredStart, wanted.size()), Math.min(c.desiredEnd, wanted.size()));
            if (c.baseStart == c.baseEnd && c.desiredStart == c.desiredEnd && !old.isEmpty() && !wanted.isEmpty()
                    && base.endsWith("\n") != desired.endsWith("\n")) {
                c = new Change(old.size() - 1, old.size(), wanted.size() - 1, wanted.size());
                newlineTail = true;
            }
            // SDK virtual EOF lines may match physical blank lines beside an insertion/deletion.
            if (crossedOldTerminal && c.baseStart == c.baseEnd && c.baseStart == old.size() && (base.isEmpty() || base.endsWith("\n"))
                    && c.desiredStart > 0 && wanted.get(c.desiredStart - 1).equals("\n"))
                c = new Change(c.baseStart, c.baseEnd, c.desiredStart - 1, c.desiredEnd);
            if (crossedNewTerminal && c.desiredStart == c.desiredEnd && c.desiredStart == wanted.size() && (desired.isEmpty() || desired.endsWith("\n"))
                    && c.baseStart > 0 && old.get(c.baseStart - 1).equals("\n"))
                c = new Change(c.baseStart - 1, c.baseEnd, c.desiredStart, c.desiredEnd);
            effective.add(c);
        }
        return new Normalized(effective, newlineTail);
    }

    public static Result apply(String base, boolean baseExists, String desired, boolean desiredExists,
                               List<Change> changes, boolean selectDesired, int start, int end) {
        if (start < 0 || end <= start) return new Result(base, baseExists, false, false, List.of(), false);
        List<String> old = lines(base), wanted = lines(desired);
        var result = new ArrayList<>(old);
        var origins = new ArrayList<Integer>();
        for (int i = 0; i < old.size(); i++) origins.add(i);
        var normalized = normalize(base, desired, old, wanted, changes);
        var effective = SelectedLineAlignment.refine(old, wanted, normalized.ranges, selectDesired, start, end, normalized.newlineTail);
        boolean preview = false;
        for (int i = effective.size() - 1; i >= 0; i--) {
            Change c = effective.get(i);
            int a = selectDesired ? c.desiredStart : c.baseStart;
            int b = selectDesired ? c.desiredEnd : c.baseEnd;
            int lo = Math.max(start, a), hi = Math.min(end, b);
            if (hi <= lo) continue;
            int oldCount = c.baseEnd - c.baseStart, newCount = c.desiredEnd - c.desiredStart;
            if (lo == a && hi == b) {
                result.subList(c.baseStart, c.baseEnd).clear();
                result.addAll(c.baseStart, wanted.subList(c.desiredStart, c.desiredEnd));
                origins.subList(c.baseStart, c.baseEnd).clear();
                origins.addAll(c.baseStart, java.util.Collections.nCopies(newCount, -1));
            } else if (oldCount == 0) {
                result.addAll(c.baseStart, wanted.subList(lo, hi));
                origins.addAll(c.baseStart, java.util.Collections.nCopies(hi - lo, -1));
            } else if (newCount == 0) {
                result.subList(lo, hi).clear();
                origins.subList(lo, hi).clear();
            } else if (oldCount == newCount) {
                int relative = lo - a;
                result.subList(c.baseStart + relative, c.baseStart + relative + hi - lo).clear();
                result.addAll(c.baseStart + relative, wanted.subList(c.desiredStart + relative, c.desiredStart + relative + hi - lo));
                for (int k = c.baseStart + relative; k < c.baseStart + relative + hi - lo; k++) origins.set(k, -1);
                preview = true; // Equal line counts alone do not establish semantic correspondence.
            } else {
                // No insertion/deletion guess can establish the missing replacement correspondence.
                // Reject the entire selection atomically, including any other selected hunks.
                return new Result(base, baseExists, false, false, List.of(), true);
            }
        }
        String text = join(result);
        boolean exists = !text.isEmpty() || (text.equals(desired) ? desiredExists : baseExists);
        return new Result(text, exists, preview, !text.equals(base) || exists != baseExists, List.copyOf(origins), false);
    }

    static List<String> lines(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') {
            result.add(text.substring(start, i + 1)); start = i + 1;
        }
        if (start < text.length()) result.add(text.substring(start));
        return result;
    }

    private static String join(List<String> lines) {
        var out = new StringBuilder();
        for (String line : lines) {
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append(line);
        }
        return out.toString();
    }
}
