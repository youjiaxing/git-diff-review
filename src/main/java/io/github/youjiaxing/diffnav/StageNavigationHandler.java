package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.platform.backend.navigation.NavigationRequest;
import com.intellij.platform.backend.navigation.impl.SourceNavigationRequest;
import com.intellij.platform.ide.navigation.NavigationHandler;
import com.intellij.platform.ide.navigation.NavigationOptions;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consume final native navigation requests; never replace Go's symbol resolver. */
public final class StageNavigationHandler implements NavigationHandler {
    @Override
    public boolean navigate(NavigationRequest request, NavigationOptions options, DataContext context) {
        Project project = CommonDataKeys.PROJECT.getData(context);
        if (project == null || project.isDisposed()) return false;
        if (!(request instanceof SourceNavigationRequest source)) return false;
        var app = ApplicationManager.getApplication();
        if (!app.isDispatchThread() && app.isReadAccessAllowed()) return false;
        AtomicBoolean consumed = new AtomicBoolean();
        Runnable navigate = () -> {
            if (!project.isDisposed()) {
                var service = project.getService(StageNavigationService.class);
                consumed.set(service.navigate(source, options, context));
            }
        };
        if (app.isDispatchThread()) navigate.run();
        else app.invokeAndWait(navigate, ModalityState.defaultModalityState());
        return consumed.get();
    }
}
