package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;

public final class StageSelectedLinesAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation().setEnabledAndVisible(LineStaging.available(editor) && editor.getSelectionModel().hasSelection());
    }
    @Override public void actionPerformed(AnActionEvent event) { LineStaging.invoke(event.getData(CommonDataKeys.EDITOR)); }
}
