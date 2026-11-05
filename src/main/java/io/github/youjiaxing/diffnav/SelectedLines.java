package io.github.youjiaxing.diffnav;

import java.util.ArrayList;
import java.util.List;

/** Applies selected line changes to the index version, never the worktree. */
public final class SelectedLines {
    public record Change(int baseStart, int baseEnd, int desiredStart, int desiredEnd) {}
    public record Result(String text, boolean exists, boolean needsPreview, boolean changed, List<Integer> baseOrigins) {}

    public static Result apply(String base, boolean baseExists, String desired, boolean desiredExists,
                               List<Change> changes, boolean selectDesired, int start, int end) {
        if (start < 0 || end <= start) return new Result(base, baseExists, false, false, List.of());
        List<String> old = lines(base), wanted = lines(desired);
        var result = new ArrayList<>(old);
        var origins = new ArrayList<Integer>();
        for (int i = 0; i < old.size(); i++) origins.add(i);
        var effective = new ArrayList<Change>();
        for (Change c : changes) {
            c = new Change(Math.min(c.baseStart, old.size()), Math.min(c.baseEnd, old.size()),
                    Math.min(c.desiredStart, wanted.size()), Math.min(c.desiredEnd, wanted.size()));
            if (c.baseStart == c.baseEnd && c.desiredStart == c.desiredEnd && !old.isEmpty() && !wanted.isEmpty()
                    && base.endsWith("\n") != desired.endsWith("\n")) {
                c = new Change(old.size() - 1, old.size(), wanted.size() - 1, wanted.size());
            }
            effective.add(c);
        }
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
                // Present a conservative, editable proposal: retain unselected original lines.
                if (selectDesired) {
                    result.addAll(c.baseStart, wanted.subList(lo, hi));
                    origins.addAll(c.baseStart, java.util.Collections.nCopies(hi - lo, -1));
                } else { result.subList(lo, hi).clear(); origins.subList(lo, hi).clear(); }
                preview = true;
            }
        }
        String text = join(result);
        boolean exists = !text.isEmpty() || (text.equals(desired) ? desiredExists : baseExists);
        return new Result(text, exists, preview, !text.equals(base) || exists != baseExists, List.copyOf(origins));
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
