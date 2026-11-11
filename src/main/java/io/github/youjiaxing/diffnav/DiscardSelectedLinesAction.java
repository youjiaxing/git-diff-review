package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;

public final class DiscardSelectedLinesAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(LineStaging.operations(event.getData(CommonDataKeys.EDITOR)).contains(SelectionOperation.DISCARD));
    }
    @Override public void actionPerformed(AnActionEvent event) { LineStaging.invoke(event.getData(CommonDataKeys.EDITOR), SelectionOperation.DISCARD); }
}
