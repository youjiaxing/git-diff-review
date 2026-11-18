package io.github.youjiaxing.diffnav;

import java.util.List;

public final class SourceLineMappingTest {
    public static void main(String[] args) {
        check(SourceLineMapping.toIndex("same\nsame\n", "same\nsame\n", List.of(), 1, 2), 1, 2);
        var insertion = List.of(new SelectedLines.Change(1, 1, 1, 2));
        check(SourceLineMapping.toIndex("before\nstaged\nafter\n", "before\nnew\nstaged\nafter\n", insertion, 2, 3), 1, 2);
        reject(SourceLineMapping.toIndex("before\nstaged\n", "before\nnew\nstaged\n", insertion, 1, 3));
        var replacement = List.of(new SelectedLines.Change(1, 2, 1, 2));
        reject(SourceLineMapping.toIndex("before\nstaged\n", "before\nnew\n", replacement, 1, 2));
        var deletion = List.of(new SelectedLines.Change(1, 2, 1, 1));
        check(SourceLineMapping.toIndex("before\ndeleted\nstaged\n", "before\nstaged\n", deletion, 1, 2), 2, 3);
        reject(SourceLineMapping.toIndex("same\nsame\n", "new\nsame\nsame\n", List.of(new SelectedLines.Change(0, 0, 0, 1)), 1, 2));
        var staged = List.of(new SelectedLines.Change(1, 2, 1, 1));
        if (!SourceLineMapping.containsStaged(replacement, staged, 1, 2)) throw new AssertionError("overlapping edit must reach staged diff");
        if (SourceLineMapping.containsStaged(insertion, staged, 1, 2)) throw new AssertionError("local-only insertion is not staged");
        if (!SourceLineMapping.containsStaged(insertion, staged, 2, 3)) throw new AssertionError("shifted staged line must stay staged");
        System.out.println("SourceLineMappingTest: PASS");
    }
    private static void check(SourceLineMapping.Range value, int start, int end) {
        if (value == null || value.start() != start || value.end() != end) throw new AssertionError("Unexpected mapped range: " + value);
    }
    private static void reject(SourceLineMapping.Range value) { if (value != null) throw new AssertionError("Ambiguous selection was mapped: " + value); }
}
