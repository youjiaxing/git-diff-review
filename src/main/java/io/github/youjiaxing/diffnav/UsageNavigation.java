package io.github.youjiaxing.diffnav;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.Project;
import com.intellij.platform.backend.navigation.impl.SourceNavigationRequest;
import com.intellij.platform.ide.navigation.NavigationOptions;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageView;

/** Explicit single-usage activation, before native openTextEditor bypasses NavigationHandler. */
final class UsageNavigation {
    private static UsageInfo2UsageAdapter target(Project project, DataContext context) {
        var service = project.getService(StageNavigationService.class);
        if (!service.isReviewing(context)) return null;
        var usages = UsageView.USAGES_KEY.getData(context);
        var navigatables = CommonDataKeys.NAVIGATABLE_ARRAY.getData(context);
        if (navigatables == null || navigatables.length != 1) return null;
        UsageInfo2UsageAdapter usage = navigatables[0] instanceof UsageInfo2UsageAdapter direct ? direct
                : usages != null && usages.length == 1 && usages[0] instanceof UsageInfo2UsageAdapter selected ? selected : null;
        if (usage == null) return null;
        var file = usage.getFile();
        var document = usage.getDocument();
        int offset = usage.getNavigationOffset();
        return file == null || !file.isValid() || !file.isInLocalFileSystem() || document == null
                || offset < 0 || offset > document.getTextLength() ? null : usage;
    }

    static boolean canNavigate(Project project, DataContext context) { return target(project, context) != null; }

    static boolean navigate(Project project, DataContext context) {
        var usage = target(project, context);
        if (usage == null) return false;
        var service = project.getService(StageNavigationService.class);
        var file = usage.getFile();
        var document = usage.getDocument();
        int offset = usage.getNavigationOffset();
        var marker = document.createRangeMarker(offset, offset);
        try { return service.navigate(new SourceNavigationRequest(file, marker, null), NavigationOptions.requestFocus(), context); }
        finally { marker.dispose(); }
    }
}
