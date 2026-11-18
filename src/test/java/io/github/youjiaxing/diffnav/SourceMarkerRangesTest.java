package io.github.youjiaxing.diffnav;

import java.util.List;
import com.intellij.openapi.vcs.ex.Range;

public final class SourceMarkerRangesTest {
    public static void main(String[] args) {
        var allStaged = List.of(new SelectedLines.Change(0, 0, 0, 60));
        var local = List.of(new SelectedLines.Change(14, 15, 14, 15), new SelectedLines.Change(34, 35, 34, 34));
        var ranges = SourceMarkerRanges.build(60, 59, allStaged, local);
        for (int line = 0; line < 59; line++) {
            var mark = row(ranges, line);
            require(mark != null && mark.staged && mark.unstaged == (line == 14), "mixed state leaked at line " + line);
        }
        require(point(ranges, 34).staged && point(ranges, 34).unstaged, "deleted staged line must remain a mixed point");
        var inserted = SourceMarkerRanges.build(3, 4, List.of(new SelectedLines.Change(0, 0, 0, 3)), List.of(new SelectedLines.Change(1, 1, 1, 2)));
        require(!row(inserted, 1).staged && row(inserted, 1).unstaged, "local-only added line must be unstaged");
        require(row(inserted, 2).staged && !row(inserted, 2).unstaged, "shifted staged line must remain staged");
        var stagedDelete = SourceMarkerRanges.build(2, 2, List.of(new SelectedLines.Change(1, 2, 1, 1)), List.of());
        require(point(stagedDelete, 1).staged && !point(stagedDelete, 1).unstaged && row(stagedDelete, 1) == null, "staged deletion must not color neighbor");
        var localOnly = SourceMarkerRanges.build(3, 2, List.of(), List.of(new SelectedLines.Change(1, 2, 1, 1)));
        require(!point(localOnly, 1).staged && point(localOnly, 1).unstaged, "local-only deletion must be unstaged");
        var adjacent = SourceMarkerRanges.build(3, 3, List.of(new SelectedLines.Change(0, 1, 0, 1)), List.of(new SelectedLines.Change(1, 2, 1, 2)));
        require(row(adjacent, 0).staged && !row(adjacent, 0).unstaged && !row(adjacent, 1).staged, "adjacent independent edits must not share flags");
        require(SourceMarkerRanges.build(0, 0, List.of(), List.of()).isEmpty(), "empty files must have no markers");
        var newline = SourceMarkerRanges.build(2, 1, List.of(), List.of(new SelectedLines.Change(1, 2, 1, 1)));
        require(point(newline, 1).unstaged && row(newline, 0) == null, "terminal document line must not overflow or mark other rows");
        var grown = SourceMarkerRanges.build(3, 4, List.of(new SelectedLines.Change(1, 1, 1, 2)), List.of(new SelectedLines.Change(1, 2, 1, 3)));
        require(row(grown, 1).getType() == Range.INSERTED && row(grown, 2).getType() == Range.INSERTED
                && row(grown, 1).staged && row(grown, 1).unstaged, "unequal replacement of staged inserted lines must retain inserted type");
        var besideDelete = SourceMarkerRanges.build(2, 2, List.of(new SelectedLines.Change(1, 2, 1, 1)), List.of(new SelectedLines.Change(1, 2, 1, 2)));
        require(point(besideDelete, 1).staged && !point(besideDelete, 1).unstaged, "staged deletion must not inherit next row local edit");
        require(!row(besideDelete, 1).staged && row(besideDelete, 1).unstaged, "row after staged deletion remains independently unstaged");
        System.out.println("SourceMarkerRangesTest: line and boundary checks passed");
    }
    private static SourceMarkerRanges.Mark row(List<SourceMarkerRanges.Mark> marks, int line) {
        return marks.stream().filter(m -> m.getLine1() <= line && line < m.getLine2()).findFirst().orElse(null);
    }
    private static SourceMarkerRanges.Mark point(List<SourceMarkerRanges.Mark> marks, int line) {
        return marks.stream().filter(m -> m.getLine1() == line && m.getLine2() == line && m.getType() == Range.DELETED).findFirst().orElseThrow();
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
}
