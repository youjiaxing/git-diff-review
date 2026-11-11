package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.undo.*;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.util.Pair;

/** INDEX is read-only; an undo of our Discard belongs to the real LOCAL document. */
public final class DiscardUndoProvider implements UndoManagerProvider {
    @Override public UndoManager getUndoManager(DataContext context) {
        var editor = CommonDataKeys.EDITOR.getData(context);
        if (editor == null) return null;
        TextEditor target = LineStaging.discardUndoTarget(editor);
        if (target == null || target.getEditor().getProject() == null) return null;
        var delegate = UndoManager.getInstance(target.getEditor().getProject());
        var manager = new LocalUndo(delegate, target);
        return manager.isUndoAvailable(null) || manager.isRedoAvailable(null) ? manager : null;
    }
    private static final class LocalUndo extends UndoManager {
        private final UndoManager delegate;
        private final TextEditor target;
        LocalUndo(UndoManager delegate, TextEditor target) { this.delegate = delegate; this.target = target; }
        private boolean owns(boolean redo) {
            if (target.getEditor().isDisposed() || !(redo ? delegate.isRedoAvailable(target) : delegate.isUndoAvailable(target))) return false;
            var name = redo ? delegate.getRedoActionNameAndDescription(target) : delegate.getUndoActionNameAndDescription(target);
            return name.first != null && name.first.contains(SelectionOperation.DISCARD.text);
        }
        @Override public boolean isUndoAvailable(FileEditor ignored) { return owns(false); }
        @Override public boolean isRedoAvailable(FileEditor ignored) { return owns(true); }
        @Override public void undo(FileEditor ignored) { if (owns(false)) delegate.undo(target); }
        @Override public void redo(FileEditor ignored) { if (owns(true)) delegate.redo(target); }
        @Override public Pair<String, String> getUndoActionNameAndDescription(FileEditor ignored) { return delegate.getUndoActionNameAndDescription(target); }
        @Override public Pair<String, String> getRedoActionNameAndDescription(FileEditor ignored) { return delegate.getRedoActionNameAndDescription(target); }
        @Override public long getNextUndoNanoTime(FileEditor ignored) { return delegate.getNextUndoNanoTime(target); }
        @Override public long getNextRedoNanoTime(FileEditor ignored) { return delegate.getNextRedoNanoTime(target); }
        @Override public boolean isNextUndoAskConfirmation(FileEditor ignored) { return delegate.isNextUndoAskConfirmation(target); }
        @Override public boolean isNextRedoAskConfirmation(FileEditor ignored) { return delegate.isNextRedoAskConfirmation(target); }
        @Override public boolean isUndoInProgress() { return delegate.isUndoInProgress(); }
        @Override public boolean isRedoInProgress() { return delegate.isRedoInProgress(); }
        @Override public void undoableActionPerformed(UndoableAction action) { delegate.undoableActionPerformed(action); }
        @Override public void nonundoableActionPerformed(DocumentReference reference, boolean global) { delegate.nonundoableActionPerformed(reference, global); }
    }
}
