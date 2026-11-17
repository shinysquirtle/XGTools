package cn.company.xgtools.common;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.util.Duration;

public final class SettingHelp {
    private SettingHelp() {}

    public static Tooltip tooltip(String explanation) {
        Tooltip tooltip = new Tooltip(explanation);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(430);
        tooltip.setShowDelay(Duration.millis(350));
        tooltip.setHideDelay(Duration.millis(120));
        tooltip.setShowDuration(Duration.seconds(45));
        tooltip.getStyleClass().add("setting-tooltip");
        return tooltip;
    }

    public static void attach(String explanation, Node... nodes) {
        for (Node node : nodes) {
            if (node instanceof Control control) control.setTooltip(tooltip(explanation));
            else Tooltip.install(node, tooltip(explanation));
            node.setAccessibleHelp(explanation);
        }
    }

    /** 标签始终可悬停，即使对应控件因当前模式而被禁用。 */
    public static Label label(String title, String explanation, Node... controls) {
        Label label = new Label(title);
        Label indicator = new Label("?");
        indicator.getStyleClass().add("help-indicator");
        label.setGraphic(indicator);
        label.setContentDisplay(ContentDisplay.RIGHT);
        label.setGraphicTextGap(6);
        label.setAlignment(Pos.CENTER_LEFT);
        label.setMinHeight(38);
        label.getStyleClass().add("setting-label");
        attach(explanation, label);
        attach(explanation, controls);
        return label;
    }
}
