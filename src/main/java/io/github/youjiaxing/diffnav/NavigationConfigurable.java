package io.github.youjiaxing.diffnav;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import java.awt.GridLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class NavigationConfigurable implements SearchableConfigurable {
    private final Project project;
    private JCheckBox enabled;
    private JCheckBox autoStart;
    private JComboBox<NavigationSettings.TargetScope> scope;
    public NavigationConfigurable(Project project) { this.project = project; }
    @Override public String getId() { return "io.github.youjiaxing.diffnav.settings"; }
    @Override public String getDisplayName() { return "Git Diff Review"; }
    @Override public JComponent createComponent() {
        enabled = new JCheckBox("启用 Diff 审阅导航");
        autoStart = new JCheckBox("从暂存区 Diff 跳转时自动开始审阅");
        scope = new JComboBox<>(NavigationSettings.TargetScope.values());
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 8));
        fields.add(enabled);
        fields.add(autoStart);
        fields.add(new JLabel("导航目标的比较范围："));
        fields.add(scope);
        fields.add(new JLabel("只审已暂存时：目标不在暂存版本或无法确认坐标，将打开源码。"));
        fields.add(new JLabel("审阅可经过未修改源码和用法结果；关闭所属 Diff 预览或暂停后结束。"));
        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.add(fields, java.awt.BorderLayout.NORTH);
        reset();
        return panel;
    }
    @Override public boolean isModified() {
        var options = NavigationSettings.get(project).getState();
        return enabled != null && (enabled.isSelected() != options.enabled
                || autoStart.isSelected() != options.autoStart || scope.getSelectedItem() != options.targetScope);
    }
    @Override public void apply() {
        var options = NavigationSettings.get(project).getState();
        options.enabled = enabled.isSelected();
        options.autoStart = autoStart.isSelected();
        options.targetScope = (NavigationSettings.TargetScope) scope.getSelectedItem();
        project.getService(StageNavigationService.class).settingsChanged();
    }
    @Override public void reset() {
        if (enabled == null) return;
        var options = NavigationSettings.get(project).getState();
        enabled.setSelected(options.enabled);
        autoStart.setSelected(options.autoStart);
        scope.setSelectedItem(options.targetScope);
    }
    @Override public void disposeUIResources() { enabled = null; autoStart = null; scope = null; }
}
