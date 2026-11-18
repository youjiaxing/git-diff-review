package io.github.youjiaxing.diffnav;

import com.intellij.diff.comparison.*;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import java.util.List;

/** Native coarse diff, repeated closing lines and independent method addition. */
public final class SelectedReplacementTest {
    private static int passed;
    private static final String OLD = "func score() {\n    if role != nil {\n        score = role.Achievement(period.RankNo).Score\n    }\n    return\n}\n";
    private static final String EDITED = OLD.replace("role.Achievement(period.RankNo).Score", "role.AchievementScore(period.RankNo)");
    private static final String METHOD = "\n// details\nfunc details() {\n    if role != nil {\n        count = role.PeriodState(rankNo).Counts\n    }\n    return\n}\n";
    public static void main(String[] args) {
        String local = EDITED + METHOD;
        var changes = changes(OLD, local);
        var method = SelectedLines.apply(OLD, true, local, true, changes, true, 6, 15);
        require(method.text().equals(OLD + METHOD), "method-only Stage must append the complete method after the original function");
        require(!method.needsPreview(), "clear method insertion must not require replacement confirmation");
        var call = SelectedLines.apply(OLD, true, local, true, changes, true, 2, 3);
        require(call.text().equals(EDITED) && !call.needsPreview(), "call-only Stage must not include any part of the new method");
        var all = SelectedLines.apply(OLD, true, local, true, changes, true, 0, 15);
        require(all.text().equals(local), "whole replacement stays exact");
        var nativeBlock = changes.getFirst();
        var block = SelectedLines.apply(OLD, true, local, true, changes, true, nativeBlock.desiredStart(), nativeBlock.desiredEnd());
        require(block.text().equals(local), "complete native replacement preserves its exact existing interpretation");
        var leftCall = SelectedLines.apply(OLD, true, local, true, changes, false, 2, 3);
        require(leftCall.text().equals(local), "whole INDEX-side native block retains its existing complete-block meaning");
        var unstageMethod = SelectedLines.apply(local, true, OLD, true, changes(local, OLD), false, 6, 15);
        require(unstageMethod.text().equals(EDITED) && !unstageMethod.needsPreview(), "Unstage removes only added method and retains staged call edit");
        var unstageCall = SelectedLines.apply(local, true, OLD, true, changes(local, OLD), false, 2, 3);
        require(unstageCall.text().equals(OLD + METHOD) && !unstageCall.needsPreview(), "Unstage restores only selected call without removing method");
        var discard = SelectedLines.apply(local, true, OLD, true, changes(local, OLD), false, 6, 15);
        require(discard.text().equals(EDITED), "Discard uses the same located method deletion");
        require(method.baseOrigins().subList(0, 6).equals(List.of(0, 1, 2, 3, 4, 5)), "method insertion retains exact origins of all original lines");
        var partMethod = SelectedLines.apply(OLD, true, local, true, changes, true, 7, 11);
        require(partMethod.text().startsWith(OLD) && !partMethod.text().contains("AchievementScore"), "partial method rows are appended after the original closure, never nested");
        String repeated = EDITED + "\n" + EDITED + METHOD;
        var duplicate = SelectedLines.apply(OLD, true, repeated, true, changes(OLD, repeated), true, 7, 12);
        require(duplicate.unresolved() && !duplicate.changed() && duplicate.text().equals(OLD), "two plausible line counterparts must not be guessed");
        var ambiguous = SelectedLines.apply("a\nb\nkeep\nx\n", true, "v\nA\nB\nkeep\nX\n", true,
                List.of(new SelectedLines.Change(0, 2, 0, 3), new SelectedLines.Change(3, 4, 4, 5)), true, 1, 5);
        require(ambiguous.unresolved() && ambiguous.text().equals("a\nb\nkeep\nx\n") && !ambiguous.changed(), "unresolved range cancels other selected hunks atomically");
        for (boolean newline : new boolean[]{true, false}) {
            String target = newline ? local : local.stripTrailing();
            var selected = SelectedLines.apply(OLD, true, target, true, changes(OLD, target), true, 6, 20);
            require(selected.text().equals(OLD + (newline ? METHOD : METHOD.stripTrailing())), "new method preserves its final newline choice newline=" + newline);
            var reversed = SelectedLines.apply(target, true, OLD, true, changes(target, OLD), false, 6, 20);
            require(reversed.text().equals(EDITED), "method-only Unstage preserves original final newline choice newline=" + newline);
            var onlyCall = SelectedLines.apply(OLD, true, target, true, changes(OLD, target), true, 2, 3);
            require(onlyCall.text().equals(EDITED), "call-only Stage does not borrow the added method's EOF newline");
        }
        String eofBase = "a\nb\nkeep\nz\n", eofDesired = "A\nB\nC\nkeep\nz";
        var eofChanges = changes(eofBase, eofDesired);
        var firstBlock = SelectedLines.apply(eofBase, true, eofDesired, true, eofChanges, true, 0, 3);
        require(firstBlock.text().equals("A\nB\nC\nkeep\nz\n"), "independent EOF change must not block complete native replacement");
        var onlyEof = SelectedLines.apply(eofBase, true, eofDesired, true, eofChanges, true, 4, 5);
        require(onlyEof.text().equals("a\nb\nkeep\nz"), "independent terminal newline remains separately selectable");
        var allEof = SelectedLines.apply(eofBase, true, eofDesired, true, eofChanges, true, 0, 5);
        require(allEof.baseOrigins().get(3) == 2, "unchanged context retains original byte-format origin across EOF selection");
        var append = SelectedLines.apply(EDITED, true, local, true, changes(EDITED, local), true, 6, 20);
        require(append.text().equals(local), "pure EOF insertion preserves selected separator blank line");
        var deleteMethod = SelectedLines.apply(local, true, EDITED, true, changes(local, EDITED), false, 6, 20);
        require(deleteMethod.text().equals(EDITED), "pure EOF method deletion also removes selected separator blank line");
        for (int existing = 0; existing <= 3; existing++) for (int added = 0; added <= 3; added++) {
            String baseline = "a\n" + "\n".repeat(existing);
            String target = baseline + "\n".repeat(added) + "b\n";
            var forward = SelectedLines.apply(baseline, true, target, true, changes(baseline, target), true, 0, 20);
            require(forward.text().equals(target), "EOF additions preserve existing=" + existing + " added=" + added + " blank lines");
            var reverse = SelectedLines.apply(target, true, baseline, true, changes(target, baseline), false, 0, 20);
            require(reverse.text().equals(baseline), "EOF deletion preserves existing=" + existing + " added=" + added + " blank lines");
            if (existing > 0) {
                require(!SelectedLines.apply(baseline, true, target, true, changes(baseline, target), true, 1, 2).changed(), "unchanged EOF blank is not an added row");
                require(!SelectedLines.apply(target, true, baseline, true, changes(target, baseline), false, 1, 2).changed(), "unchanged EOF blank is not a deleted row");
            }
        }
        for (var pair : List.of(new String[]{"a", "a\n"}, new String[]{"a\n", "a\n\nb\n"},
                new String[]{"a\n\nb\n", "a\n"}, new String[]{"", "\n"}, new String[]{"\n", ""})) {
            var hunks = changes(pair[0], pair[1]);
            require(hunks.size() == 1, "EOF fixture contains one native hunk");
            var whole = SelectedLines.applyHunk(pair[0], true, pair[1], true, hunks.getFirst());
            require(whole.changed() && whole.text().equals(pair[1]), "native hunk retains EOF: " + pair[0].replace("\n", "\\n")
                    + " -> " + pair[1].replace("\n", "\\n") + "; actual=" + whole.text().replace("\n", "\\n") + "; hunk=" + hunks);
        }
        var two = changes("a\nold\nkeep\ntail\n", "a\nnew\nkeep\nTAIL\n");
        var one = SelectedLines.applyHunk("a\nold\nkeep\ntail\n", true, "a\nnew\nkeep\nTAIL\n", true, two.getFirst());
        require(one.text().equals("a\nnew\nkeep\ntail\n"), "whole-hunk operation excludes the other local hunk");
        require(one.baseOrigins().equals(List.of(0, -1, 2, 3)), "whole-hunk operation preserves origins outside its actual range");
        var eofDeletion = SelectedLines.physicalHunk("a\n\nb\n", "a\n", changes("a\n\nb\n", "a\n").getFirst());
        require(eofDeletion.equals(new SelectedLines.Change(1, 3, 1, 1)), "EOF pane labels and INDEX selection use physical rows rather than virtual EOF coordinates");
        var undoEof = SelectedLines.apply("a\n\nb\n", true, "a\n", true, changes("a\n\nb\n", "a\n"), false, eofDeletion.baseStart(), eofDeletion.baseEnd());
        require(undoEof.text().equals("a\n"), "Unstage of the displayed EOF deletion removes both staged content and separator blank");
        var emptyDeletion = SelectedLines.physicalHunk("\n", "", changes("\n", "").getFirst());
        require(emptyDeletion.equals(new SelectedLines.Change(0, 1, 0, 0)), "empty LOCAL deletion identifies the staged physical blank row");
        System.out.println("SelectedReplacementTest: " + passed + " checks passed");
    }
    static List<SelectedLines.Change> changes(String before, String after) {
        return new ComparisonManagerImpl().compareLines(before, after, ComparisonPolicy.DEFAULT,
                new EmptyProgressIndicator(ModalityState.nonModal())).stream()
                .map(f -> new SelectedLines.Change(f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2())).toList();
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
