package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;

public final class StageSelectedLinesAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        var operations = LineStaging.operations(editor).stream().filter(op -> op != SelectionOperation.DISCARD).toList();
        event.getPresentation().setEnabledAndVisible(!operations.isEmpty());
        event.getPresentation().setText(operations.size() == 1 ? operations.getFirst().text : "Stage / Unstage 选中行…");
    }
    @Override public void actionPerformed(AnActionEvent event) { LineStaging.invoke(event.getData(CommonDataKeys.EDITOR)); }
}
