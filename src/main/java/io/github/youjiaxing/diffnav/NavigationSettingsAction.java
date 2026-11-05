package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;

public final class NavigationSettingsAction extends DumbAwareAction {
    @Override public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) ShowSettingsUtil.getInstance().showSettingsDialog(event.getProject(), NavigationConfigurable.class);
    }
}
