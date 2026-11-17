package cn.company.xgtools.common;

import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ResultTableSupport {
    private ResultTableSupport() {}

    public static <T> void install(TableView<T> table, Function<T, String> details) {
        table.getStyleClass().add("result-table");
        table.setFixedCellSize(46);
        table.setRowFactory(view -> {
            TableRow<T> row = new TableRow<>();
            MenuItem show = new MenuItem("查看完整详情");
            MenuItem copy = new MenuItem("复制详情");
            show.setOnAction(event -> showSelected(table, details));
            copy.setOnAction(event -> copySelected(table, details));
            ContextMenu menu = new ContextMenu(show, copy);
            row.emptyProperty().addListener((o, old, empty) -> row.setContextMenu(empty ? null : menu));
            row.setOnContextMenuRequested(event -> {
                if (!row.isEmpty() && !row.isSelected()) table.getSelectionModel().clearAndSelect(row.getIndex());
            });
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2)
                    showSelected(table, details);
            });
            row.setTooltip(SettingHelp.tooltip("双击查看完整详情；选中后按 Ctrl+C 或右键复制。支持多选，复制内容不截断。"));
            return row;
        });
        table.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(event)) {
                copySelected(table, details); event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                showSelected(table, details); event.consume();
            }
        });
    }

    public static <T> Button copyButton(TableView<T> table, Function<T, String> details) {
        Button button = new Button("复制详情");
        button.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        button.setOnAction(event -> copySelected(table, details));
        SettingHelp.attach("复制选中文件的完整路径、状态和详情，包含全部报错文字。支持按 Ctrl/Ctrl+A 多选后复制；不会复制表格中截断的摘要。", button);
        return button;
    }

    public static <T> Button detailButton(TableView<T> table, Function<T, String> details) {
        Button button = new Button("查看详情");
        button.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        button.setOnAction(event -> showSelected(table, details));
        SettingHelp.attach("在独立窗口查看完整详情，可用鼠标选择文字并复制，也可点击“复制全部”。双击表格行也可打开。", button);
        return button;
    }

    private static <T> String selected(TableView<T> table, Function<T, String> details) {
        return table.getSelectionModel().getSelectedItems().stream()
                .map(details).collect(Collectors.joining("\n\n────────────────────────\n\n"));
    }
    private static void copy(String text) {
        if (text.isBlank()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
    private static <T> void copySelected(TableView<T> table, Function<T, String> details) {
        copy(selected(table, details));
    }
    private static <T> void showSelected(TableView<T> table, Function<T, String> details) {
        String text = selected(table, details);
        if (text.isBlank()) return;
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(table.getScene().getWindow());
        dialog.setTitle("处理结果详情");
        dialog.setHeaderText("完整详情 · 可选择文字复制");
        dialog.setResizable(true);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefSize(800, 400);
        area.getStyleClass().add("result-detail-text");
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getStyleClass().add("result-detail-dialog");
        dialog.getDialogPane().getStylesheets().addAll(table.getScene().getStylesheets());
        ButtonType copyAll = new ButtonType("复制全部", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().addAll(copyAll, new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().lookupButton(copyAll).addEventFilter(ActionEvent.ACTION, event -> {
            copy(area.getText()); event.consume();
        });
        dialog.show();
    }

    public static <T> void styleColumn(TableColumn<T, String> column, boolean status, boolean numeric) {
        if (numeric) column.getStyleClass().add("number-column");
        column.setCellFactory(view -> new TableCell<>() {
            private final Label badge = new Label();
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(null); setGraphic(null);
                setAlignment(numeric ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                setWrapText(false); setTextOverrun(OverrunStyle.ELLIPSIS);
                if (empty || value == null) return;
                if (status) {
                    badge.setText(value);
                    badge.getStyleClass().setAll("status-badge", statusClass(value));
                    setGraphic(badge);
                } else {
                    setText(value.replaceAll("[\\r\\n\\t]+", " ").strip());
                }
            }
        });
    }
    private static String statusClass(String value) {
        if (value.contains("失败")) return "status-error";
        if (value.contains("未达") || value.contains("取消")) return "status-warning";
        if (value.contains("完成") || value.equals("已诊断")) return "status-success";
        if (value.contains("正在") || value.contains("中")) return "status-running";
        return "status-neutral";
    }
}
