package io.github.youjiaxing.diffnav;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.GitStagePanel;
import git4idea.index.ui.GitStageTree;
import git4idea.index.ui.NodeKind;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.*;
import java.util.*;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

/** Passive editor → Commit tree following. Never opens a preview or executes Git operations. */
@Service(Service.Level.PROJECT)
public final class CommitTreeFollowService implements Disposable {
    private final Project project;
    private final Map<GitStageTree, Binding> bindings = new IdentityHashMap<>();
    private long generation;
    private boolean disposed;
    public CommitTreeFollowService(Project project) {
        this.project = project;
        var bus = project.getMessageBus().connect(this);
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(FileEditorManagerEvent event) { request(true); }
        });
        bus.subscribe(ToolWindowManagerListener.TOPIC, new ToolWindowManagerListener() {
            @Override public void toolWindowShown(com.intellij.openapi.wm.ToolWindow window) { if (window.getId().equals("Commit")) request(true); }
            @Override public void stateChanged(ToolWindowManager manager) { request(false); }
        });
        request(true);
    }

    public void request(boolean newIntent) {
        if (disposed || project.isDisposed()) return;
        if (newIntent) generation++;
        long expected = generation;
        ApplicationManager.getApplication().invokeLater(() -> follow(expected, newIntent));
    }

    private void follow(long expected, boolean newIntent) {
        if (disposed || project.isDisposed() || expected != generation) return;
        var window = ToolWindowManager.getInstance(project).getToolWindow("Commit");
        if (window == null || !window.isVisible()) return;
        var panel = find(window.getContentManager().getComponent(), GitStagePanel.class);
        if (panel == null || !panel.isShowing() || !(panel.getTree() instanceof GitStageTree tree) || !tree.isShowing()) return;
        Binding binding = bindings.computeIfAbsent(tree, Binding::new);
        if (newIntent) binding.manual = false;
        if (binding.manual || tree.getSelectionCount() > 1) return;
        if (project.getService(StageNavigationService.class).isNavigating(tree)) return;
        var editorWindow = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        if (editorWindow == null) return;
        if (SwingUtilities.getWindowAncestor(panel) != SwingUtilities.getWindowAncestor(editorWindow.getOwner())) return;
        VirtualFile file = editorWindow.getSelectedFile();
        if (file == null) return;
        String path = file.getPath();
        NodeKind required = null;
        for (var editor : FileEditorManager.getInstance(project).getEditors(file)) {
            if (editor instanceof DiffEditorViewerFileEditor diff && diff.getEditorViewer() instanceof GitStageDiffRequestProcessor stage) {
                if (stage.getStageTree() != tree || stage.getCurrentChange() == null
                        || !(stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node)) return;
                if (stage.getActiveViewer() instanceof com.intellij.diff.tools.util.base.DiffViewerBase base && base.hasPendingRediff()) return;
                if (StageDiffView.of(stage.getActiveViewer()) != null && StageDiffView.current(stage) == null) return;
                path = node.getFilePath().getPath(); required = node.getKind(); break;
            }
        }
        if (required == null && !file.isInLocalFileSystem()) return;
        if (tree.isModelUpdateInProgress()) {
            tree.invokeAfterRefresh(() -> follow(expected, false)); return;
        }
        // A selected directory includes descendant status nodes, but is not an exact file selection.
        boolean unstagedOnly = NavigationSettings.get(project).getState().targetScope == NavigationSettings.TargetScope.FOLLOW_TARGET;
        var selection = tree.getSelectionPath();
        if (required == null && selection != null && selection.getLastPathComponent() instanceof ChangesBrowserNode<?> selected
                && selected.getUserObject() instanceof GitFileStatusNode node && same(node, path)
                && (!unstagedOnly || isUnstaged(node.getKind()))) return;
        ChangesBrowserNode<?> best = null;
        var nodes = tree.getRoot().preorderEnumeration();
        while (nodes.hasMoreElements()) {
            Object item = nodes.nextElement();
            if (!(item instanceof ChangesBrowserNode<?> candidate) || !(candidate.getUserObject() instanceof GitFileStatusNode node)
                    || !same(node, path) || required != null && node.getKind() != required) continue;
            if (required == null && unstagedOnly && !isUnstaged(node.getKind())) continue;
            if (best == null || rank(node.getKind()) < rank(((GitFileStatusNode) best.getUserObject()).getKind())) best = candidate;
        }
        if (best == null || expected != generation || binding.manual) return;
        TreePath target = new TreePath(best.getPath());
        if (tree.isPathSelected(target)) return;
        project.getService(StageNavigationService.class).beforeAutomaticTreeSelection(tree);
        binding.own++;
        try { tree.setSelectionPath(target); tree.scrollPathToVisible(target); }
        finally { binding.own--; }
    }

    private static int rank(NodeKind kind) { return kind == NodeKind.UNSTAGED || kind == NodeKind.UNTRACKED ? 0 : kind == NodeKind.STAGED ? 1 : 2; }
    private static boolean isUnstaged(NodeKind kind) { return kind == NodeKind.UNSTAGED || kind == NodeKind.UNTRACKED; }
    private static boolean same(GitFileStatusNode node, String path) { return com.intellij.openapi.util.io.FileUtil.pathsEqual(node.getFilePath().getPath(), path); }
    private final class Binding {
        final GitStageTree tree;
        int own;
        boolean manual;
        final MouseAdapter mouse;
        final KeyAdapter keys;
        final java.beans.PropertyChangeListener model;
        Binding(GitStageTree tree) {
            this.tree = tree;
            mouse = new MouseAdapter() { @Override public void mousePressed(MouseEvent event) { markManual(); } };
            keys = new KeyAdapter() { @Override public void keyPressed(KeyEvent event) { markManual(); } };
            tree.addMouseListener(mouse); tree.addKeyListener(keys);
            tree.addSelectionListener(() -> {
                if (own == 0 && !tree.isModelUpdateInProgress() && tree.hasFocus()) markManual();
            }, CommitTreeFollowService.this);
            model = event -> { if (!manual) request(false); };
            tree.addPropertyChangeListener("model", model);
        }
        void markManual() {
            if (own != 0) return;
            manual = true; generation++;
            project.getService(StageNavigationService.class).manualTreeSelection(tree);
        }
    }
    private static <T> T find(Component component, Class<T> type) {
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container parent) for (Component child : parent.getComponents()) {
            T match = find(child, type); if (match != null) return match;
        }
        return null;
    }
    @Override public void dispose() {
        disposed = true; generation++;
        for (Binding binding : bindings.values()) {
            binding.tree.removeMouseListener(binding.mouse); binding.tree.removeKeyListener(binding.keys);
            binding.tree.removePropertyChangeListener("model", binding.model);
        }
        bindings.clear();
    }
}
