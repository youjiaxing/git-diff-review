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
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.EditorTabDiffPreview;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.xmlb.XmlSerializer;
import io.github.youjiaxing.diffnav.NavigationConfigurable;
import io.github.youjiaxing.diffnav.NavigationSettings;
import io.github.youjiaxing.diffnav.StageNavigationService;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffPreviewHandler;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.NodeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.tree.TreePath;
import javax.swing.JCheckBox;
import org.jdom.Element;

/** Native navigation and exact tree selection for added files in the disposable fixture. */
final class AddedFileChecks {
    private final Project project;
    private final GitStageDiffRequestProcessor stage;
    private final EditorTabDiffPreview preview;
    private final BiConsumer<Boolean, String> check;
    private final List<String> failures = new ArrayList<>();
    private int step;
    private int ticks;

    AddedFileChecks(Project project, GitStageDiffRequestProcessor stage, EditorTabDiffPreview preview, BiConsumer<Boolean, String> check) {
        this.project = project; this.stage = stage; this.preview = preview; this.check = check;
    }

    boolean tick() {
        ticks++;
        if (step == 0) {
            settingsRoundTrip();
            selectDirectory("added"); open("added/a.go"); next();
        } else if (step == 1 && ticks >= 5) {
            expect(selected("a.go"), "single directory selection follows source tab to exact added file");
            expect(source("a.go"), "tree follow preserves active added source tab");
            selectDirectory("single"); open("single/only.go"); next();
        } else if (step == 2 && ticks >= 5) {
            expect(selected("only.go"), "single-file directory is replaced with exact file selection");
            selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 3) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            expect(stage.getActiveViewer() instanceof OnesideTextDiffViewer, "untracked added file uses native one-sided Diff");
            call("AddedB()"); next();
        } else if (step == 4) {
            if (source("b.go")) {
                expect(false, "native declaration from untracked A stays in Diff for untracked B");
                throw new AssertionError(String.join("; ", failures));
            }
            if (!at("b.go", NodeKind.UNTRACKED)) return waiting();
            expect(editor().getCaretModel().getLogicalPosition().line == 2, "added target declaration is positioned at line 3");
            action("DiffNavigation.Back"); next();
        } else if (step == 5) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            expect(editor().getCaretModel().getOffset() == editor().getDocument().getText().indexOf("AddedB()"), "Back restores one-sided source and call position");
            action("DiffNavigation.Forward"); next();
        } else if (step == 6) {
            if (!at("b.go", NodeKind.UNTRACKED)) return waiting();
            expect(true, "Forward restores one-sided target");
            call("Existing()"); next();
        } else if (step == 7) {
            if (!at("existing.go", NodeKind.UNSTAGED)) return waiting();
            expect(stage.getActiveViewer() instanceof TwosideTextDiffViewer, "one-sided source can navigate to existing two-sided target");
            call("AddedB()"); next();
        } else if (step == 8) {
            if (!at("b.go", NodeKind.UNTRACKED)) return waiting();
            expect(true, "two-sided source can navigate to untracked one-sided target");
            setPreference(false);
            selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 9) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedB()"); next();
        } else if (step == 10) {
            if (!source("b.go") || ticks < 5) return waiting();
            expect(service().statusText().contains("On"), "disabled added-file Diff preference preserves review session in source");
            expect(selected("b.go"), "Commit follows added source tab even with added-file Diff preference off");
            action("DiffNavigation.Back"); next();
        } else if (step == 11) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            expect(true, "preference off still allows Back to manually opened added Diff");
            action("DiffNavigation.Forward"); next();
        } else if (step == 12) {
            if (!source("b.go")) return waiting();
            expect(true, "Forward restores added source chosen by preference");
            call("Existing()"); next();
        } else if (step == 13) {
            if (!at("existing.go", NodeKind.UNSTAGED)) return waiting();
            expect(true, "review continues from added source to existing modified Diff with preference off");
            selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 14) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedStaged()"); next();
        } else if (step == 15) {
            if (!source("staged.go")) return waiting();
            expect(true, "preference off opens fully staged new target as source");
            action("DiffNavigation.Back"); next();
        } else if (step == 16) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedPartial()"); next();
        } else if (step == 17) {
            if (!source("partial_added.go")) return waiting();
            expect(true, "preference off opens partially staged new target as source");
            setPreference(true); selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 18) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedStaged()"); next();
        } else if (step == 19) {
            if (!at("staged.go", NodeKind.STAGED)) return waiting();
            expect(stage.getActiveViewer() instanceof OnesideTextDiffViewer, "fully staged new target uses native one-sided INDEX Diff");
            call("AddedB()"); next();
        } else if (step == 20) {
            if (!at("b.go", NodeKind.UNTRACKED)) return waiting();
            expect(true, "native declaration from identical one-sided INDEX resolves in working-tree PSI");
            selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 21) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedPartial()"); next();
        } else if (step == 22) {
            if (!at("partial_added.go", NodeKind.UNSTAGED)) return waiting();
            expect(editor().getCaretModel().getLogicalPosition().line == 3, "partially staged new target follows local version and shifted declaration");
            NavigationSettings.get(project).getState().targetScope = NavigationSettings.TargetScope.STAGED_ONLY;
            service().settingsChanged(); selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 23) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedB()"); next();
        } else if (step == 24) {
            if (!source("b.go")) return waiting();
            expect(true, "staged-only scope keeps untracked new target in source despite preference on");
            action("DiffNavigation.Back"); next();
        } else if (step == 25) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedPartial()"); next();
        } else if (step == 26) {
            if (!at("partial_added.go", NodeKind.STAGED)) return waiting();
            expect(editor().getCaretModel().getLogicalPosition().line == 2 && !editor().getDocument().getText().contains("local-only"),
                    "staged-only new target uses INDEX declaration and excludes local edit");
            NavigationSettings.get(project).getState().targetScope = NavigationSettings.TargetScope.FOLLOW_TARGET;
            service().settingsChanged(); selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 27) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            call("AddedB()"); next();
        } else if (step == 28) {
            if (!at("b.go", NodeKind.UNTRACKED)) return waiting();
            git("add", "added/a.go"); refreshIndex(); next();
        } else if (step == 29) {
            if (!has("a.go", NodeKind.STAGED) || has("a.go", NodeKind.UNTRACKED)
                    || stage.getStageTree().isModelUpdateInProgress() || ticks < 5) return waiting();
            action("DiffNavigation.Back"); next();
        } else if (step == 30) {
            if (!at("a.go", NodeKind.STAGED)) return waiting();
            expect(editor().getCaretModel().getOffset() == editor().getDocument().getText().indexOf("AddedB()"), "Back remaps formerly untracked source after staging");
            action("DiffNavigation.Forward"); next();
        } else if (step == 31) {
            if (!at("b.go", NodeKind.UNTRACKED)) return waiting();
            git("restore", "--staged", "--", "added/a.go"); refreshIndex(); next();
        } else if (step == 32) {
            if (!has("a.go", NodeKind.UNTRACKED) || has("a.go", NodeKind.STAGED)
                    || stage.getStageTree().isModelUpdateInProgress() || ticks < 5) return waiting();
            action("DiffNavigation.Back"); next();
        } else if (step == 33) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            expect(editor().getCaretModel().getOffset() == editor().getDocument().getText().indexOf("AddedB()"), "Back remaps staged source after full unstage");
            service().settingsChanged(); open("added/a.go"); next();
        } else if (step == 34) {
            if (!source("a.go")) return waiting();
            call("AddedB()"); next();
        } else if (step == 35) {
            if (!source("b.go")) return waiting();
            expect(service().statusText().contains("Idle"), "ordinary added source navigation does not start review");
            selectDiff("a.go", NodeKind.UNTRACKED); next();
        } else if (step == 36) {
            if (!at("a.go", NodeKind.UNTRACKED)) return waiting();
            action("DiffNavigation.ToggleReview"); action("DiffNavigation.ToggleReview");
            call("AddedB()"); next();
        } else if (step == 37) {
            if (!source("b.go")) return waiting();
            expect(service().statusText().contains("Idle"), "paused one-sided review preserves ordinary source navigation");
            stage.getStageTree().setSelectionPaths(new TreePath[]{directory("added"), directory("single")});
            open("added/existing.go"); next();
        } else if (step == 38 && ticks >= 5) {
            expect(stage.getStageTree().getSelectionCount() == 2, "actual multi-directory selection is preserved on tab switch");
            stage.getStageTree().clearSelection(); selectDirectory("single"); open("added/a.go"); next();
        } else if (step == 39 && ticks >= 5) {
            expect(selected("a.go"), "single unrelated directory does not block exact new-file following");
            if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
            return true;
        }
        return false;
    }

    private void next() { step++; ticks = 0; }
    private boolean waiting() {
        if (ticks > 50) throw new AssertionError("Added-file step " + step + " timed out; " + service().getLastReason() + "; " + failures);
        return false;
    }
    private void expect(boolean value, String label) {
        if (value) check.accept(true, label);
        else failures.add(label);
    }
    private void selectDirectory(String relative) {
        stage.getStageTree().setSelectionPath(directory(relative));
    }
    private TreePath directory(String relative) {
        var nodes = stage.getStageTree().getRoot().preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (ChangesBrowserNode<?>) nodes.nextElement();
            if (node.getUserObject() instanceof FilePath path && path.isDirectory()
                    && path.getPath().equals(project.getBasePath() + "/" + relative)) {
                return new TreePath(node.getPath());
            }
        }
        throw new AssertionError("Missing tree directory " + relative);
    }
    private boolean selected(String name) {
        var tree = stage.getStageTree();
        if (tree.getSelectionCount() != 1) return false;
        var node = (ChangesBrowserNode<?>) tree.getSelectionPath().getLastPathComponent();
        return node.getUserObject() instanceof GitFileStatusNode status && status.getFilePath().getName().equals(name);
    }
    private void open(String relative) {
        var file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + relative);
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 0), true);
    }
    private boolean source(String name) {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        var file = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && file.getName().equals(name);
    }
    private void selectDiff(String name, NodeKind kind) {
        var wrapper = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(stage.getStageTree()).find(w ->
                w.getFilePath().getName().equals(name) && ((GitFileStatusNode) w.getUserObject()).getKind() == kind);
        if (wrapper == null) throw new AssertionError("Missing added fixture " + name + " " + kind);
        GitStageDiffPreviewHandler.INSTANCE.selectChange(stage.getStageTree(), wrapper);
        stage.setCurrentChange(wrapper); preview.openPreview(true);
    }
    private boolean at(String name, NodeKind kind) {
        if (!(stage.getActiveViewer() instanceof DiffViewerBase viewer) || viewer.hasPendingRediff() || stage.getCurrentChange() == null
                || FileEditorManager.getInstance(project).getSelectedTextEditor() != null) return false;
        var node = (GitFileStatusNode) stage.getCurrentChange().getUserObject();
        var file = FileDocumentManager.getInstance().getFile(editor().getDocument());
        String actual = file instanceof git4idea.index.vfs.GitIndexVirtualFile index ? index.getFilePath().getPath() : file == null ? "" : file.getPath();
        return node.getKind() == kind && node.getFilePath().getName().equals(name) && actual.equals(node.getFilePath().getPath());
    }
    private Editor editor() {
        return stage.getActiveViewer() instanceof TwosideTextDiffViewer two ? two.getCurrentEditor()
                : ((OnesideTextDiffViewer) stage.getActiveViewer()).getEditor();
    }
    private void call(String expression) {
        var source = FileEditorManager.getInstance(project).getSelectedTextEditor();
        var editor = source == null ? editor() : source;
        int offset = editor.getDocument().getText().indexOf(expression);
        if (offset < 0) throw new AssertionError("Missing native call " + expression);
        editor.getCaretModel().moveToOffset(offset); action("GotoDeclaration");
    }
    private void action(String id) {
        var source = FileEditorManager.getInstance(project).getSelectedTextEditor();
        ActionUtil.invokeAction(ActionManager.getInstance().getAction(id),
                DataManager.getInstance().getDataContext((source == null ? editor() : source).getContentComponent()), "DiffNavSmoke", null, null);
    }
    private StageNavigationService service() { return project.getService(StageNavigationService.class); }
    private boolean has(String name, NodeKind kind) {
        return GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(stage.getStageTree()).find(w ->
                w.getFilePath().getName().equals(name) && ((GitFileStatusNode) w.getUserObject()).getKind() == kind) != null;
    }
    private void git(String... arguments) {
        var command = new ArrayList<>(List.of("git", "-C", project.getBasePath()));
        command.addAll(List.of(arguments));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (process.waitFor() != 0) throw new AssertionError("Fixture Git failed: " + output);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void refreshIndex() {
        var path = com.intellij.vcsUtil.VcsUtil.getFilePath(project.getBasePath() + "/added/a.go");
        var root = LocalFileSystem.getInstance().findFileByPath(project.getBasePath());
        var repository = git4idea.repo.GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root);
        if (repository == null) throw new AssertionError("Missing isolated Git repository");
        // External Git commands bypass the IDE action that normally invalidates its untracked cache.
        repository.getUntrackedFilesHolder().markPossiblyUntracked(List.of(path));
        com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).fileDirty(path);
        git4idea.index.vfs.GitIndexFileSystemRefresher.refreshFilePaths(project, List.of(path));
        git4idea.index.GitStageTracker.getInstance(project).updateTrackerState();
    }
    private void settingsRoundTrip() {
        var legacy = new Element("state");
        legacy.addContent(new Element("option").setAttribute("name", "autoStart").setAttribute("value", "false"));
        var loaded = XmlSerializer.deserialize(legacy, NavigationSettings.Options.class);
        expect(loaded.openNewFilesInDiff && !loaded.autoStart, "legacy preferences retain existing values and default added-file Diff on");
        loaded.openNewFilesInDiff = false;
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(loaded), NavigationSettings.Options.class);
        expect(!restored.openNewFilesInDiff && !restored.autoStart, "XML round trip preserves explicit added-file Diff off");
        expect(NavigationSettings.get(project).getState().openNewFilesInDiff, "new project defaults added-file Diff on");
    }
    private void setPreference(boolean value) {
        var configurable = new NavigationConfigurable(project);
        try {
            var component = configurable.createComponent();
            JCheckBox checkbox = checkbox(component);
            if (checkbox == null) throw new AssertionError("Missing added-file preference checkbox");
            checkbox.setSelected(value);
            expect(configurable.isModified(), "settings detect changed added-file preference");
            configurable.apply();
            expect(service().statusText().contains("Idle"), "applying settings resets review as with existing preferences");
            expect(!configurable.isModified() && NavigationSettings.get(project).getState().openNewFilesInDiff == value,
                    "settings apply and retain added-file preference " + value);
        } finally { configurable.disposeUIResources(); }
    }
    private JCheckBox checkbox(java.awt.Component component) {
        if (component instanceof JCheckBox box && box.getText().equals("跳转到新增文件时使用 Diff")) return box;
        if (component instanceof java.awt.Container parent) for (var child : parent.getComponents()) {
            var found = checkbox(child); if (found != null) return found;
        }
        return null;
    }
}
