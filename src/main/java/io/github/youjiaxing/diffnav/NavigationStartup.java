package io.github.youjiaxing.diffnav;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;

public final class NavigationStartup implements StartupActivity.DumbAware {
    @Override public void runActivity(Project project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) project.getService(CommitTreeFollowService.class);
        });
    }
}
