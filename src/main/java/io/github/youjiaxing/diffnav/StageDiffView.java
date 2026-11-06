package io.github.youjiaxing.diffnav;

import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.tools.util.base.DiffViewerBase;
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.Side;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDiffRequestProcessor;
import git4idea.index.ui.NodeKind;
import git4idea.index.vfs.GitIndexVirtualFile;

/** The supported staging text views, with version identity checked before using their editors. */
final class StageDiffView {
    private final DiffViewerBase viewer;
    private final Editor left;
    private final Editor right;

    private StageDiffView(DiffViewerBase viewer, Editor left, Editor right) {
        this.viewer = viewer; this.left = left; this.right = right;
    }

    static StageDiffView of(FrameDiffTool.DiffViewer viewer) {
        if (viewer instanceof TwosideTextDiffViewer two) return new StageDiffView(two, two.getEditor1(), two.getEditor2());
        // A missing old version is shown on the right. Deleted-only (left) views stay native.
        if (viewer instanceof OnesideTextDiffViewer one && one.getSide() == Side.RIGHT) return new StageDiffView(one, null, one.getEditor());
        return null;
    }

    static StageDiffView current(GitStageDiffRequestProcessor stage) {
        if (stage.isDisposed() || stage.getCurrentChange() == null
                || !(stage.getCurrentChange().getUserObject() instanceof GitFileStatusNode node)) return null;
        StageDiffView view = of(stage.getActiveViewer());
        if (view == null || view.viewer.hasPendingRediff()) return null;
        var file = FileDocumentManager.getInstance().getFile(view.right.getDocument());
        boolean index = file instanceof GitIndexVirtualFile;
        var local = index ? ((GitIndexVirtualFile) file).getFilePath().getVirtualFile() : file;
        return local != null && local.equals(node.getFilePath().getVirtualFile()) && index == (node.getKind() == NodeKind.STAGED) ? view : null;
    }

    Editor editor(Side side) { return side == Side.LEFT ? left : right; }
    Editor currentEditor() { return viewer instanceof TwosideTextDiffViewer two ? two.getCurrentEditor() : right; }
    Side side(Editor editor) { return editor == left ? Side.LEFT : Side.RIGHT; }
    void markStagingEditors() {
        if (left != null) left.putUserData(StageNavigationService.STAGE_EDITOR, true);
        right.putUserData(StageNavigationService.STAGE_EDITOR, true);
    }
    void scrollToLine(Side side, int line) {
        if (viewer instanceof TwosideTextDiffViewer two) two.scrollToLine(side, line);
        else ((OnesideTextDiffViewer) viewer).scrollToLine(line);
    }
}
