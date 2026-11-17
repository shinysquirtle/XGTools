package cn.company.xgtools;

import cn.hutool.setting.dialect.Props;
import cn.company.xgtools.core.DesktopTool;
import cn.company.xgtools.core.ToolRegistry;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.util.*;

public final class XGToolsApp extends Application {
    private final List<DesktopTool> tools = ToolRegistry.createTools();
    public static void main(String[] args) { launch(args); }
    @Override public void start(Stage stage) {
        String iconUrl = Objects.requireNonNull(getClass().getResource("/icons/xgtools.png")).toExternalForm();
        for (int size : new int[]{16, 32, 48, 256}) {
            stage.getIcons().add(new Image(iconUrl, size, size, true, true));
        }
        BorderPane root = new BorderPane();
        VBox navigation = new VBox(14);
        navigation.getStyleClass().add("navigation");
        navigation.setPadding(new Insets(28, 18, 24, 18));
        navigation.setPrefWidth(180);
        Label brand = new Label("XGTools");
        brand.getStyleClass().add("brand");
        ImageView brandIcon = new ImageView(new Image(iconUrl, 30, 30, true, true));
        brand.setGraphic(brandIcon);
        brand.setGraphicTextGap(8);
        Label tagline = new Label("日常工作工具箱");
        tagline.getStyleClass().add("nav-caption");
        navigation.getChildren().addAll(brand, tagline, new Separator());
        ToggleGroup group = new ToggleGroup();
        Map<String, Node> views = new HashMap<>();
        for (DesktopTool tool : tools) {
            ToggleButton button = new ToggleButton(tool.name());
            button.setMaxWidth(Double.MAX_VALUE);
            button.setToggleGroup(group);
            button.setTooltip(new Tooltip(tool.description()));
            button.setOnAction(e -> {
                button.setSelected(true);
                root.setCenter(views.computeIfAbsent(tool.id(), key -> tool.createView(stage)));
            });
            navigation.getChildren().add(button);
            if (root.getCenter() == null) {
                button.setSelected(true);
                root.setCenter(views.computeIfAbsent(tool.id(), key -> tool.createView(stage)));
            }
        }
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS);
        Label footer = new Label("版本 " + Props.getProp("xgtools-version.properties").getStr("app.version"));
        footer.getStyleClass().add("nav-caption");
        navigation.getChildren().addAll(spacer, footer);
        root.setLeft(navigation);
        Scene scene = new Scene(root, 1240, 760);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/app.css")).toExternalForm());
        stage.setTitle("XGTools · 工作工具箱");
        stage.setScene(scene); stage.setMinWidth(1060); stage.setMinHeight(650);
        stage.setOnCloseRequest(e -> {
            if (tools.stream().anyMatch(DesktopTool::isBusy)) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "有任务正在执行，取消任务并退出？", ButtonType.YES, ButtonType.NO);
                confirm.initOwner(stage);
                if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) e.consume();
            }
        });
        stage.show();
    }
    @Override public void stop() { tools.forEach(DesktopTool::close); }
}
