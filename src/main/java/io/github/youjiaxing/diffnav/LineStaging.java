package io.github.youjiaxing.diffnav;

import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.ui.awt.RelativePoint;
import git4idea.config.GitExecutableManager;
import git4idea.index.GitStageTracker;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.NodeKind;
import java.awt.BorderLayout;
import java.awt.Point;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Selection UI and version checks around the isolated line-plan and index transaction. */
public final class LineStaging {
    private static final Key<LineStaging> CONTROLLER = Key.create("diffnav.line.staging");
    private final DiffViewerBase viewer;
    private final List<? extends Editor> editors;
    private final Document leftDocument;
    private final Document rightDocument;
    private final Project project;
    private final AtomicBoolean busy = new AtomicBoolean();
    private JBPopup popup;

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
        for (Editor editor : controller.editors) {
            editor.putUserData(CONTROLLER, controller);
            editor.addEditorMouseListener(new EditorMouseListener() {
                @Override public void mouseReleased(EditorMouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event.getMouseEvent())) controller.offer(editor);
                }
            }, viewer);
            editor.getSelectionModel().addSelectionListener(new com.intellij.openapi.editor.event.SelectionListener() {
                @Override public void selectionChanged(com.intellij.openapi.editor.event.SelectionEvent event) { controller.closePopup(); }
            }, viewer);
        }
        Disposer.register(viewer, controller::closePopup);
    }

    static boolean available(Editor editor) { return editor != null && editor.getUserData(CONTROLLER) != null; }
    static void invoke(Editor editor) {
        if (available(editor)) editor.getUserData(CONTROLLER).execute(editor);
    }

    private void offer(Editor editor) {
        closePopup();
        var stage = StageNavigationService.processor(DataManager.getInstance().getDataContext(editor.getContentComponent()));
        if (!valid(stage, editor)) return;
        boolean unstage = node(stage).getKind() == NodeKind.STAGED;
        var button = new JButton(unstage ? "Unstage 选中行" : "Stage 选中行");
        button.setFocusable(false);
        button.addActionListener(event -> { closePopup(); execute(editor); });
        var panel = new JPanel(new BorderLayout()); panel.add(button);
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
                .setRequestFocus(false).setFocusable(false).setCancelOnClickOutside(true)
                .setModalContext(false)
                .setCancelOnWindowDeactivation(true).createPopup();
        Point point = editor.logicalPositionToXY(editor.offsetToLogicalPosition(editor.getSelectionModel().getSelectionEnd()));
        point.translate(8, editor.getLineHeight());
        popup.show(new RelativePoint(editor.getContentComponent(), point));
    }

    private boolean valid(GitStageDiffRequestProcessor stage, Editor editor) {
        if (busy.get() || viewer.isDisposed() || viewer.hasPendingRediff() || editor.isDisposed()
                || !editor.getSelectionModel().hasSelection() || editor.getCaretModel().getCaretCount() != 1
                || stage == null || stage.getActiveViewer() != viewer || node(stage) == null) return false;
        NodeKind kind = node(stage).getKind();
        return kind == NodeKind.STAGED || kind == NodeKind.UNSTAGED || kind == NodeKind.UNTRACKED;
    }

    private static GitFileStatusNode node(GitStageDiffRequestProcessor stage) {
        return stage.getCurrentChange() != null && stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node ? node : null;
    }

    private void execute(Editor selected) {
        closePopup();
        var context = DataManager.getInstance().getDataContext(selected.getContentComponent());
        var stage = StageNavigationService.processor(context);
        if (!valid(stage, selected)) return;
        GitFileStatusNode node = node(stage);
        if (node.getStatus().isConflicted() || node.getStatus().getOrigPath() != null) {
            message("冲突或重命名文件请先使用原生暂存操作"); return;
        }
        boolean unstage = node.getKind() == NodeKind.STAGED;
        Document left = leftDocument, right = rightDocument;
        Document base = unstage ? right : left, desired = unstage ? left : right;
        boolean selectDesired = selected.getDocument() == desired;
        int start = selected.getDocument().getLineNumber(selected.getSelectionModel().getSelectionStart());
        int end = selected.getDocument().getLineNumber(Math.max(selected.getSelectionModel().getSelectionStart(), selected.getSelectionModel().getSelectionEnd() - 1)) + 1;
        String before = base.getText(), wanted = desired.getText();
        long leftStamp = left.getModificationStamp(), rightStamp = right.getModificationStamp();
        Path root = Path.of(node.getRoot().getPath());
        String path = root.relativize(Path.of(node.getFilePath().getPath())).toString().replace('\\', '/');
        var local = node.getFilePath().getVirtualFile();
        Charset charset = local == null ? StandardCharsets.UTF_8 : local.getCharset();
        if (local == null) {
            for (var content : viewer.getRequest().getContents()) {
                if (content instanceof com.intellij.diff.contents.DocumentContent documentContent && documentContent.getCharset() != null) {
                    charset = documentContent.getCharset(); break;
                }
            }
        }
        Charset fileCharset = charset;
        var transaction = new GitIndexTransaction(GitExecutableManager.getInstance().getPathToGit(project));
        Document localDocument = local == null ? null : FileDocumentManager.getInstance().getCachedDocument(local);
        boolean unsavedSource = localDocument != null && FileDocumentManager.getInstance().isDocumentUnsaved(localDocument);
        if (!unstage && unsavedSource) { message("本地文件有未保存编辑，请先保存后再按行暂存；插件不会自动保存文件"); return; }
        if (!busy.compareAndSet(false, true)) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var snapshot = transaction.capture(root, path, fileCharset);
                var oldEntry = snapshot.staged();
                var desiredEntry = unstage ? snapshot.committed() : null;
                String diskText = fileCharset.newDecoder().decode(java.nio.ByteBuffer.wrap(snapshot.worktreeBytes())).toString();
                if (!before.equals(oldEntry == null ? "" : oldEntry.text())
                        || unstage && !wanted.equals(desiredEntry == null ? "" : desiredEntry.text())
                        || !unstage && !wanted.equals(GitIndexTransaction.normalized(diskText))) {
                    throw new java.io.IOException("Diff 版本已变化或暂存编辑尚未保存，请刷新后重试");
                }
                List<SelectedLines.Change> changes = ComparisonManager.getInstance().compareLines(before, wanted,
                        ComparisonPolicy.DEFAULT, new EmptyProgressIndicator()).stream()
                        .map(f -> new SelectedLines.Change(f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2())).toList();
                boolean desiredExists = unstage ? desiredEntry != null : snapshot.worktreeExists();
                var result = SelectedLines.apply(before, oldEntry != null, wanted, desiredExists, changes, selectDesired, start, end);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!unchanged(left, right, leftStamp, rightStamp)) { busy.set(false); message("内容已变化，请重新选择"); return; }
                    if (!result.changed()) { busy.set(false); return; }
                    String candidate = result.text();
                    boolean exists = result.exists();
                    if (result.needsPreview()) {
                        var dialog = new SelectionPreview(project, before, oldEntry != null, candidate, exists, unstage);
                        if (!dialog.showAndGet()) { busy.set(false); return; }
                        candidate = dialog.candidate();
                        exists = !candidate.isEmpty() || result.exists();
                    }
                    String finalText = candidate;
                    boolean finalExists = exists;
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            transaction.commit(snapshot, finalText, finalExists, finalText.equals(result.text()) ? result.baseOrigins() : null,
                                    () -> unchanged(left, right, leftStamp, rightStamp));
                            ApplicationManager.getApplication().invokeLater(() -> {
                                busy.set(false);
                                // Refresh from Git; never save the source documents as part of staging.
                                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshNioFiles(List.of(snapshot.index()), true, false, null);
                                git4idea.index.vfs.GitIndexFileSystemRefresher.refreshFilePaths(project, List.of(node.getFilePath()));
                                GitStageTracker.getInstance(project).updateTrackerState();
                                if (!stage.isDisposed()) stage.refresh(true);
                            }, ModalityState.nonModal());
                        } catch (Exception failure) { fail(failure); }
                    });
                }, ModalityState.nonModal());
            } catch (Exception failure) { fail(failure); }
        });
    }

    private boolean unchanged(Document left, Document right, long a, long b) {
        return !project.isDisposed() && !viewer.isDisposed() && left.getModificationStamp() == a && right.getModificationStamp() == b;
    }
    private void fail(Exception failure) {
        ApplicationManager.getApplication().invokeLater(() -> { busy.set(false); if (!project.isDisposed()) message(failure.getMessage()); }, ModalityState.nonModal());
    }
    private void message(String text) { Messages.showInfoMessage(project, text, "按行暂存"); }
    private void closePopup() { if (popup != null) { var old = popup; popup = null; old.cancel(); } }

    private static final class SelectionPreview extends DialogWrapper {
        private final Project project;
        private final String before;
        private final boolean oldExists, newExists;
        private final Document candidate;
        SelectionPreview(Project project, String before, boolean oldExists, String proposed, boolean newExists, boolean unstage) {
            super(project);
            this.project = project; this.before = before; this.oldExists = oldExists; this.newExists = newExists;
            candidate = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(proposed);
            setTitle(unstage ? "确认取消暂存的内容" : "确认暂存内容");
            setOKButtonText(unstage ? "确认 Unstage" : "确认 Stage"); init();
        }
        @Override protected JComponent createCenterPanel() {
            var panel = new JPanel(new BorderLayout(0, 8));
            panel.add(new JLabel("替换关系无法唯一确定。请检查右侧结果，可直接编辑；取消不会写入暂存区。"), BorderLayout.NORTH);
            var factory = DiffContentFactory.getInstance();
            var after = factory.create(project, candidate);
            var diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
            diff.setRequest(new SimpleDiffRequest("本次暂存区变化", factory.create(project, before), after,
                    oldExists ? "当前暂存内容" : "当前不存在", newExists ? "操作后的暂存内容（可编辑）" : "操作后删除文件条目"));
            panel.add(diff.getComponent(), BorderLayout.CENTER);
            panel.setPreferredSize(new java.awt.Dimension(1000, 600));
            return panel;
        }
        String candidate() { return candidate.getText(); }
    }
}
