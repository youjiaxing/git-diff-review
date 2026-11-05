package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;

public final class NavigationDiagnosticsAction extends DumbAwareAction {
    @Override public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) Messages.showInfoMessage(event.getProject(),
                event.getProject().getService(StageNavigationService.class).getLastReason(), "Diff Review 最近一次导航");
    }
}
