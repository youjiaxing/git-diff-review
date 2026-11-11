package io.github.youjiaxing.diffnav;

import com.intellij.diff.*;
import com.intellij.diff.comparison.*;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.util.base.*;
import com.intellij.diff.tools.util.side.*;
import com.intellij.ide.DataManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.awt.RelativePoint;
import git4idea.config.GitExecutableManager;
import git4idea.index.GitStageTracker;
import git4idea.index.ui.*;
import java.awt.BorderLayout;
import java.awt.Point;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** One selection plan for the floating toolbar, context menu and keyboard actions. */
public final class LineStaging {
    private static final Key<LineStaging> CONTROLLER = Key.create("diffnav.line.staging");
    private final DiffViewerBase viewer;
    private final List<? extends Editor> editors;
    private final Document leftDocument, rightDocument;
    private final Project project;
    private boolean busy, wantPopup;
    private long generation;
    private Selection pending;
    private Editor selectionEditor;
    private Prepared prepared;
    private JBPopup popup;
    private record Plan(String before, SelectedLines.Result result) {}
    private record Prepared(Selection selection, GitIndexTransaction transaction,
                            GitIndexTransaction.Snapshot snapshot, Map<SelectionOperation, Plan> plans) {}

    private LineStaging(DiffViewerBase viewer, Project project) {
        this.viewer = viewer; this.project = project;
        if (viewer instanceof TwosideTextDiffViewer two) {
            editors = two.getEditors(); leftDocument = two.getEditor1().getDocument(); rightDocument = two.getEditor2().getDocument();
        } else {
            var one = (OnesideTextDiffViewer) viewer;
            editors = one.getEditors();
            Document empty = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument("");
            leftDocument = one.getSide() == com.intellij.diff.util.Side.LEFT ? one.getEditor().getDocument() : empty;
            rightDocument = one.getSide() == com.intellij.diff.util.Side.RIGHT ? one.getEditor().getDocument() : empty;
        }
    }
    static void install(DiffViewerBase viewer, DiffContext context) {
        if (!"Stage".equals(context.getUserData(com.intellij.diff.util.DiffUserDataKeys.PLACE))) return;
        Project project = context.getProject();
        if (project == null) return;
        var controller = new LineStaging(viewer, project);
        project.getService(StageRefreshService.class).register(controller, viewer);
        for (Editor editor : controller.editors) {
            editor.putUserData(CONTROLLER, controller);
            editor.addEditorMouseListener(new EditorMouseListener() {
                @Override public void mousePressed(EditorMouseEvent event) { controller.invalidate(); controller.selectionEditor = editor; }
                @Override public void mouseReleased(EditorMouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event.getMouseEvent())) controller.offer(editor);
                }
            }, viewer);
            editor.getSelectionModel().addSelectionListener(new SelectionListener() {
                @Override public void selectionChanged(SelectionEvent event) {
                    if (editor.getSelectionModel().hasSelection()) controller.selectionEditor = editor;
                    else if (controller.selectionEditor == editor) controller.selectionEditor = null;
                    controller.invalidate(); later(() -> controller.prepare(editor));
                }
            }, viewer);
            var focus = new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent event) {
                    if (controller.selectionEditor != editor) {
                        controller.invalidate(); controller.selectionEditor = editor; controller.prepare(editor);
                    }
                }
            };
            editor.getContentComponent().addFocusListener(focus);
            Disposer.register(viewer, () -> editor.getContentComponent().removeFocusListener(focus));
        }
        for (var document : new HashSet<>(List.of(controller.leftDocument, controller.rightDocument)))
            document.addDocumentListener(new DocumentListener() {
                @Override public void beforeDocumentChange(DocumentEvent event) { controller.invalidate(); }
            }, viewer);
        viewer.addListener(new DiffViewerListener() {
            @Override protected void onBeforeRediff() { controller.invalidate(); }
        });
        project.getMessageBus().connect(viewer).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(FileEditorManagerEvent event) {
                var selection = controller.pending;
                if (selection != null && !selection.ownsWindow()) controller.invalidate();
            }
            @Override public void fileClosed(FileEditorManager manager, VirtualFile file) { controller.invalidate(); }
        });
        Disposer.register(viewer, controller::invalidate);
    }
    private static LineStaging controller(Editor editor) { return editor == null ? null : editor.getUserData(CONTROLLER); }
    static List<SelectionOperation> operations(Editor editor) {
        var controller = controller(editor);
        if (controller == null) return List.of();
        controller.prepare(editor);
        var ready = controller.prepared;
        return ready != null && ready.selection.editor == editor && controller.valid(editor) && !controller.busy && ready.selection.current()
                ? List.copyOf(ready.plans.keySet()) : List.of();
    }
    static boolean available(Editor editor) { return operations(editor).stream().anyMatch(op -> op != SelectionOperation.DISCARD); }
    static TextEditor discardUndoTarget(Editor editor) {
        var controller = controller(editor);
        if (controller == null || controller.project.isDisposed() || editor.isDisposed() || editor.getDocument() != controller.leftDocument
                || !NavigationSettings.get(controller.project).getState().enabled || controller.viewer.isDisposed()
                || !(controller.viewer instanceof TwosideTextDiffViewer two)) return null;
        var stage = StageNavigationService.processor(DataManager.getInstance().getDataContext(editor.getContentComponent()));
        var view = stage == null ? null : StageDiffView.current(stage);
        if (view == null || stage.getActiveViewer() != controller.viewer || node(stage).getKind() == NodeKind.STAGED) return null;
        return com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(two.getEditor2());
    }
    static void invoke(Editor editor) {
        var controller = controller(editor);
        if (controller == null) return;
        var operations = operations(editor).stream().filter(op -> op != SelectionOperation.DISCARD).toList();
        if (operations.size() == 1) controller.execute(operations.getFirst());
        else if (operations.size() > 1) controller.showPopup(operations);
    }
    static void invoke(Editor editor, SelectionOperation operation) {
        var controller = controller(editor);
        if (controller != null && operations(editor).contains(operation)) controller.execute(operation);
    }
    void invalidate() {
        generation++;
        if (pending != null) pending.canceled.set(true);
        pending = null; prepared = null; wantPopup = false; closePopup();
    }
    private void offer(Editor editor) {
        selectionEditor = editor;
        closePopup(); prepare(editor); wantPopup = true;
        if (prepared != null && prepared.selection.current()) showPopup(List.copyOf(prepared.plans.keySet()));
    }
    private void prepare(Editor editor) {
        if (busy || selectionEditor != null && selectionEditor != editor || !valid(editor)) return;
        selectionEditor = editor;
        if (pending != null && pending.editor == editor && pending.current()) return;
        invalidate();
        var selection = new Selection(editor); pending = selection;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            var transaction = new GitIndexTransaction(selection.git);
            try {
                var snapshot = transaction.capture(selection.root, selection.path, selection.charset);
                var plans = plans(selection, snapshot);
                later(() -> {
                    if (!selection.current()) return;
                    prepared = new Prepared(selection, transaction, snapshot, plans);
                    com.intellij.ide.ActivityTracker.getInstance().inc();
                    if (wantPopup) showPopup(List.copyOf(plans.keySet()));
                });
            } catch (Exception failure) {
                later(() -> {
                    if (selection.current()) { prepared = new Prepared(selection, transaction, null, Map.of()); reason(failure.getMessage()); }
                });
            }
        });
    }
    private boolean valid(Editor editor) {
        if (project.isDisposed() || viewer.isDisposed() || viewer.hasPendingRediff() || editor.isDisposed()
                || !NavigationSettings.get(project).getState().enabled
                || leftDocument.getTextLength() > 8 * 1024 * 1024 || rightDocument.getTextLength() > 8 * 1024 * 1024
                || !editor.getSelectionModel().hasSelection() || editor.getCaretModel().getCaretCount() != 1) return false;
        var stage = StageNavigationService.processor(DataManager.getInstance().getDataContext(editor.getContentComponent()));
        if (stage == null || stage.isDisposed() || stage.getActiveViewer() != viewer || node(stage) == null
                || !project.getService(StageRefreshService.class).ready(stage, viewer)) return false;
        var node = node(stage); var status = node.getStatus();
        if (status.isConflicted() || status.getOrigPath() != null || status.getIndex() == 'R' || status.getIndex() == 'C'
                || status.getWorkTree() == 'R' || status.getWorkTree() == 'C') return false;
        var local = node.getFilePath().getVirtualFile();
        if (local != null && (!local.isValid() || local.getFileType().isBinary() || local.getLength() > 8 * 1024 * 1024
                || local.is(com.intellij.openapi.vfs.VFileProperty.SYMLINK))) return false;
        return node.getKind() == NodeKind.STAGED || node.getKind() == NodeKind.UNSTAGED || node.getKind() == NodeKind.UNTRACKED;
    }
    private static GitFileStatusNode node(GitStageDiffRequestProcessor stage) {
        return stage.getCurrentChange() != null && stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node ? node : null;
    }
    private final class Selection {
        final Editor editor;
        final GitStageDiffRequestProcessor stage;
        final GitFileStatusNode node;
        final Object wrapper, request;
        final EditorWindow window;
        final VirtualFile preview, local;
        final Document localDocument;
        final long epoch, leftStamp, rightStamp, localStamp;
        final int selectionStart, selectionEnd, start, end;
        final boolean selectedRight, unsaved;
        final String leftText, rightText, localText, path, git;
        final Path root;
        final Charset charset;
        final AtomicBoolean canceled = new AtomicBoolean();
        Selection(Editor editor) {
            this.editor = editor;
            stage = StageNavigationService.processor(DataManager.getInstance().getDataContext(editor.getContentComponent()));
            node = node(stage); wrapper = stage.getCurrentChange(); request = viewer.getRequest(); epoch = generation;
            window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow(); preview = window == null ? null : window.getSelectedFile();
            local = node.getFilePath().getVirtualFile();
            localDocument = local == null ? null : FileDocumentManager.getInstance().getDocument(local);
            localText = localDocument == null ? null : localDocument.getText(); localStamp = localDocument == null ? -1 : localDocument.getModificationStamp();
            unsaved = localDocument != null && FileDocumentManager.getInstance().isDocumentUnsaved(localDocument);
            selectionStart = editor.getSelectionModel().getSelectionStart(); selectionEnd = editor.getSelectionModel().getSelectionEnd();
            start = editor.getDocument().getLineNumber(selectionStart);
            end = editor.getDocument().getLineNumber(Math.max(selectionStart, selectionEnd - 1)) + 1;
            selectedRight = editor.getDocument() == rightDocument;
            leftText = leftDocument.getText(); rightText = rightDocument.getText();
            leftStamp = leftDocument.getModificationStamp(); rightStamp = rightDocument.getModificationStamp();
            root = Path.of(node.getRoot().getPath()); path = root.relativize(Path.of(node.getFilePath().getPath())).toString().replace('\\', '/');
            Charset encoding = local == null ? StandardCharsets.UTF_8 : local.getCharset();
            if (local == null) for (var content : viewer.getRequest().getContents())
                if (content instanceof com.intellij.diff.contents.DocumentContent document && document.getCharset() != null) { encoding = document.getCharset(); break; }
            charset = encoding; git = GitExecutableManager.getInstance().getPathToGit(project);
        }
        boolean stable() {
            return !canceled.get() && !project.isDisposed() && !viewer.isDisposed()
                    && leftDocument.getModificationStamp() == leftStamp && rightDocument.getModificationStamp() == rightStamp
                    && (localDocument == null || localDocument.getModificationStamp() == localStamp);
        }
        boolean ownsWindow() {
            if (window == null || window.isDisposed() || preview == null || !preview.equals(window.getSelectedFile())
                    || FileEditorManagerEx.getInstanceEx(project).getCurrentWindow() != window) return false;
            var composite = window.getSelectedComposite();
            return composite != null && composite.getSelectedEditor() instanceof com.intellij.diff.editor.DiffEditorViewerFileEditor diff && diff.getEditorViewer() == stage;
        }
        boolean current() {
            return stable() && epoch == generation && !editor.isDisposed() && !stage.isDisposed() && ownsWindow()
                    && stage.getActiveViewer() == viewer && stage.getCurrentChange() == wrapper && viewer.getRequest() == request
                    && !viewer.hasPendingRediff() && editor.getCaretModel().getCaretCount() == 1
                    && editor.getSelectionModel().getSelectionStart() == selectionStart && editor.getSelectionModel().getSelectionEnd() == selectionEnd
                    && (localDocument == null || FileDocumentManager.getInstance().isDocumentUnsaved(localDocument) == unsaved);
        }
    }
    private Map<SelectionOperation, Plan> plans(Selection selected, GitIndexTransaction.Snapshot snapshot) throws Exception {
        String index = snapshot.staged() == null ? "" : snapshot.staged().text(), head = snapshot.committed() == null ? "" : snapshot.committed().text();
        boolean staged = selected.node.getKind() == NodeKind.STAGED;
        if (!index.equals(staged ? selected.rightText : selected.leftText) || staged && !head.equals(selected.leftText))
            throw new java.io.IOException("比较基线已变化，请刷新后重新选择");
        var result = new EnumMap<SelectionOperation, Plan>(SelectionOperation.class);
        if (staged) add(result, SelectionOperation.UNSTAGE, index, snapshot.staged() != null, head, snapshot.committed() != null, !selected.selectedRight, selected);
        else {
            String disk = GitIndexTransaction.normalized(selected.charset.newDecoder().decode(java.nio.ByteBuffer.wrap(snapshot.worktreeBytes())).toString());
            if (!selected.unsaved && disk.equals(selected.rightText))
                add(result, SelectionOperation.STAGE, index, snapshot.staged() != null, selected.rightText, snapshot.worktreeExists(), selected.selectedRight, selected);
            if (!selected.selectedRight) add(result, SelectionOperation.UNSTAGE, index, snapshot.staged() != null, head, snapshot.committed() != null, false, selected);
            // No file reconstruction, and never operate on a detached copy of LOCAL.
            if (selected.localDocument != null && selected.localDocument == rightDocument
                    && selected.localDocument.isWritable() && selected.local != null && selected.local.isWritable() && selected.localText.equals(selected.rightText))
                add(result, SelectionOperation.DISCARD, selected.localText, true, index, true, !selected.selectedRight, selected);
        }
        return Collections.unmodifiableMap(result);
    }
    private static void add(Map<SelectionOperation, Plan> plans, SelectionOperation operation, String before, boolean beforeExists,
                            String wanted, boolean wantedExists, boolean selectDesired, Selection selection) {
        var changes = ComparisonManager.getInstance().compareLines(before, wanted, ComparisonPolicy.DEFAULT, new EmptyProgressIndicator()).stream()
                .map(f -> new SelectedLines.Change(f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2())).toList();
        var result = SelectedLines.apply(before, beforeExists, wanted, wantedExists, changes, selectDesired, selection.start, selection.end);
        if (result.changed()) plans.put(operation, new Plan(before, result));
    }
    private void showPopup(List<SelectionOperation> operations) {
        closePopup(); var ready = prepared;
        if (ready == null || !ready.selection.current() || operations.isEmpty() || busy) return;
        var panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
        for (var operation : operations) {
            var button = new JButton(operation.text); button.setFocusable(false);
            if (operation == SelectionOperation.DISCARD) button.setToolTipText("撤销选中的本地修改；暂存内容保留，可用 Ctrl+Z 撤销");
            button.addActionListener(event -> { closePopup(); execute(operation); }); panel.add(button);
        }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null).setRequestFocus(false).setFocusable(false)
                .setCancelOnClickOutside(true).setModalContext(false).setCancelOnWindowDeactivation(true).createPopup();
        var editor = ready.selection.editor;
        Point point = editor.logicalPositionToXY(editor.offsetToLogicalPosition(editor.getSelectionModel().getSelectionEnd())); point.translate(8, editor.getLineHeight());
        popup.show(new RelativePoint(editor.getContentComponent(), point));
    }
    private void execute(SelectionOperation operation) {
        closePopup(); var ready = prepared;
        if (busy || ready == null || ready.snapshot == null || !ready.selection.current()) return;
        var plan = ready.plans.get(operation);
        if (plan == null) return;
        busy = true;
        // Leave action callbacks before a modal preview or a document command can dispatch events.
        later(() -> {
            if (!ready.selection.current()) { finish(null); return; }
            if (plan.result.needsPreview()) {
                var dialog = new SelectionPreview(project, plan.before, plan.result.text(), operation);
                if (!dialog.showAndGet()) { finish(null); return; }
                if (!ready.selection.current()) { finish("内容或选区已变化，已取消本次操作"); return; }
            }
            var guard = SelectionWriteGuard.acquire(ready.snapshot.index());
            if (guard == null) { finish("此仓库正在执行另一项选行操作，请稍后重试"); return; }
            var refresh = project.getService(StageRefreshService.class).begin(ready.selection.stage, ready.selection.editor, ready.selection.node);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    if (operation == SelectionOperation.DISCARD) {
                        ready.transaction.verify(ready.snapshot); later(() -> discard(ready, plan, refresh, guard));
                    } else {
                        ready.transaction.commit(ready.snapshot, plan.result.text(), plan.result.exists(), plan.result.baseOrigins(),
                                () -> ready.selection.stable() && (refresh == null || !refresh.canceled));
                        later(() -> {
                            try {
                                if (!project.isDisposed()) {
                                    if (refresh != null) StageRefreshService.markStale(viewer);
                                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshNioFiles(List.of(ready.snapshot.index()), true, false, null);
                                    git4idea.index.vfs.GitIndexFileSystemRefresher.refreshFilePaths(project, List.of(ready.selection.node.getFilePath()));
                                    GitStageTracker.getInstance(project).updateTrackerState();
                                    if (refresh != null) refresh.completed(plan.result.text(), plan.result.exists()); else if (!ready.selection.stage.isDisposed()) ready.selection.stage.refresh(true);
                                }
                            } finally { guard.close(); finish(null); }
                        });
                    }
                } catch (Exception failure) { later(() -> { guard.close(); if (refresh != null) refresh.cancel(); finish(failure.getMessage()); }); }
            });
        });
    }
    private void discard(Prepared ready, Plan plan, StageRefreshService.Refresh refresh, SelectionWriteGuard guard) {
        try {
            var selected = ready.selection;
            WriteCommandAction.writeCommandAction(project).withName(SelectionOperation.DISCARD.text).withGroupId(UUID.randomUUID().toString())
                    .shouldRecordActionForActiveDocument(false).run(() -> {
                if (!selected.current() || refresh != null && refresh.canceled || selected.local == null || !selected.local.isValid() || !selected.local.isWritable()
                        || FileDocumentManager.getInstance().getCachedDocument(selected.local) != selected.localDocument || !selected.localDocument.isWritable())
                    throw new IllegalStateException("内容或选区已变化，已取消 Discard");
                replaceChangedText(selected.localDocument, plan.result.text());
            });
            if (refresh != null) refresh.completedDocument(plan.result.text());
        } catch (Exception failure) { if (refresh != null) refresh.cancel(); reason(failure.getMessage()); }
        finally { guard.close(); finish(null); }
    }
    private static void replaceChangedText(Document document, String after) {
        String before = document.getText(); int prefix = 0, suffix = 0;
        while (prefix < before.length() && prefix < after.length() && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        while (suffix < before.length() - prefix && suffix < after.length() - prefix && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) suffix++;
        document.replaceString(prefix, before.length() - suffix, after.substring(prefix, after.length() - suffix));
    }
    private void finish(String failure) { busy = false; invalidate(); if (failure != null) reason(failure); }
    private void reason(String text) { if (!project.isDisposed()) project.getService(StageNavigationService.class).comparisonFailed(text); }
    private static void later(Runnable runnable) { ApplicationManager.getApplication().invokeLater(runnable, ModalityState.nonModal()); }
    private void closePopup() { if (popup != null) { var old = popup; popup = null; old.cancel(); } }
    private static final class SelectionPreview extends DialogWrapper {
        private final Project project;
        private final String before, candidate;
        private final SelectionOperation operation;
        SelectionPreview(Project project, String before, String candidate, SelectionOperation operation) {
            super(project); this.project = project; this.before = before; this.candidate = candidate; this.operation = operation;
            setTitle(operation == SelectionOperation.STAGE ? "确认暂存内容" : operation == SelectionOperation.UNSTAGE ? "确认取消暂存的内容" : "确认丢弃选中修改");
            setOKButtonText("确认 " + operation.name().substring(0, 1) + operation.name().substring(1).toLowerCase(Locale.ROOT)); init();
        }
        @Override protected JComponent createCenterPanel() {
            var panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JLabel("替换关系无法唯一确定。请检查本次结果；未选修改保持，取消不会写入。"), BorderLayout.NORTH);
            var factory = DiffContentFactory.getInstance(); var diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
            var request = new SimpleDiffRequest("本次选行操作", factory.create(project, before), factory.create(project, candidate),
                    operation == SelectionOperation.DISCARD ? "当前本地内容" : "当前暂存内容", "确认后的内容");
            request.putUserData(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY, true);
            diff.setRequest(request);
            panel.add(diff.getComponent(), BorderLayout.CENTER); panel.setPreferredSize(new java.awt.Dimension(1000, 600)); return panel;
        }
    }
}
