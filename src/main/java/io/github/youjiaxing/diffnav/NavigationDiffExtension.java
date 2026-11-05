package io.github.youjiaxing.diffnav;

import com.intellij.diff.DiffContext;
import com.intellij.diff.DiffExtension;
import com.intellij.diff.FrameDiffTool;
import com.intellij.diff.requests.DiffRequest;

public final class NavigationDiffExtension extends DiffExtension {
    @Override public void onViewerCreated(FrameDiffTool.DiffViewer viewer, DiffContext context, DiffRequest request) {
        StageNavigationService.viewerCreated(viewer, context, request);
        if (viewer instanceof com.intellij.diff.tools.util.side.TwosideTextDiffViewer two) LineStaging.install(two, context);
        else if (viewer instanceof com.intellij.diff.tools.util.side.OnesideTextDiffViewer one) LineStaging.install(one, context);
    }
}
