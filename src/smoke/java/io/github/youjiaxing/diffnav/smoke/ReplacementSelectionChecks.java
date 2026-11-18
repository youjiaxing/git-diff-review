package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import git4idea.index.GitStageTracker;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import io.github.youjiaxing.diffnav.SelectedLines;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Real native Diff toolbar and source actions, using only an isolated fixture repository. */
final class ReplacementSelectionChecks {
    private static final String OLD = "func score() {\n    if role != nil {\n        score = role.Achievement(period.RankNo).Score\n    }\n    return\n}\n";
    private static final String EDITED = OLD.replace("role.Achievement(period.RankNo).Score", "role.AchievementScore(period.RankNo)");
    private static final String METHOD = "\n// details\nfunc details() {\n    if role != nil {\n        count = role.PeriodState(rankNo).Counts\n    }\n    return\n}\n";
    private static final String LOCAL = EDITED + METHOD;
    private final Project project;
    private final BiConsumer<Boolean, String> check;
    private int step, ticks;
    private Editor source;
    private com.intellij.openapi.vfs.VirtualFile file;
    private GitStageDiffRequestProcessor stage;
    ReplacementSelectionChecks(Project project, BiConsumer<Boolean, String> check) { this.project = project; this.check = check; }
    boolean tick() throws Exception {
        if (++ticks > 300) throw new AssertionError("Replacement selection timeout step=" + step + " reason=" + project.getService(io.github.youjiaxing.diffnav.StageNavigationService.class).getLastReason());
        var manager = FileEditorManager.getInstance(project);
        if (step == 0) {
            Files.writeString(path(), OLD); git("add", "--", "replacement-selection.txt"); git("commit", "-m", "replacement fixture", "--only", "--", "replacement-selection.txt");
            Files.writeString(path(), LOCAL);
            file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path());
            source = manager.openTextEditor(new OpenFileDescriptor(project, file), true);
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(file);
            GitStageTracker.getInstance(project).updateTrackerState(); next();
        } else if (step == 1) {
            if (git4idea.index.GitStageTrackerKt.status(GitStageTracker.getInstance(project), file) == null) return false;
            action("Git.Stage.Compare.Local.Staged", source); next();
        } else if (step == 2) {
            for (var e : manager.getAllEditors()) if (e instanceof DiffEditorViewerFileEditor d && d.getEditorViewer() instanceof GitStageDiffRequestProcessor p) stage = p;
            if (!diffReady(OLD)) return false;
            selectMethod(two().getEditor2()); next();
        } else if (step == 3) {
            var editor = two().getEditor2();
            if (!SelectionUxChecks.enabled(editor, "DiffNavigation.SelectedLines")) return false;
            var ready = field(SelectionUxChecks.controller(editor), "prepared");
            var plans = (Map<?, ?>) field(ready, "plans");
            var plan = plans.values().iterator().next();
            var result = (SelectedLines.Result) field(plan, "result");
            check.accept(result.text().equals(OLD + METHOD), "native Diff method-only Stage plan keeps the old call and appends the complete method");
            check.accept(!result.needsPreview(), "clear method-only Stage has no ambiguous replacement dialog");
            SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", editor); next();
        } else if (step == 4) {
            if (!diffReady(OLD + METHOD)) return false;
            check.accept(index().equals(OLD + METHOD) && Files.readString(path()).equals(LOCAL), "method-only Stage writes exact INDEX and preserves worktree");
            SelectionUxChecks.select(two().getEditor2(), "score = role.AchievementScore(period.RankNo)"); next();
        } else if (step == 5) {
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", two().getEditor2())) return false; next();
        } else if (step == 6) {
            if (!diffReady(LOCAL)) return false;
            check.accept(index().equals(LOCAL), "call-only Stage retains the independently staged method");
            source = manager.openTextEditor(new OpenFileDescriptor(project, file), true); selectMethod(source); next();
        } else if (step == 7) {
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", source)) return false; next();
        } else if (step == 8) {
            if (!index().equals(EDITED)) return false;
            check.accept(source.getDocument().getText().equals(LOCAL), "source method-only Unstage retains the staged call and all local code");
            selectMethod(source); next();
        } else if (step == 9) {
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", source)) return false; next();
        } else if (step == 10) {
            if (!index().equals(LOCAL)) return false;
            check.accept(true, "source method-only Stage restores the complete added method at its own boundary");
            SelectionUxChecks.select(source, "score = role.AchievementScore(period.RankNo)"); next();
        } else if (step == 11) {
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.SelectedLines", source)) return false; next();
        } else if (step == 12) {
            if (!index().equals(OLD + METHOD)) return false;
            check.accept(Files.readString(path()).equals(LOCAL), "source call-only Unstage preserves the staged method and worktree");
            action("Git.Stage.Compare.Local.Staged", source); next();
        } else if (step == 13) {
            if (!diffReady(OLD + METHOD)) return false;
            SelectionUxChecks.select(two().getEditor2(), "score = role.AchievementScore(period.RankNo)"); next();
        } else if (step == 14) {
            if (!SelectionUxChecks.invokeWhenReady("DiffNavigation.DiscardSelectedLines", two().getEditor2())) return false; next();
        } else if (step == 15) {
            if (!two().getEditor2().getDocument().getText().equals(OLD + METHOD)) return false;
            check.accept(index().equals(OLD + METHOD), "Discard changes only the selected local call while keeping INDEX and method");
            return true;
        }
        return false;
    }
    private void next() { step++; ticks = 0; }
    private TwosideTextDiffViewer two() { return (TwosideTextDiffViewer) stage.getActiveViewer(); }
    private boolean diffReady(String expected) {
        return stage != null && stage.getActiveViewer() instanceof TwosideTextDiffViewer t && !t.hasPendingRediff() && t.getEditor1().getDocument().getText().equals(expected);
    }
    private static void selectMethod(Editor editor) {
        int start = editor.getDocument().getText().indexOf("\n// details");
        editor.getCaretModel().moveToOffset(start); editor.getSelectionModel().setSelection(start, editor.getDocument().getTextLength());
    }
    private static void action(String id, Editor editor) { ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), DataManager.getInstance().getDataContext(editor.getContentComponent()), "ReplacementSmoke", null, null); }
    private Path path() { return Path.of(project.getBasePath(), "replacement-selection.txt"); }
    private String index() throws Exception { return git("show", ":replacement-selection.txt"); }
    private String git(String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-C", project.getBasePath())); cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); var out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new AssertionError(out); return out.replace("\r\n", "\n");
    }
    private static Object field(Object o, String name) throws Exception { var f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
}
