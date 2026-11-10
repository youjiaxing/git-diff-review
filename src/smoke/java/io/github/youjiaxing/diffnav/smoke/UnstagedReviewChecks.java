package io.github.youjiaxing.diffnav.smoke;

import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.EditorTabDiffPreview;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.xmlb.XmlSerializer;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffPreviewHandler;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.NodeKind;
import io.github.youjiaxing.diffnav.NavigationSettings;
import io.github.youjiaxing.diffnav.StageNavigationService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.jdom.Element;

/** Real native navigation and Git transitions for the unstaged-only default. */
final class UnstagedReviewChecks {
    private final Project project;
    private final GitStageDiffRequestProcessor stage;
    private final EditorTabDiffPreview preview;
    private final BiConsumer<Boolean, String> check;
    private final List<String> failures = new ArrayList<>();
    private int step;
    private int ticks;
    private javax.swing.tree.TreePath sourceSelection;

    UnstagedReviewChecks(Project project, GitStageDiffRequestProcessor stage, EditorTabDiffPreview preview,
                         BiConsumer<Boolean, String> check) {
        this.project = project; this.stage = stage; this.preview = preview; this.check = check;
    }

    boolean tick() {
        if (++ticks > 75) throw new AssertionError("Unstaged policy step " + step + ": " + service().getLastReason() + "; " + failures);
        switch (step) {
            case 0 -> { selectDiff("partial.go", NodeKind.STAGED); next(); }
            case 1 -> {
                if (!at("partial.go", NodeKind.STAGED) || !selected("partial.go", NodeKind.STAGED)) return false;
                open("partial.go"); step = 100; ticks = 0;
            }
            case 100 -> {
                if (ticks < 5) return false;
                expect(selected("partial.go", NodeKind.UNSTAGED), "source tab replaces same-file staged selection with unstaged item");
                sourceSelection = stage.getStageTree().getSelectionPath();
                open("policy/b.go"); step = 2; ticks = 0;
            }
            case 2 -> {
                if (ticks < 5) return false;
                expect(!selected("b.go", NodeKind.STAGED), "source tab does not auto-select staged-only file");
                expect(java.util.Objects.equals(String.valueOf(sourceSelection), String.valueOf(stage.getStageTree().getSelectionPath())),
                        "staged-only source tab preserves the previous Commit selection");
                service().settingsChanged(); selectDiff("a.go", NodeKind.UNSTAGED); next();
            }
            case 3 -> {
                if (!at("a.go", NodeKind.UNSTAGED)) return false;
                call("PolicyB()"); next();
            }
            case 4 -> {
                if (!source("b.go")) {
                    if (ticks < 10) return false;
                    expect(false, "native declaration to staged-only target opens source instead of staged Diff");
                    throw new AssertionError(String.join("; ", failures));
                }
                expect(service().statusText().contains("On"), "staged-only source keeps the review session");
                call("PolicyC()"); next();
            }
            case 5 -> {
                if (!at("c.go", NodeKind.UNSTAGED)) return false;
                expect(true, "navigation through staged-only source continues to unstaged Diff");
                action("Back"); next();
            }
            case 6 -> {
                if (!source("b.go")) return false;
                expect(true, "native Back also keeps staged-only target in source");
                service().settingsChanged(); selectDiff("a.go", NodeKind.UNSTAGED); next();
            }
            case 7 -> {
                if (!at("a.go", NodeKind.UNSTAGED)) return false;
                call("PolicyC()"); next();
            }
            case 8 -> {
                if (!at("c.go", NodeKind.UNSTAGED)) return false;
                git("add", "policy/a.go"); refresh("policy/a.go"); next();
            }
            case 9 -> {
                if (!settled("a.go", NodeKind.STAGED, NodeKind.UNSTAGED)) return false;
                action("DiffNavigation.Back"); next();
            }
            case 10 -> {
                if (!source("a.go")) return false;
                expect(current().getCaretModel().getOffset() == current().getDocument().getText().indexOf("PolicyC()"),
                        "Back after full Stage restores source and call position");
                action("DiffNavigation.Forward"); next();
            }
            case 11 -> {
                if (!at("c.go", NodeKind.UNSTAGED)) return false;
                git("restore", "--staged", "--", "policy/a.go"); refresh("policy/a.go"); next();
            }
            case 12 -> {
                if (!settled("a.go", NodeKind.UNSTAGED, NodeKind.STAGED)) return false;
                action("DiffNavigation.Back"); next();
            }
            case 13 -> {
                if (!at("a.go", NodeKind.UNSTAGED)) return false;
                expect(current().getCaretModel().getOffset() == current().getDocument().getText().indexOf("PolicyC()"),
                        "Back after Unstage re-evaluates a history entry previously shown as source");
                action("DiffNavigation.OpenSource"); next();
            }
            case 14 -> { if (!source("a.go")) return false; call("PolicyC()"); next(); }
            case 15 -> { if (!at("c.go", NodeKind.UNSTAGED)) return false; action("DiffNavigation.Back"); next(); }
            case 16 -> {
                if (!source("a.go")) return false;
                expect(true, "explicit Open as Source remains source during history travel");
                service().settingsChanged(); selectDiff("b.go", NodeKind.STAGED); next();
            }
            case 17 -> {
                if (!at("b.go", NodeKind.STAGED)) return false;
                action("DiffNavigation.ToggleReview"); open("policy/c.go"); next();
            }
            case 18 -> {
                if (!source("c.go") || !selected("c.go", NodeKind.UNSTAGED) || ticks < 5) return false;
                preview.openPreview(true); next();
            }
            case 19 -> {
                if (!at("b.go", NodeKind.STAGED)) return false;
                expect(true, "explicit return to parked manual staged preview preserves the original file and group");
                open("policy/c.go"); next();
            }
            case 20 -> {
                if (!source("c.go") || !selected("c.go", NodeKind.UNSTAGED) || ticks < 5) return false;
                git("restore", "--staged", "--", "policy/b.go"); refresh("policy/b.go"); next();
            }
            case 21 -> {
                if (!settled("b.go", NodeKind.UNSTAGED, NodeKind.STAGED)) return false;
                preview.openPreview(true); next();
            }
            case 22 -> {
                if (!at("b.go", NodeKind.UNSTAGED)) return false;
                expect(true, "parked preview follows remaining unstaged version after old group disappears");
                open("policy/c.go"); next();
            }
            case 23 -> {
                if (!source("c.go") || !selected("c.go", NodeKind.UNSTAGED) || ticks < 5) return false;
                git("add", "policy/b.go"); refresh("policy/b.go"); next();
            }
            case 24 -> {
                if (!settled("b.go", NodeKind.STAGED, NodeKind.UNSTAGED)) return false;
                preview.openPreview(true); next();
            }
            case 25 -> {
                if (!source("b.go")) return false;
                expect(true, "return to obsolete unstaged preview opens original source instead of unrelated background file");
                service().settingsChanged(); selectDiff("b.go", NodeKind.STAGED); next();
            }
            case 26 -> { if (!at("b.go", NodeKind.STAGED)) return false; call("PolicyC()"); next(); }
            case 27 -> { if (!at("c.go", NodeKind.UNSTAGED)) return false; action("DiffNavigation.Back"); next(); }
            case 28 -> {
                if (!source("b.go")) return false;
                expect(true, "default Back from manual staged origin still applies current unstaged range");
                NavigationSettings.get(project).getState().targetScope = NavigationSettings.TargetScope.STAGED_ONLY;
                service().settingsChanged(); selectDiff("a.go", NodeKind.UNSTAGED); next();
            }
            case 29 -> { if (!at("a.go", NodeKind.UNSTAGED)) return false; call("PolicyB()"); next(); }
            case 30 -> {
                if (!at("b.go", NodeKind.STAGED)) return false;
                expect(true, "explicit staged-only scope still navigates to staged Diff");
                action("DiffNavigation.Back"); next();
            }
            case 31 -> {
                if (!at("a.go", NodeKind.UNSTAGED)) return false;
                expect(true, "explicit staged-only scope keeps its previous history semantics");
                xmlCompatibility();
                NavigationSettings.get(project).getState().targetScope = NavigationSettings.TargetScope.FOLLOW_TARGET;
                service().settingsChanged();
                for (var file : FileEditorManager.getInstance(project).getOpenFiles())
                    if (file.isInLocalFileSystem() && file.getPath().startsWith(project.getBasePath() + "/"))
                        FileEditorManager.getInstance(project).closeFile(file);
                selectDiff("main.go", NodeKind.UNSTAGED); next();
            }
            case 32 -> { if (at("main.go", NodeKind.UNSTAGED)) { if (!failures.isEmpty()) throw new AssertionError(failures); return true; } }
            default -> throw new AssertionError("Unexpected policy step " + step);
        }
        return false;
    }

    private void next() { step++; ticks = 0; }
    private void expect(boolean value, String label) { if (value) check.accept(true, label); else failures.add(label); }
    private StageNavigationService service() { return project.getService(StageNavigationService.class); }
    private Editor current() {
        var source = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (source != null) return source;
        return stage.getActiveViewer() instanceof TwosideTextDiffViewer two ? two.getCurrentEditor()
                : ((OnesideTextDiffViewer) stage.getActiveViewer()).getEditor();
    }
    private boolean source(String name) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        var file = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && file.getPath().equals(expectedPath(name));
    }
    private boolean at(String name, NodeKind kind) {
        if (FileEditorManager.getInstance(project).getSelectedTextEditor() != null
                || !(stage.getActiveViewer() instanceof DiffViewerBase viewer) || viewer.hasPendingRediff() || stage.getCurrentChange() == null) return false;
        var node = (GitFileStatusNode) stage.getCurrentChange().getUserObject();
        var file = FileDocumentManager.getInstance().getFile(current().getDocument());
        String actual = file instanceof git4idea.index.vfs.GitIndexVirtualFile index ? index.getFilePath().getPath() : file == null ? "" : file.getPath();
        return node.getFilePath().getPath().equals(expectedPath(name)) && node.getKind() == kind && actual.equals(node.getFilePath().getPath());
    }
    private boolean selected(String name, NodeKind kind) {
        var path = stage.getStageTree().getSelectionPath();
        return stage.getStageTree().getSelectionCount() == 1 && path != null
                && ((ChangesBrowserNode<?>) path.getLastPathComponent()).getUserObject() instanceof GitFileStatusNode node
                && node.getKind() == kind && node.getFilePath().getPath().equals(expectedPath(name));
    }
    private boolean has(String name, NodeKind kind) {
        return GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(stage.getStageTree()).find(w ->
                w.getFilePath().getPath().equals(expectedPath(name)) && ((GitFileStatusNode) w.getUserObject()).getKind() == kind) != null;
    }
    private boolean settled(String name, NodeKind present, NodeKind absent) {
        return ticks >= 5 && !stage.getStageTree().isModelUpdateInProgress() && has(name, present) && !has(name, absent);
    }
    private void selectDiff(String name, NodeKind kind) {
        var wrapper = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(stage.getStageTree()).find(w ->
                w.getFilePath().getPath().equals(expectedPath(name)) && ((GitFileStatusNode) w.getUserObject()).getKind() == kind);
        if (wrapper == null) throw new AssertionError("Missing policy fixture " + name + " " + kind);
        var tree = stage.getStageTree();
        tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
        GitStageDiffPreviewHandler.INSTANCE.selectChange(stage.getStageTree(), wrapper);
        stage.setCurrentChange(wrapper); preview.openPreview(true);
    }
    private void open(String relative) {
        var file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + relative);
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true);
    }
    private String expectedPath(String name) {
        return project.getBasePath() + "/" + (name.equals("a.go") || name.equals("b.go") || name.equals("c.go") ? "policy/" : "") + name;
    }
    private void call(String expression) {
        int offset = current().getDocument().getText().indexOf(expression);
        if (offset < 0) throw new AssertionError("Missing policy call " + expression);
        current().getCaretModel().moveToOffset(offset); action("GotoDeclaration");
    }
    private void action(String id) {
        ActionUtil.invokeAction(ActionManager.getInstance().getAction(id), DataManager.getInstance().getDataContext(current().getContentComponent()),
                "DiffNavSmoke", null, null);
    }
    private void git(String... arguments) {
        var command = new ArrayList<>(List.of("git", "-C", project.getBasePath())); command.addAll(List.of(arguments));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new AssertionError(out);
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private void refresh(String relative) {
        var path = com.intellij.vcsUtil.VcsUtil.getFilePath(project.getBasePath() + "/" + relative);
        var root = LocalFileSystem.getInstance().findFileByPath(project.getBasePath());
        var repository = git4idea.repo.GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root);
        repository.getUntrackedFilesHolder().markPossiblyUntracked(List.of(path));
        com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(path);
        git4idea.index.vfs.GitIndexFileSystemRefresher.refreshFilePaths(project, List.of(path));
        git4idea.index.GitStageTracker.getInstance(project).updateTrackerState();
    }
    private void xmlCompatibility() {
        var empty = XmlSerializer.deserialize(new Element("state"), NavigationSettings.Options.class);
        expect(empty.targetScope == NavigationSettings.TargetScope.FOLLOW_TARGET
                && empty.targetScope.toString().equals("只审未暂存修改"), "missing scope defaults to unstaged-only display without changing enum identity");
        for (var encoded : List.of("FOLLOW_TARGET", "跟随本地导航目标", "只审未暂存修改", "STAGED_ONLY", "只审已暂存修改")) {
            var scope = encoded.equals("STAGED_ONLY") || encoded.equals("只审已暂存修改")
                    ? NavigationSettings.TargetScope.STAGED_ONLY : NavigationSettings.TargetScope.FOLLOW_TARGET;
            var xml = new Element("state");
            xml.addContent(new Element("option").setAttribute("name", "targetScope").setAttribute("value", encoded));
            xml.addContent(new Element("option").setAttribute("name", "openNewFilesInDiff").setAttribute("value", "false"));
            var loaded = XmlSerializer.deserialize(xml, NavigationSettings.Options.class);
            var serialized = XmlSerializer.serialize(loaded);
            var restored = XmlSerializer.deserialize(serialized, NavigationSettings.Options.class);
            expect(loaded.targetScope == scope && restored.targetScope == scope && !restored.openNewFilesInDiff,
                    "scope XML round trip accepts " + encoded + " and retains unrelated preference");
        }
    }
}
