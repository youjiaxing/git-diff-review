package io.github.youjiaxing.diffnav;

import java.util.List;

public final class SelectedLinesTest {
    private static int passed;
    public static void main(String[] args) {
        var added = List.of(new SelectedLines.Change(1, 1, 1, 4));
        var a = SelectedLines.apply("a\nz\n", true, "a\nx\ny\nw\nz\n", true, added, true, 2, 3);
        check(a.text().equals("a\ny\nz\n") && !a.needsPreview(), "only selected added line");
        var removed = SelectedLines.apply("a\nx\ny\nw\nz\n", true, "a\nz\n", true,
                List.of(new SelectedLines.Change(1, 4, 1, 1)), false, 2, 3);
        check(removed.text().equals("a\nx\nw\nz\n"), "only selected deleted line");
        var replace = List.of(new SelectedLines.Change(0, 2, 0, 2));
        var r = SelectedLines.apply("a\nb\n", true, "A\nB\n", true, replace, true, 0, 1);
        check(r.text().equals("A\nb\n") && r.needsPreview(), "partial replacement needs preview");
        check(!SelectedLines.apply("a\nb\n", true, "A\nB\n", true, replace, true, 0, 2).needsPreview(), "whole selected replacement is exact");
        var complex = SelectedLines.apply("a\nb\n", true, "v\nA\nB\n", true,
                List.of(new SelectedLines.Change(0, 2, 0, 3)), true, 1, 2);
        check(complex.needsPreview() && complex.text().equals("A\na\nb\n"), "ambiguous replacement preserves originals for editable preview");
        check(!SelectedLines.apply("a\n", true, "b\n", true, List.of(new SelectedLines.Change(0, 1, 0, 1)), true, 0, 0).changed(), "empty selection is a no-op");
        var delete = List.of(new SelectedLines.Change(0, 1, 0, 0));
        check(!SelectedLines.apply("a\n", true, "", false, delete, false, 0, 1).exists(), "delete removes index entry");
        check(SelectedLines.apply("a\n", true, "", true, delete, false, 0, 1).exists(), "cleared existing file remains empty file");
        var partialNew = SelectedLines.apply("", false, "a\nb\n", true, List.of(new SelectedLines.Change(0, 0, 0, 2)), true, 1, 2);
        check(partialNew.exists() && partialNew.text().equals("b\n"), "partial new file creates only selected contents");
        check(SelectedLines.apply("a\nb", true, "a\nB", true, List.of(new SelectedLines.Change(1, 2, 1, 2)), true, 1, 2).text().equals("a\nB"), "no final newline is preserved");
        check(SelectedLines.apply("", false, "a", true, List.of(new SelectedLines.Change(0, 1, 0, 1)), true, 0, 1).text().equals("a"), "SDK virtual empty line supports new file without newline");
        check(!SelectedLines.apply("a", true, "", false, List.of(new SelectedLines.Change(0, 1, 0, 1)), false, 0, 1).exists(), "SDK virtual empty line supports deletion without newline");
        check(SelectedLines.apply("a\n", true, "a", true, List.of(new SelectedLines.Change(1, 2, 1, 1)), false, 0, 1).text().equals("a"), "removing final newline applies to last actual line");
        check(SelectedLines.apply("a", true, "a\n", true, List.of(new SelectedLines.Change(1, 1, 1, 2)), true, 0, 1).text().equals("a\n"), "adding final newline applies to last actual line");
        System.out.println("SelectedLinesTest: " + passed + " checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }
}
