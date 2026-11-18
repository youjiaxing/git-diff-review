package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;

public final class SourceStagedDiffAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(SourceStagingService.needsStagedDiff(event.getData(CommonDataKeys.EDITOR)));
    }
    @Override public void actionPerformed(AnActionEvent event) { SourceStagingService.stagedDiff(event.getData(CommonDataKeys.EDITOR)); }
}
