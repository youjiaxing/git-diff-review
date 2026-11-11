package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import git4idea.index.actions.GitStageCompareLocalWithStagedAction;

/** Declarative override: the platform owns registration and restoration on plugin unload. */
public final class CompareWithStagedAction extends AnActionWrapper implements OverridingAction {
    private volatile AnAction original;
    public CompareWithStagedAction() { super(new GitStageCompareLocalWithStagedAction()); }
    private synchronized AnAction original() {
        if (original == null) {
            AnAction base = ActionManagerEx.getInstanceEx().asActionRuntimeRegistrar().getBaseAction(this);
            if (base instanceof ActionStub stub) {
                // The native class has only its immutable LOCAL/STAGED constructor parameters.
                // Preserve the registered stub's localized presentation and shortcut metadata.
                if (!stub.getClassName().equals(GitStageCompareLocalWithStagedAction.class.getName()))
                    throw new IllegalStateException("Unsupported comparison action override: " + stub.getClassName());
                var nativeAction = new GitStageCompareLocalWithStagedAction();
                stub.initAction(nativeAction);
                base = nativeAction;
            }
            original = base == null ? super.getDelegate() : base;
            copyFrom(original);
        }
        return original;
    }
    @Override public AnAction getDelegate() { return original(); }
    @Override public void update(AnActionEvent event) {
        var delegate = original();
        event.getPresentation().setText(delegate.getTemplatePresentation().getText());
        event.getPresentation().setDescription(delegate.getTemplatePresentation().getDescription());
        delegate.update(event);
        if (event.getPresentation().isVisible() && untrackedSource(event)) event.getPresentation().setEnabled(true);
    }
    @Override public void actionPerformed(AnActionEvent event) { original().actionPerformed(event); }
    static AnAction nativeAction(AnAction action) { return action instanceof CompareWithStagedAction wrapper ? wrapper.original() : action; }
    private static boolean untrackedSource(AnActionEvent event) {
        var project = event.getProject();
        var editor = event.getData(CommonDataKeys.EDITOR);
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || project.isDisposed() || !NavigationSettings.get(project).getState().enabled
                || editor == null || editor.isDisposed() || editor.getProject() != project || editor.getEditorKind() != EditorKind.MAIN_EDITOR
                || event.getData(com.intellij.diff.tools.util.DiffDataKeys.DIFF_CONTEXT) != null
                || file == null || !file.isValid() || !file.isInLocalFileSystem() || file.isDirectory() || file.getFileType().isBinary()
                || !file.equals(FileDocumentManager.getInstance().getFile(editor.getDocument()))
                || !git4idea.index.GitStageManagerKt.isStagingAreaAvailable(project)) return false;
        var status = git4idea.index.GitStageTrackerKt.status(git4idea.index.GitStageTracker.getInstance(project), file);
        return status != null && status.isUntracked();
    }
}
