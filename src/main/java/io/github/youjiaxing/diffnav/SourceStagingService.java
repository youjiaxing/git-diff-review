package io.github.youjiaxing.diffnav;

import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Alarm;
import com.intellij.util.ui.JBUI;
import git4idea.config.GitExecutableManager;
import git4idea.index.GitStageTracker;
import git4idea.repo.GitRepositoryManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.FlowLayout;
import java.awt.event.MouseEvent;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;

/** Ordinary source editors share the index transaction with Diff, but keep source coordinates and lifetime. */
@Service(Service.Level.PROJECT)
public final class SourceStagingService implements Disposable {
    private static final Key<Binding> BINDING = Key.create("diffnav.source.staging");
    private final Project project;
    private final Map<Editor, Binding> bindings = new IdentityHashMap<>();
    private boolean disposed;

    public SourceStagingService(Project project) {
        this.project = project;
        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(EditorFactoryEvent event) { later(() -> attach(event.getEditor())); }
            @Override public void editorReleased(EditorFactoryEvent event) {
                var binding = bindings.remove(event.getEditor());
                if (binding != null) Disposer.dispose(binding);
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void selectionChanged(FileEditorManagerEvent event) { refresh(); }
            @Override public void fileClosed(FileEditorManager manager, VirtualFile file) { refresh(); }
        });
        GitStageTracker.getInstance(project).addListener(() -> later(this::refresh), this);
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
            @Override public void afterDocumentSaved(Document document) { later(() -> bindings.values().stream().filter(b -> b.editor.getDocument() == document).forEach(Binding::request)); }
            @Override public void fileContentReloaded(VirtualFile file, Document document) { later(() -> refresh()); }
        });
        for (var editor : EditorFactory.getInstance().getAllEditors()) attach(editor);
    }

    public void refresh() {
        if (disposed || project.isDisposed()) return;
        for (var binding : List.copyOf(bindings.values())) binding.request();
    }

    private void attach(Editor editor) {
        if (disposed || project.isDisposed() || editor.isDisposed() || editor.getProject() != project
                || editor.getEditorKind() != EditorKind.MAIN_EDITOR || editor.isViewer() || bindings.containsKey(editor)) return;
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || !file.isInLocalFileSystem() || file.isDirectory() || file.getFileType().isBinary()) return;
        var binding = new Binding(editor, file);
        bindings.put(editor, binding); editor.putUserData(BINDING, binding);
        Disposer.register(this, binding); binding.request();
    }

    private boolean enabled() {
        if (disposed || project.isDisposed()) return false;
        var options = NavigationSettings.get(project).getState();
        return !disposed && !project.isDisposed() && options.enabled && options.sourceStaging
                && git4idea.index.GitStageManagerKt.isStagingAreaAvailable(project);
    }

    private static Binding binding(Editor editor) { return editor == null ? null : editor.getUserData(BINDING); }
    static boolean handles(Editor editor) { return binding(editor) != null; }
    static List<SelectionOperation> operations(Editor editor) {
        var b = binding(editor); if (b == null || b.ready == null || b.busy || !b.ready.ticket.current()) return List.of();
        return List.copyOf(b.ready.plans.keySet());
    }
    static boolean needsStagedDiff(Editor editor) {
        var b = binding(editor); return b != null && !b.busy && b.ready != null && b.ready.stagedDiff && b.ready.ticket.current();
    }
    static void stagedDiff(Editor editor) {
        var b = binding(editor);
        if (needsStagedDiff(editor)) b.openStaged();
    }
    static void invoke(Editor editor, SelectionOperation operation) {
        var b = binding(editor); if (b != null && operations(editor).contains(operation)) b.execute(operation);
    }
    static void invoke(Editor editor) {
        var b = binding(editor); if (b == null) return;
        var ops = operations(editor);
        if (ops.size() == 1) b.execute(ops.getFirst());
        else if (!ops.isEmpty()) {
            var group = new DefaultActionGroup();
            for (var op : ops) group.add(new com.intellij.openapi.project.DumbAwareAction(op.text) {
                @Override public void actionPerformed(AnActionEvent event) { invoke(editor, op); }
            });
            JBPopupFactory.getInstance().createActionGroupPopup("选中行操作", group,
                    DataManager.getInstance().getDataContext(editor.getContentComponent()),
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true).showInBestPositionFor(editor);
        }
    }

    boolean showMarker(Editor editor, int line, boolean point, String status, MouseEvent event, Runnable nativePopup) {
        var b = binding(editor);
        if (b == null || !b.supported() || b.busy || line < 0) return false;
        b.openMarker(line, point, status, event, nativePopup);
        return true;
    }

    private record Plan(String before, SelectedLines.Result result) {}
    private record Ready(Ticket ticket, GitIndexTransaction transaction, GitIndexTransaction.Snapshot snapshot,
                         Map<SelectionOperation, Plan> plans, boolean stagedDiff, String summary) {}

    private final class Ticket {
        final Binding binding;
        final long generation, stamp;
        final int selectionStart, selectionEnd, start, end;
        final boolean selected, unsaved;
        final String text, git;
        final Path root;
        final String path;
        final EditorWindow window;
        Ticket(Binding binding, VirtualFile repositoryRoot) { this(binding, repositoryRoot, -1); }
        Ticket(Binding binding, VirtualFile repositoryRoot, int markerLine) {
            this.binding = binding; generation = binding.generation;
            var editor = binding.editor; var document = editor.getDocument();
            stamp = document.getModificationStamp(); text = document.getText();
            var selection = editor.getSelectionModel();
            selected = markerLine >= 0 || selection.hasSelection() && editor.getCaretModel().getCaretCount() == 1;
            selectionStart = selection.getSelectionStart(); selectionEnd = selection.getSelectionEnd();
            start = markerLine >= 0 ? markerLine : document.getLineNumber(selectionStart);
            end = markerLine >= 0 ? markerLine + 1 : document.getLineNumber(Math.max(selectionStart, selectionEnd - 1)) + 1;
            unsaved = FileDocumentManager.getInstance().isDocumentUnsaved(document);
            root = Path.of(repositoryRoot.getPath());
            path = root.relativize(Path.of(binding.file.getPath())).toString().replace('\\', '/');
            git = GitExecutableManager.getInstance().getPathToGit(project);
            window = FileEditorManagerEx.getInstanceEx(project).getCurrentWindow();
        }
        boolean stable() {
            return !binding.closed && !project.isDisposed() && generation == binding.generation
                    && !binding.editor.isDisposed() && binding.editor.getDocument().getModificationStamp() == stamp;
        }
        boolean current() {
            if (!stable() || !enabled() || !binding.file.isValid() || binding.editor.getCaretModel().getCaretCount() != 1) return false;
            var editor = binding.editor;
            if (editor.getSelectionModel().getSelectionStart() != selectionStart || editor.getSelectionModel().getSelectionEnd() != selectionEnd
                    || FileDocumentManager.getInstance().isDocumentUnsaved(editor.getDocument()) != unsaved) return false;
            if (window == null || window.isDisposed() || FileEditorManagerEx.getInstanceEx(project).getCurrentWindow() != window
                    || !binding.file.equals(window.getSelectedFile())) return false;
            var composite = window.getSelectedComposite();
            return composite != null && composite.getSelectedEditor() instanceof TextEditor textEditor && textEditor.getEditor() == editor;
        }
    }

    private final class Binding implements Disposable {
        final Editor editor;
        final VirtualFile file;
        final Alarm alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        final JPanel header = new JPanel(new BorderLayout(8, 0));
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        final JLabel label = new JLabel();
        final JButton stage = new JButton(SelectionOperation.STAGE.text), unstage = new JButton(SelectionOperation.UNSTAGE.text);
        final JButton stagedDiff = new JButton("在 Diff 中选择取消暂存");
        volatile long generation;
        volatile boolean closed;
        boolean busy;
        boolean refreshDeferred;
        Ready ready;
        MarkerPopup markerPopup;
        JComponent originalHeader;
        JPanel wrapper;
        Binding(Editor editor, VirtualFile file) {
            this.editor = editor; this.file = file;
            header.setBorder(JBUI.Borders.empty(5, 9)); actions.setOpaque(false); header.add(label, BorderLayout.CENTER);
            header.add(actions, BorderLayout.EAST);
            for (var button : List.of(stage, unstage, stagedDiff)) { button.setFocusable(false); actions.add(button); }
            stage.addActionListener(e -> execute(SelectionOperation.STAGE)); unstage.addActionListener(e -> execute(SelectionOperation.UNSTAGE));
            stagedDiff.addActionListener(e -> openStaged());
            stage.setToolTipText("仅暂存选中的修改；源码必须已保存");
            stagedDiff.setToolTipText("当前源码无法唯一对应暂存版本；打开 HEAD ↔ INDEX 后重新选行，打开本身不写入");
            editor.getSelectionModel().addSelectionListener(new SelectionListener() {
                @Override public void selectionChanged(SelectionEvent event) { request(); }
            }, this);
            var focus = new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) {
                    if (refreshDeferred) {
                        refreshDeferred = false;
                        request();
                    }
                }
            };
            editor.getContentComponent().addFocusListener(focus);
            Disposer.register(this, () -> editor.getContentComponent().removeFocusListener(focus));
            editor.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void beforeDocumentChange(DocumentEvent event) { invalidate(); }
                @Override public void documentChanged(DocumentEvent event) { request(); }
            }, this);
        }
        void invalidate() { generation++; ready = null; alarm.cancelAllRequests(); closeMarker(); }
        void closeMarker() { var old = markerPopup; markerPopup = null; if (old != null) old.close(); }
        boolean supported() {
            if (closed || busy || !enabled() || editor.isDisposed() || editor.getCaretModel().getCaretCount() != 1
                    || !file.isValid() || !file.isWritable() || !editor.getDocument().isWritable()
                    || file.getLength() > 8 * 1024 * 1024 || editor.getDocument().getTextLength() > 8 * 1024 * 1024
                    || file.is(com.intellij.openapi.vfs.VFileProperty.SYMLINK)) return false;
            var repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file);
            if (repository == null) return false;
            var status = git4idea.index.GitStageTrackerKt.status(GitStageTracker.getInstance(project), file);
            return status == null || !status.isConflicted() && status.getOrigPath() == null && status.getIndex() != 'R'
                    && status.getIndex() != 'C' && status.getWorkTree() != 'R' && status.getWorkTree() != 'C';
        }
        void openMarker(int line, boolean point, String state, MouseEvent event, Runnable nativePopup) {
            closeMarker();
            var repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file);
            if (repository == null) return;
            var ticket = new Ticket(this, repository.getRoot(), line);
            markerPopup = new MarkerPopup(this, ticket, point, state, event, nativePopup);
        }
        void request() {
            if (closed || editor.isDisposed()) return;
            if (focusIsOutsideEditor()) {
                // Ctrl+F changes the editor selection while focus belongs to the
                // Find bar. Do not rebuild the editor header from that transient
                // selection: replacing the header would make the Find bar lose
                // focus and route the next Enter into the source editor.
                refreshDeferred = true;
                return;
            }
            refreshDeferred = false;
            invalidate();
            if (!enabled()) { hide(); return; }
            buttons();
            if (wrapper != null) label.setText(busy ? "正在更新暂存版本…" : "正在核实暂存状态…");
            if (!busy) alarm.addRequest(this::prepare, 180, ModalityState.nonModal());
        }
        void prepare() {
            if (!supported()) { hide(); return; }
            var repository = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file);
            var ticket = new Ticket(this, repository.getRoot());
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    var transaction = new GitIndexTransaction(ticket.git);
                    var snapshot = transaction.capture(ticket.root, ticket.path, file.getCharset());
                    var result = preparePlan(ticket, transaction, snapshot);
                    later(() -> {
                        if (!ticket.stable() || !enabled()) return;
                        if (ticket.unsaved != FileDocumentManager.getInstance().isDocumentUnsaved(editor.getDocument())) {
                            request(); return; // Saving changes eligibility without necessarily changing the document stamp.
                        }
                        if (focusIsOutsideEditor()) {
                            refreshDeferred = true;
                            return;
                        }
                        ready = result;
                        if (result.summary.isEmpty()) hide(); else {
                            show();
                            label.setText(!ticket.selected && !project.getService(SourceMarkerService.class).active(editor.getDocument())
                                    ? "暂存状态使用 IDE 原生标记；选中代码可查看文字状态和操作" : result.summary);
                            buttons();
                        }
                        com.intellij.ide.ActivityTracker.getInstance().inc();
                    });
                } catch (Exception failure) { later(() -> { if (ticket.stable()) { hide(); ready = null; } }); }
            });
        }
        void show() {
            if (focusIsOutsideEditor()) { refreshDeferred = true; return; }
            if (editor.getHeaderComponent() == wrapper && wrapper != null) return;
            originalHeader = editor.getHeaderComponent(); wrapper = new JPanel(new BorderLayout());
            if (originalHeader != null) wrapper.add(originalHeader, BorderLayout.NORTH);
            wrapper.add(header, BorderLayout.SOUTH); editor.setHeaderComponent(wrapper);
        }
        void hide() {
            if (focusIsOutsideEditor()) { refreshDeferred = true; return; }
            if (wrapper != null && !editor.isDisposed() && editor.getHeaderComponent() == wrapper) editor.setHeaderComponent(originalHeader);
            wrapper = null; originalHeader = null;
        }
        boolean focusIsOutsideEditor() {
            Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            return focusOwner == null || !SwingUtilities.isDescendingFrom(focusOwner, editor.getContentComponent());
        }
        void buttons() {
            boolean valid = !busy && ready != null && ready.ticket.selected && ready.ticket.current();
            stage.setVisible(valid && ready.plans.containsKey(SelectionOperation.STAGE));
            unstage.setVisible(valid && ready.plans.containsKey(SelectionOperation.UNSTAGE));
            stagedDiff.setVisible(valid && ready.stagedDiff);
        }
        void openStaged() {
            if (!needsStagedDiff(editor)) return;
            project.getService(SourceCompareService.class).openStaged(editor);
        }
        void execute(SelectionOperation operation) { execute(ready, operation); }
        void execute(Ready result, SelectionOperation operation) {
            if (busy || result == null || !result.ticket.selected || !result.ticket.current()) return;
            var plan = result.plans.get(operation); if (plan == null) return;
            busy = true; buttons(); label.setText("正在核实选中行…");
            later(() -> {
                if (!result.ticket.current()) { finish("内容或选区已变化，已取消本次操作"); return; }
                if (plan.result.needsPreview() && !new LineStaging.SelectionPreview(project, plan.before, plan.result.text(), operation).showAndGet()) { finish(null); return; }
                if (!result.ticket.current()) { finish("内容或选区已变化，已取消本次操作"); return; }
                var guard = SelectionWriteGuard.acquire(result.snapshot.index());
                if (guard == null) { finish("此仓库正在执行另一项选行操作，请稍后重试"); return; }
                label.setText("正在更新暂存版本…");
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        result.transaction.commit(result.snapshot, plan.result.text(), plan.result.exists(), plan.result.baseOrigins(), result.ticket::stable);
                        later(() -> {
                            guard.close();
                            if (!project.isDisposed()) {
                                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshNioFiles(List.of(result.snapshot.index()), true, false, null);
                                git4idea.index.vfs.GitIndexFileSystemRefresher.refreshFilePaths(project,
                                        List.of(com.intellij.vcsUtil.VcsUtil.getFilePath(file)));
                                GitStageTracker.getInstance(project).updateTrackerState();
                            }
                            finish(null);
                        });
                    } catch (Exception failure) { later(() -> { guard.close(); finish(failure.getMessage()); }); }
                });
            });
        }
        void finish(String failure) {
            busy = false;
            if (failure != null && !project.isDisposed()) project.getService(StageNavigationService.class).comparisonFailed(failure);
            request();
        }
        @Override public void dispose() {
            closed = true; invalidate(); hide();
            editor.putUserData(BINDING, null);
        }
    }

    /** Keep the platform gutter popup and code editors; narrow INDEX↔LOCAL to the clicked real hunk. */
    private final class MarkerPopup {
        final Binding binding;
        final Ticket ticket;
        final com.intellij.openapi.util.CheckedDisposable lifetime;
        Disposable popupLifetime;
        com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel panel;
        com.intellij.ui.EditorTextField before, after;
        Ready prepared;
        MarkerPopup(Binding binding, Ticket ticket, boolean point, String state, MouseEvent event, Runnable nativePopup) {
            this.binding = binding; this.ticket = ticket;
            lifetime = Disposer.newCheckedDisposable(binding, "Native source marker comparison");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    var tx = new GitIndexTransaction(ticket.git);
                    var snapshot = tx.capture(ticket.root, ticket.path, binding.file.getCharset());
                    String index = snapshot.staged() == null ? "" : snapshot.staged().text();
                    String head = snapshot.committed() == null ? "" : snapshot.committed().text();
                    var localChanges = changes(index, ticket.text);
                    SelectedLines.Change target = null;
                    for (var c : localChanges) if (point ? c.desiredStart() == c.desiredEnd() && c.desiredStart() == ticket.start
                            : c.desiredStart() <= ticket.start && ticket.start < c.desiredEnd()) { target = c; break; }
                    if (target == null) {
                        later(() -> { if (valid()) { close(); nativePopup.run(); } });
                        return;
                    }
                    var rawChange = target;
                    var change = SelectedLines.physicalHunk(index, ticket.text, rawChange);
                    var plans = new EnumMap<SelectionOperation, Plan>(SelectionOperation.class);
                    var stage = SelectedLines.applyHunk(index, snapshot.staged() != null, ticket.text, snapshot.worktreeExists(), rawChange);
                    String disk = GitIndexTransaction.normalized(snapshot.charset().newDecoder().decode(ByteBuffer.wrap(snapshot.worktreeBytes())).toString());
                    if (stage.changed() && !stage.unresolved() && !ticket.unsaved && disk.equals(ticket.text))
                        plans.put(SelectionOperation.STAGE, new Plan(index, stage));
                    if (change.baseStart() < change.baseEnd()) {
                        var unstage = SelectedLines.apply(index, snapshot.staged() != null, head, snapshot.committed() != null,
                                changes(index, head), false, change.baseStart(), change.baseEnd());
                        if (unstage.changed() && !unstage.unresolved()) plans.put(SelectionOperation.UNSTAGE, new Plan(index, unstage));
                    }
                    var result = new Ready(ticket, tx, snapshot, Collections.unmodifiableMap(plans), false, "");
                    var indexLines = SelectedLines.lines(index); var localLines = SelectedLines.lines(ticket.text);
                    int beforeStart = Math.max(0, change.baseStart() - 2), afterStart = Math.max(0, change.desiredStart() - 2);
                    int beforeEnd = Math.min(indexLines.size(), change.baseEnd() + 2), afterEnd = Math.min(localLines.size(), change.desiredEnd() + 2);
                    String oldText = String.join("", indexLines.subList(Math.min(beforeStart, beforeEnd), beforeEnd));
                    String newText = String.join("", localLines.subList(Math.min(afterStart, afterEnd), afterEnd));
                    var words = com.intellij.diff.comparison.ComparisonManager.getInstance().compareWords(oldText, newText,
                            com.intellij.diff.comparison.ComparisonPolicy.DEFAULT, new com.intellij.openapi.progress.EmptyProgressIndicator());
                    later(() -> {
                        if (!valid()) return;
                        try {
                        prepared = result;
                        com.intellij.openapi.vcs.ex.LineStatusMarkerPopupService.getInstance().buildAndShowPopup(lifetime, binding.editor,
                                event.getPoint(), disposable -> {
                            popupLifetime = disposable;
                            var editor = binding.editor;
                            before = com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel.createTextField(editor, oldText);
                            after = com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel.createTextField(editor, newText);
                            var fileType = binding.file.getFileType();
                            var oldDocument = EditorFactory.getInstance().createDocument(index);
                            com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel.installBaseEditorSyntaxHighlighters(project, before, oldDocument,
                                    textRange(oldDocument, beforeStart, beforeEnd), fileType);
                            com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel.installBaseEditorSyntaxHighlighters(project, after, editor.getDocument(),
                                    textRange(editor.getDocument(), afterStart, afterEnd), fileType);
                            com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel.installPopupEditorWordHighlighters(before, words);
                            var reversed = words.stream().map(f -> (com.intellij.diff.fragments.DiffFragment) new com.intellij.diff.fragments.DiffFragmentImpl(
                                    f.getStartOffset2(), f.getEndOffset2(), f.getStartOffset1(), f.getEndOffset1())).toList();
                            com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel.installPopupEditorWordHighlighters(after, reversed);
                            int width = Math.max(440, Math.min(900, editor.getContentComponent().getWidth() - 40));
                            before.setPreferredSize(new java.awt.Dimension(width, Math.min(220, Math.max(45, (beforeEnd - beforeStart) * editor.getLineHeight() + 10))));
                            after.setPreferredSize(new java.awt.Dimension(width, Math.min(220, Math.max(45, (afterEnd - afterStart) * editor.getLineHeight() + 10))));
                            var body = new JPanel(); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
                            body.add(new JLabel("暂存版本 · " + linesLabel(change.baseStart(), change.baseEnd())));
                            body.add(before); body.add(new JLabel("本地 · " + linesLabel(change.desiredStart(), change.desiredEnd()))); body.add(after);
                            var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                            for (var op : result.plans.keySet()) {
                                var button = new JButton(op == SelectionOperation.STAGE ? "暂存这处修改" : "取消这段暂存");
                                button.addActionListener(ignored -> { if (valid()) { close(); binding.execute(result, op); } }); controls.add(button);
                            }
                            if (ticket.unsaved) controls.add(new JLabel("本地尚未保存"));
                            // No shortcuts are registered on the source editor. The platform helper's
                            // cleanup mutates ActionUtil's immutable empty list when given no actions.
                            var toolbar = ActionManager.getInstance().createActionToolbar("DiffReviewSourceMarker", new DefaultActionGroup(), true);
                            toolbar.setReservePlaceAutoPopupIcon(false);
                            toolbar.setTargetComponent(editor.getComponent());
                            panel = com.intellij.openapi.vcs.ex.LineStatusMarkerPopupPanel.create(editor, toolbar, body, controls);
                            return panel;
                        });
                        } catch (Exception failure) {
                            close();
                            if (ticket.current()) nativePopup.run();
                        }
                    });
                } catch (Exception failure) {
                    later(() -> { if (valid()) { close(); nativePopup.run(); } });
                }
            });
        }
        boolean valid() { return binding.markerPopup == this && !lifetime.isDisposed()
                && (popupLifetime == null || !Disposer.isDisposed(popupLifetime)) && ticket.current(); }
        void close() { if (!lifetime.isDisposed()) Disposer.dispose(lifetime); }
        String linesLabel(int start, int end) { return start == end ? "第 " + (start + 1) + " 行前" : start + 1 == end ? "第 " + end + " 行" : "第 " + (start + 1) + "–" + end + " 行"; }
        com.intellij.openapi.util.TextRange textRange(Document document, int start, int end) {
            int count = document.getLineCount();
            int lo = count == 0 || start >= count ? document.getTextLength() : document.getLineStartOffset(start);
            int hi = count == 0 || end >= count ? document.getTextLength() : document.getLineStartOffset(end);
            return new com.intellij.openapi.util.TextRange(lo, hi);
        }
    }

    private Ready preparePlan(Ticket ticket, GitIndexTransaction transaction, GitIndexTransaction.Snapshot snapshot) throws Exception {
        String index = snapshot.staged() == null ? "" : snapshot.staged().text();
        String head = snapshot.committed() == null ? "" : snapshot.committed().text();
        var localChanges = changes(index, ticket.text);
        var stagedChanges = changes(index, head);
        var plans = new EnumMap<SelectionOperation, Plan>(SelectionOperation.class);
        boolean diff = false;
        String summary = localChanges.isEmpty() && stagedChanges.isEmpty() ? "" : "暂存状态：实心＝未暂存 · 空心＝已暂存 · 半填充＝已暂存，另有修改";
        if (ticket.selected) {
            var stage = SelectedLines.apply(index, snapshot.staged() != null, ticket.text, snapshot.worktreeExists(), localChanges, true, ticket.start, ticket.end);
            String disk = GitIndexTransaction.normalized(snapshot.charset().newDecoder().decode(ByteBuffer.wrap(snapshot.worktreeBytes())).toString());
            if (stage.changed() && !ticket.unsaved && disk.equals(ticket.text)) plans.put(SelectionOperation.STAGE, new Plan(index, stage));
            boolean staged = SourceLineMapping.containsStaged(localChanges, stagedChanges, ticket.start, ticket.end);
            if (staged) {
                var mapped = SourceLineMapping.toIndex(index, ticket.text, localChanges, ticket.start, ticket.end);
                if (mapped == null) diff = true;
                else {
                    var unstage = SelectedLines.apply(index, snapshot.staged() != null, head, snapshot.committed() != null,
                            stagedChanges, false, mapped.start(), mapped.end());
                    if (unstage.changed() && !unstage.unresolved()) plans.put(SelectionOperation.UNSTAGE, new Plan(index, unstage));
                    else diff = true; // A staged deletion has no selectable line on the INDEX side.
                }
            }
            summary = "选中 " + (ticket.start + 1) + "–" + ticket.end + " 行："
                    + (stage.changed() ? staged ? "已暂存，另有修改" : "未暂存修改" : staged ? "已暂存内容" : "无可操作修改")
                    + (stage.changed() && ticket.unsaved ? "（先保存源码才能 Stage）" : "");
            if (stage.unresolved()) summary = "无法定位选中行对应的替换范围，请在 Diff 中扩大选区覆盖完整替换块";
        }
        return new Ready(ticket, transaction, snapshot, Collections.unmodifiableMap(plans), diff, summary);
    }
    private static List<SelectedLines.Change> changes(String before, String after) {
        return com.intellij.diff.comparison.ComparisonManager.getInstance().compareLines(before, after,
                com.intellij.diff.comparison.ComparisonPolicy.DEFAULT, new com.intellij.openapi.progress.EmptyProgressIndicator()).stream()
                .map(f -> new SelectedLines.Change(f.getStartLine1(), f.getEndLine1(), f.getStartLine2(), f.getEndLine2())).toList();
    }
    private static void later(Runnable runnable) { ApplicationManager.getApplication().invokeLater(runnable, ModalityState.nonModal()); }
    @Override public void dispose() { disposed = true; }
}
