package io.github.youjiaxing.diffnav;

import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.util.Side;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import git4idea.index.GitStageTracker;
import git4idea.index.ui.*;
import java.awt.event.*;
import java.util.*;

/** Reconciles an index write with the originating preview. Never performs a Git write. EDT only. */
@Service(Service.Level.PROJECT)
public final class StageRefreshService implements Disposable {
    private static final Key<Boolean> STALE = Key.create("diffnav.stale.staging.viewer");
    private final Project project;
    private final Map<GitStageDiffRequestProcessor, Refresh> pending = new IdentityHashMap<>();
    private final Set<LineStaging> selections = Collections.newSetFromMap(new IdentityHashMap<>());
    private int own;
    public StageRefreshService(Project project) {
        this.project = project;
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(FileEditorManagerEvent event) {
                if (own != 0) return;
                for (Refresh refresh : List.copyOf(pending.values()))
                    if (!refresh.preview.equals(event.getNewFile()) || !refresh.ownsWindow()) refresh.cancel();
            }
            @Override public void fileClosed(FileEditorManager manager, VirtualFile file) {
                for (Refresh refresh : List.copyOf(pending.values())) if (refresh.preview.equals(file)) refresh.cancel();
            }
        });
    }
    static void markStale(DiffViewerBase viewer) { viewer.getComponent().putClientProperty(STALE, true); }
    static boolean isStale(DiffViewerBase viewer) { return Boolean.TRUE.equals(viewer.getComponent().getClientProperty(STALE)); }
    boolean ready(GitStageDiffRequestProcessor stage, DiffViewerBase viewer) {
        return !isStale(viewer) && !pending.containsKey(stage);
    }
    void register(LineStaging selection, Disposable lifetime) {
        selections.add(selection);
        Disposer.register(lifetime, () -> selections.remove(selection));
    }
    void userIntent() {
        for (LineStaging selection : List.copyOf(selections)) selection.invalidate();
        for (Refresh refresh : List.copyOf(pending.values())) refresh.cancel();
    }
    Refresh begin(GitStageDiffRequestProcessor stage, Editor selected, GitFileStatusNode node) {
        // Deleted-file viewers have no local side; keep their existing native refresh path.
        if (node.getFilePath().getVirtualFile() == null) return null;
        var old = pending.get(stage); if (old != null) old.cancel();
        var window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        if (window == null || window.getSelectedFile() == null) return null;
        var refresh = new Refresh(stage, window, selected, node);
        pending.put(stage, refresh);
        return refresh;
    }

    final class Refresh {
        final GitStageDiffRequestProcessor stage;
        final EditorWindow window;
        final VirtualFile preview;
        final GitFileStatusNode original;
        final NavigationPosition position;
        final Side side;
        final Disposable lifetime = Disposer.newDisposable("Staging preview refresh");
        final Alarm alarm;
        volatile boolean canceled;
        boolean applied, treePending, tickScheduled, documentOnly, indexExists = true;
        String expected;
        long deadline;
        long lastReload;
        Disposable editorInputs;
        Object boundViewer;
        Refresh(GitStageDiffRequestProcessor stage, EditorWindow window, Editor selected, GitFileStatusNode node) {
            this.stage = stage; this.window = window; this.original = node; preview = window.getSelectedFile();
            position = NavigationPosition.capture(selected.getDocument(), selected.getCaretModel().getOffset());
            var originalView = StageDiffView.of(stage.getActiveViewer());
            side = originalView == null ? Side.LEFT : originalView.side(selected);
            Disposer.register(StageRefreshService.this, lifetime);
            Disposer.register(lifetime, position::close);
            alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, lifetime);
            var tree = stage.getStageTree();
            var mouse = new MouseAdapter() { @Override public void mousePressed(MouseEvent event) { if (own == 0) cancel(); } };
            var keys = new KeyAdapter() { @Override public void keyPressed(KeyEvent event) { if (own == 0) cancel(); } };
            tree.addMouseListener(mouse); tree.addKeyListener(keys);
            Disposer.register(lifetime, () -> { tree.removeMouseListener(mouse); tree.removeKeyListener(keys); });
            tree.addSelectionListener(() -> { if (own == 0 && tree.hasFocus() && !tree.isModelUpdateInProgress()) cancel(); }, lifetime);
            bindEditorInputs();
            stage.addListener(this::bindEditorInputs, lifetime);
        }
        void bindEditorInputs() {
            if (canceled || stage.getActiveViewer() == boundViewer) return;
            if (editorInputs != null) Disposer.dispose(editorInputs);
            boundViewer = stage.getActiveViewer();
            editorInputs = Disposer.newDisposable("Selection refresh editor inputs");
            Disposer.register(lifetime, editorInputs);
            var view = StageDiffView.of(stage.getActiveViewer());
            if (view != null) for (Side s : Side.values()) {
                var editor = view.editor(s);
                if (editor == null) continue;
                var mouse = new MouseAdapter() { @Override public void mousePressed(MouseEvent event) { if (own == 0) cancel(); } };
                var keys = new KeyAdapter() { @Override public void keyPressed(KeyEvent event) { if (own == 0) cancel(); } };
                java.awt.event.MouseWheelListener wheel = event -> { if (own == 0) cancel(); };
                var components = new HashSet<java.awt.Component>();
                components.add(editor.getContentComponent());
                collectScrollComponents(editor.getComponent(), components);
                for (var component : components) {
                    component.addMouseListener(mouse); component.addKeyListener(keys); component.addMouseWheelListener(wheel);
                    Disposer.register(editorInputs, () -> {
                        component.removeMouseListener(mouse); component.removeKeyListener(keys); component.removeMouseWheelListener(wheel);
                    });
                }
            }
        }
        void collectScrollComponents(java.awt.Component component, Set<java.awt.Component> result) {
            if (component instanceof javax.swing.JScrollPane || component instanceof javax.swing.JScrollBar) result.add(component);
            if (component instanceof java.awt.Container parent) for (var child : parent.getComponents()) collectScrollComponents(child, result);
        }
        void cancel() {
            if (canceled) return;
            canceled = true;
            pending.remove(stage, this);
            Disposer.dispose(lifetime);
        }
        boolean ownsWindow() {
            if (project.isDisposed() || window.isDisposed() || stage.isDisposed()
                    || !NavigationSettings.get(project).getState().enabled
                    || FileEditorManagerEx.getInstanceEx(project).getCurrentWindow() != window || !preview.equals(window.getSelectedFile())) return false;
            var composite = window.getSelectedComposite();
            return composite != null && composite.getSelectedEditor() instanceof DiffEditorViewerFileEditor diff && diff.getEditorViewer() == stage;
        }
        void completed(String text, boolean exists) {
            if (canceled) return;
            expected = text; indexExists = exists;
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            advance();
        }
        void completedDocument(String text) {
            if (canceled) return;
            documentOnly = true; expected = text;
            deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            if (stage.getActiveViewer() instanceof DiffViewerBase viewer) viewer.scheduleRediff();
            retry();
        }
        void retry() {
            if (canceled || tickScheduled) return;
            tickScheduled = true;
            alarm.addRequest(() -> { tickScheduled = false; advance(); }, 100, ModalityState.nonModal());
        }
        void afterTreeRefresh() {
            var tree = stage.getStageTree();
            treePending = false;
            if (canceled || !ownsWindow()) { cancel(); return; }
            if (tree.isModelUpdateInProgress()) { retry(); return; }
            NodeKind required = !indexExists ? NodeKind.UNTRACKED : original.getKind() == NodeKind.UNTRACKED ? NodeKind.UNSTAGED : original.getKind();
            var target = GitStageDiffPreviewHandler.INSTANCE.iterateAllChanges(tree).find(w ->
                    w.getUserObject() instanceof GitFileStatusNode node && node.getKind() == required
                            && node.getFilePath().equals(original.getFilePath()));
            if (target == null) {
                // No remaining entry: keep native behavior; never substitute a staged or unrelated item.
                cancel(); return;
            }
            project.getService(StageNavigationService.class).beforeExplicitComparison(tree);
            own++;
            try {
                GitStageDiffPreviewHandler.INSTANCE.selectChange(tree, target);
                stage.setCurrentChange(target);
                // Equivalent to native reloadRequest: force apply and bypass the cached producer.
                stage.updateRequest(true, false, null);
                lastReload = System.nanoTime(); applied = true;
            } finally { own--; }
            retry();
        }
        void advance() {
            if (canceled) return;
            if (!ownsWindow()) { cancel(); return; }
            if (System.nanoTime() > deadline) {
                project.getService(StageNavigationService.class).comparisonFailed("修改已成功，预览刷新未完成，请从提交面板重新打开文件");
                cancel(); return;
            }
            if (treePending) { retry(); return; }
            if (documentOnly) {
                var view = StageDiffView.current(stage);
                if (view != null && view.editor(Side.RIGHT).getDocument().getText().equals(expected)) { restorePosition(view); cancel(); }
                else retry();
                return;
            }
            var tree = stage.getStageTree();
            // A tracker update is asynchronous. In particular, do not rebuild an old UNTRACKED model.
            var local = original.getFilePath().getVirtualFile();
            var status = local == null ? null : git4idea.index.GitStageTrackerKt.status(GitStageTracker.getInstance(project), local);
            if (original.getKind() == NodeKind.UNTRACKED && (status == null || status.isUntracked())) { retry(); return; }
            if (!indexExists && (status == null || !status.isUntracked())) { retry(); return; }
            if (!applied) {
                treePending = true;
                tree.requestRefresh(() -> ApplicationManager.getApplication().invokeLater(this::afterTreeRefresh, ModalityState.nonModal()));
                retry();
                return;
            }
            if (stage.getCurrentChange() == null || !(stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node)
                    || !node.getFilePath().equals(original.getFilePath())) { cancel(); return; }
            StageDiffView view = StageDiffView.current(stage);
            if (view != null) {
                Editor index = view.editor(node.getKind() == NodeKind.STAGED ? Side.RIGHT : Side.LEFT);
                if (index == null && !indexExists || index != null && index.getDocument().getText().equals(expected)) {
                    restorePosition(view);
                    cancel(); return;
                }
            }
            if (System.nanoTime() - lastReload > java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500)) {
                git4idea.index.vfs.GitIndexFileSystemRefresher.refreshFilePaths(project, List.of(original.getFilePath()));
                stage.updateRequest(true, false, null); lastReload = System.nanoTime();
            }
            retry();
        }
        void restorePosition(StageDiffView view) {
            Side actual = view.editor(side) == null ? Side.RIGHT : side;
            var editor = view.editor(actual);
            int offset = position.resolve(editor.getDocument());
            if (offset >= 0) {
                own++;
                try { editor.getCaretModel().moveToOffset(offset); view.scrollToLine(actual, editor.getDocument().getLineNumber(offset)); }
                finally { own--; }
            }
        }
    }
    @Override public void dispose() { userIntent(); }
}
