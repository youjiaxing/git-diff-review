package io.github.youjiaxing.diffnav;

import java.util.ArrayList;
import java.util.List;

/** Repairs a coarse replacement when a unique leading line anchors adjacent unchanged context.
 * Does not infer syntax or pair arbitrary lines by ordinal position. */
final class SelectedLineAlignment {
    private static final int MAX_CANDIDATES = 256, MAX_LINE = 512;
    private int work = 1_000_000;

    static List<SelectedLines.Change> refine(List<String> old, List<String> wanted, List<SelectedLines.Change> changes,
                                              boolean selectDesired, int start, int end, boolean newlineTail) {
        var alignment = new SelectedLineAlignment();
        var result = new ArrayList<SelectedLines.Change>();
        for (int i = 0; i < changes.size(); i++) {
            var c = changes.get(i);
            int a = selectDesired ? c.desiredStart() : c.baseStart();
            int b = selectDesired ? c.desiredEnd() : c.baseEnd();
            // A complete native block already has an exact interpretation; preserve it.
            if (start <= a && end >= b) { result.add(c); continue; }
            int oldCount = c.baseEnd() - c.baseStart(), newCount = c.desiredEnd() - c.desiredStart();
            if (oldCount > 0 && newCount > 0 && oldCount != newCount) {
                // Analyze a synthetic EOF change jointly only if realignment succeeds; otherwise retain both native blocks.
                if (newlineTail && i == changes.size() - 2) {
                    var tail = changes.getLast();
                    var joined = new SelectedLines.Change(c.baseStart(), tail.baseEnd(), c.desiredStart(), tail.desiredEnd());
                    var split = alignment.split(old, wanted, joined, old.size(), wanted.size());
                    if (split != null) { result.addAll(split); i++; continue; }
                }
                int oldLimit = i + 1 < changes.size() ? changes.get(i + 1).baseStart() : old.size();
                int newLimit = i + 1 < changes.size() ? changes.get(i + 1).desiredStart() : wanted.size();
                var split = alignment.split(old, wanted, c, oldLimit, newLimit);
                if (split != null) { result.addAll(split); continue; }
            }
            result.add(c);
        }
        return result;
    }

    private List<SelectedLines.Change> split(List<String> old, List<String> wanted, SelectedLines.Change c, int oldEnd, int newEnd) {
        if (c.baseEnd() - c.baseStart() > MAX_CANDIDATES || c.desiredEnd() - c.desiredStart() > MAX_CANDIDATES) return null;
        String oldAnchor = old.get(c.baseStart()), newAnchor = wanted.get(c.desiredStart());
        if (!similar(oldAnchor, newAnchor)) return null;
        // A second plausible counterpart on either side invalidates the leading anchor.
        for (int k = c.desiredStart() + 1; k < c.desiredEnd(); k++) if (similar(oldAnchor, wanted.get(k))) return null;
        for (int k = c.baseStart() + 1; k < c.baseEnd(); k++) if (similar(newAnchor, old.get(k))) return null;
        if (work < 0) return null;
        int a = c.baseStart() + 1, b = c.desiredStart() + 1;
        int adjacent = 0;
        while (a < oldEnd && b < newEnd && old.get(a).equals(wanted.get(b))) { a++; b++; adjacent++; }
        // At least one unchanged physical line must establish where the original continuation belongs.
        if (adjacent == 0) return null;
        while (oldEnd > a && newEnd > b && old.get(oldEnd - 1).equals(wanted.get(newEnd - 1))) { oldEnd--; newEnd--; }
        // Only separate a replacement from an independently located insertion/deletion.
        if (a != oldEnd && b != newEnd) return null;
        var result = new ArrayList<SelectedLines.Change>();
        result.add(new SelectedLines.Change(c.baseStart(), c.baseStart() + 1, c.desiredStart(), c.desiredStart() + 1));
        if (a != oldEnd || b != newEnd) result.add(new SelectedLines.Change(a, oldEnd, b, newEnd));
        return result;
    }

    private boolean similar(String left, String right) {
        left = left.strip(); right = right.strip();
        if (left.length() > MAX_LINE || right.length() > MAX_LINE) return false;
        int prefix = 0, letters = 0;
        while (prefix < Math.min(left.length(), right.length()) && left.charAt(prefix) == right.charAt(prefix)) {
            if (Character.isLetter(left.charAt(prefix))) letters++;
            prefix++;
        }
        if (prefix < 8 || letters < 6) return false;
        int max = Math.max(left.length(), right.length());
        if (Math.abs(left.length() - right.length()) > max / 3) return false;
        int cost = left.length() * right.length();
        work -= cost;
        if (work < 0) return false;
        int[] previous = new int[right.length() + 1], next = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            next[0] = i;
            for (int j = 1; j <= right.length(); j++) next[j] = Math.min(Math.min(previous[j] + 1, next[j - 1] + 1),
                    previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            int[] swap = previous; previous = next; next = swap;
        }
        return previous[right.length()] <= max / 3;
    }
}
