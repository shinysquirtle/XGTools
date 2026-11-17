package cn.company.xgtools.tools.image;

import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.common.UserDirectories;
import cn.company.xgtools.common.SettingHelp;
import cn.company.xgtools.common.ResultTableSupport;
import cn.company.xgtools.core.DesktopTool;
import cn.company.xgtools.tools.image.model.ImageOptions;
import cn.company.xgtools.tools.image.service.ImageProcessingService;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ImageTool implements DesktopTool {
    private final ImageProcessingService service = new ImageProcessingService();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final CheckBox compress = new CheckBox("压缩图片");
    private final CheckBox watermark = new CheckBox("添加水印");
    private final Slider quality = new Slider(1, 100, 20);
    private final Label qualityLabel = new Label("20%");
    private final TextField output = new TextField(UserDirectories.desktop().toString());
    private final TextField watermarkPath = new TextField();
    private final ImageView watermarkPreview = new ImageView();
    private final Label summary = new Label("添加图片开始使用 · 原文件始终保留");
    private final ProgressBar progress = new ProgressBar(0);
    private final Button start = new Button("开始处理"), cancel = new Button("取消任务");
    private final List<Control> editable = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "image-processing"); thread.setDaemon(true); return thread;
    });
    private final Path config = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")),
            "XGTools", "image-settings.json");
    private volatile boolean busy;
    private Stage owner;

    private static final class Row {
        final Path source;
        String parameters = "尚未执行";
        final ReadOnlyStringWrapper name, before, after = new ReadOnlyStringWrapper("—"),
                difference = new ReadOnlyStringWrapper("—"), status = new ReadOnlyStringWrapper("等待处理"),
                detail = new ReadOnlyStringWrapper("");
        Row(Path source) {
            this.source = source;
            name = new ReadOnlyStringWrapper(source.getFileName().toString());
            before = new ReadOnlyStringWrapper(FileUtil.readableFileSize(source.toFile().length()));
        }
    }
    @Override public String id() { return "image-processing"; }
    @Override public String name() { return "图片压缩"; }
    @Override public String description() { return "批量压缩图片、添加图片水印，也可组合处理。"; }
    @Override public boolean isBusy() { return busy; }

    @Override public Node createView(Stage stage) {
        owner = stage;
        compress.setSelected(true);
        watermarkPath.setPromptText("内置默认水印：guoshi.png（可选择其他图片）");
        watermarkPath.setEditable(false);
        loadSettings();
        Label heading = new Label("图片压缩与水印"); heading.getStyleClass().add("title");
        Label subtitle = new Label("压缩 / 添加水印 · 支持 JPG、JPEG、PNG、BMP · 文件仅在本机处理");
        subtitle.getStyleClass().add("muted");
        Button add = new Button("添加图片"), remove = new Button("移除选中"), clear = new Button("清空列表");
        add.setOnAction(e -> {
            var chooser = chooser("选择图片");
            List<File> selected = chooser.showOpenMultipleDialog(owner);
            if (selected != null) addFiles(selected);
        });
        remove.setOnAction(e -> rows.removeAll(new ArrayList<>(table.getSelectionModel().getSelectedItems())));
        clear.setOnAction(e -> rows.clear());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        column("文件", row -> row.name, 230);
        column("原始大小", row -> row.before, 100);
        column("结果大小", row -> row.after, 100);
        column("体积变化", row -> row.difference, 110);
        column("状态", row -> row.status, 90);
        column("详情", row -> row.detail, 280);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("拖入图片，或点击「添加图片」"));
        ResultTableSupport.install(table, this::detailText);
        compress.getStyleClass().add("image-option");
        watermark.getStyleClass().add("image-option");
        quality.getStyleClass().add("image-quality");
        quality.setShowTickMarks(false); quality.setShowTickLabels(false);
        quality.setMinWidth(180); quality.setPrefWidth(240); quality.setMaxWidth(280);
        qualityLabel.getStyleClass().add("quality-value");
        qualityLabel.disableProperty().bind(quality.disabledProperty());
        quality.valueProperty().addListener((o, old, value) -> qualityLabel.setText(Math.round(value.doubleValue()) + "%"));
        qualityLabel.setText(Math.round(quality.getValue()) + "%");
        quality.disableProperty().bind(compress.selectedProperty().not().or(start.disabledProperty()));
        Button custom = new Button("选择水印"), reset = new Button("恢复默认");
        custom.disableProperty().bind(watermark.selectedProperty().not().or(start.disabledProperty()));
        reset.disableProperty().bind(watermark.selectedProperty().not().or(start.disabledProperty()));
        custom.setOnAction(e -> {
            File file = chooser("选择水印图片").showOpenDialog(owner);
            if (file != null) {
                String previous = watermarkPath.getText();
                watermarkPath.setText(file.getAbsolutePath());
                try { updatePreview(); } catch (MyServiceException ex) {
                    watermarkPath.setText(previous); showError(ex.getMessage());
                }
            }
        });
        reset.setOnAction(e -> { watermarkPath.clear(); updatePreview(); });
        watermarkPreview.setFitWidth(180); watermarkPreview.setFitHeight(38); watermarkPreview.setPreserveRatio(true);
        try { updatePreview(); }
        catch (MyServiceException e) {
            watermarkPath.clear(); updatePreview();
            Platform.runLater(() -> showError("保存的水印无法读取，已恢复内置默认水印"));
        }
        Button browseOutput = new Button("选择目录");
        browseOutput.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File file = chooser.showDialog(owner);
            if (file != null) output.setText(file.getAbsolutePath());
        });
        String compressionHelp = "压缩图片\n按所选质量输出 JPG，尺寸不变；透明区域会变为白色。质量越低通常体积越小，但细节和小字更容易模糊。\n可单独使用，也可与水印组合；组合时先加水印再压缩。原件保留，文件不一定总能变小。";
        String watermarkHelp = "添加水印\n将默认或自选图片以宽 340px、−45°、交错平铺方式覆盖在原图上。水印会遮挡部分画面，输出体积可能增大。\n单独使用时 PNG 保留透明、其他格式输出 JPG；同时压缩时统一输出 JPG。";
        String qualityHelp = "压缩质量｜勾选“压缩图片”时生效\n默认 20%，范围 1%～100%。这表示编码质量，不是“压缩到原大小的百分之几”。\n调低通常更小，但会增加模糊、色块和边缘毛刺；调高通常更清晰、更大，100% 也不代表无损。\n不改变像素尺寸。未勾选压缩时此项不参与处理。";
        String watermarkImageHelp = "水印图片\n留空表示使用内置 guoshi.png。点击“选择水印”可替换，点击“恢复默认”切回内置水印。\n图片会保持比例缩放到宽 340px 后平铺；透明图片只覆盖自身非透明区域，不透明底色也会覆盖原图。";
        String outputHelp = "输出目录\n处理后的图片保存到这里，默认桌面，记住你手动选择的目录。\n保存位置不影响画质和体积。原文件不覆盖，重名自动加序号；再次开始会重新处理整个列表。";
        SettingHelp.attach(compressionHelp, compress);
        SettingHelp.attach(watermarkHelp, watermark);
        SettingHelp.attach(qualityHelp, quality, qualityLabel);
        SettingHelp.attach(watermarkImageHelp, watermarkPath, custom);
        SettingHelp.attach("恢复默认水印\n切换回内置 guoshi.png，不修改原文件。下次处理时生效，平铺角度和间距保持不变。", reset);
        SettingHelp.attach(outputHelp, output, browseOutput);
        GridPane options = new GridPane(); options.setHgap(14); options.setVgap(12);
        options.setPadding(new Insets(16, 18, 16, 18));
        options.getStyleClass().addAll("pdf-settings", "image-settings");
        HBox functions = new HBox(12, compress, watermark); functions.setAlignment(Pos.CENTER_LEFT);
        HBox qualityControl = new HBox(16, quality, qualityLabel); qualityControl.setAlignment(Pos.CENTER_LEFT);
        options.addRow(0, SettingHelp.label("处理功能", "可以单独压缩、单独加水印，或同时启用。两者同时开启时先加水印再压缩；至少选择一项。"), functions);
        options.addRow(1, SettingHelp.label("压缩质量", qualityHelp), qualityControl);
        options.addRow(2, SettingHelp.label("水印图片", watermarkImageHelp), watermarkPath, new HBox(8, custom, reset));
        options.addRow(3, SettingHelp.label("水印预览", "这里只展示水印素材的缩略图，不代表原图上的最终尺寸、位置或平铺效果。最终水印按固定宽度和 −45° 平铺。", watermarkPreview), watermarkPreview);
        options.addRow(4, SettingHelp.label("输出目录", outputHelp), output, browseOutput);
        GridPane.setHgrow(output, Priority.ALWAYS); GridPane.setHgrow(watermarkPath, Priority.ALWAYS);
        Label hint = new Label("压缩统一输出 JPG，透明区域填白，默认质量 20%。同时勾选时先加水印再压缩。\n水印沿用原项目：宽 340px、−45° 交错平铺；仅加水印时 PNG 保留透明。每张图片上限 4000 万像素。");
        hint.setWrapText(true); hint.getStyleClass().add("muted");
        start.getStyleClass().add("primary"); start.setOnAction(e -> begin());
        cancel.setDisable(true);
        cancel.setOnAction(e -> { cancelled.set(true); cancel.setDisable(true); summary.setText("正在取消；当前图片编解码完成后停止…"); });
        Button open = new Button("打开输出目录");
        open.setOnAction(e -> {
            try {
                Path path = outputDirectory(); Files.createDirectories(path);
                new ProcessBuilder("explorer.exe", path.toString()).start();
            } catch (Exception ex) { showError(ex.getMessage()); }
        });
        editable.addAll(List.of(add, remove, clear, compress, watermark, output, browseOutput));
        progress.setMaxWidth(Double.MAX_VALUE);
        VBox root = new VBox(12, heading, subtitle, new HBox(10, add, remove, clear,
                ResultTableSupport.copyButton(table, this::detailText), ResultTableSupport.detailButton(table, this::detailText)), table, options,
                hint, progress, summary, new HBox(10, start, cancel, open));
        root.setPadding(new Insets(24)); VBox.setVgrow(table, Priority.ALWAYS);
        root.setOnDragOver(e -> {
            if (!busy && e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        root.setOnDragDropped(e -> {
            boolean accepted = !busy && e.getDragboard().hasFiles();
            if (accepted) addFiles(e.getDragboard().getFiles());
            e.setDropCompleted(accepted); e.consume();
        });
        return root;
    }
    private String detailText(Row row) {
        return "文件：" + row.source.toAbsolutePath() + "\n状态：" + row.status.get()
                + "\n处理设置：" + row.parameters
                + "\n原始大小：" + row.before.get() + "\n结果大小：" + row.after.get()
                + "\n体积变化：" + row.difference.get()
                + "\n\n完整详情：\n" + row.detail.get();
    }
    private void column(String title, java.util.function.Function<Row, ReadOnlyStringWrapper> value, int width) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> value.apply(cell.getValue())); column.setPrefWidth(width);
        ResultTableSupport.styleColumn(column, title.equals("状态"), List.of("原始大小", "结果大小", "减少", "体积变化").contains(title));
        table.getColumns().add(column);
    }
    private FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser(); chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片文件", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.JPG", "*.JPEG", "*.PNG", "*.BMP"));
        return chooser;
    }
    private void addFiles(List<File> files) {
        int skipped = 0;
        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!file.isFile() || !ImageProcessingService.supports(path)) { skipped++; continue; }
            if (rows.stream().noneMatch(row -> row.source.equals(path))) rows.add(new Row(path));
        }
        summary.setText("已添加 " + rows.size() + " 张图片" + (skipped == 0 ? "" : "，忽略 " + skipped + " 个不支持的项目"));
    }
    private void updatePreview() {
        try {
            String url = StrUtil.isBlank(watermarkPath.getText())
                    ? Objects.requireNonNull(getClass().getResource(ImageProcessingService.DEFAULT_WATERMARK)).toExternalForm()
                    : Path.of(watermarkPath.getText()).toUri().toString();
            Image image = new Image(url, 180, 38, true, true);
            if (image.isError()) throw new MyServiceException("水印图片无法读取");
            watermarkPreview.setImage(image);
        } catch (MyServiceException e) { throw e; }
        catch (Exception e) { throw new MyServiceException("水印图片无法读取", e); }
    }
    private Path outputDirectory() {
        if (StrUtil.isBlank(output.getText())) throw new MyServiceException("请选择输出目录");
        return Path.of(output.getText().trim()).toAbsolutePath();
    }
    private void begin() {
        if (busy) return;
        try {
            if (rows.isEmpty()) throw new MyServiceException("请先添加图片");
            ImageOptions options = new ImageOptions(compress.isSelected(), watermark.isSelected(),
                    Math.round(quality.getValue()) / 100f,
                    StrUtil.isBlank(watermarkPath.getText()) ? null : Path.of(watermarkPath.getText()));
            Path directory = outputDirectory(); Files.createDirectories(directory);
            saveSettings();
            List<Row> queue = List.copyOf(rows);
            queue.forEach(row -> {
                row.status.set("等待处理"); row.detail.set(""); row.after.set("—"); row.difference.set("—");
                row.parameters = "压缩=" + options.compress() + "；水印=" + options.watermark() + "；质量="
                        + Math.round(options.quality() * 100) + "%；水印图片="
                        + (options.customWatermark() == null ? "内置默认" : options.customWatermark());
            });
            cancelled.set(false); setBusy(true); progress.setProgress(0);
            executor.submit(() -> runQueue(queue, directory, options));
        } catch (Exception e) { showError(e.getMessage()); }
    }
    private void runQueue(List<Row> queue, Path directory, ImageOptions options) {
        int processed = 0;
        try {
            for (Row row : queue) {
                if (cancelled.get()) break;
                Platform.runLater(() -> { row.status.set("处理中"); summary.setText("正在处理：" + row.source.getFileName()); });
                try {
                    var result = service.process(row.source, directory, options, cancelled);
                    Platform.runLater(() -> {
                        row.detail.set(result.message());
                        if (!result.success()) { row.status.set("已取消"); return; }
                        var data = result.data();
                        row.status.set("完成");
                        row.before.set(FileUtil.readableFileSize(data.originalBytes()));
                        row.after.set(FileUtil.readableFileSize(data.outputBytes()));
                        double difference = 100.0 * (data.outputBytes() - data.originalBytes()) / data.originalBytes();
                        row.difference.set(String.format(Locale.ROOT, "%s %.1f%%", difference > 0 ? "增大" : "减少", Math.abs(difference)));
                        row.detail.set(result.message() + " · " + data.file());
                    });
                } catch (MyServiceException e) {
                    Platform.runLater(() -> { row.status.set("失败"); row.detail.set(e.getMessage()); });
                }
                int count = ++processed;
                Platform.runLater(() -> progress.setProgress((double) count / queue.size()));
            }
        } finally {
            int count = processed;
            Platform.runLater(() -> {
                if (cancelled.get()) queue.stream().filter(row -> row.status.get().equals("等待处理")).forEach(row -> row.status.set("已取消"));
                setBusy(false);
                summary.setText((cancelled.get() ? "已取消" : "批次完成") + " · 已处理 " + count + "/" + queue.size()
                        + " 张；再次开始将按当前设置重新处理列表");
            });
        }
    }
    private void setBusy(boolean value) {
        busy = value; start.setDisable(value); cancel.setDisable(!value);
        editable.forEach(control -> control.setDisable(value));
    }
    private void loadSettings() {
        if (!Files.exists(config)) return;
        try {
            var json = JSONUtil.parseObj(FileUtil.readUtf8String(config.toFile()));
            output.setText(json.getStr("output", output.getText()));
            quality.setValue(Math.max(1, Math.min(100, json.getInt("quality", 20))));
            compress.setSelected(json.getBool("compress", true)); watermark.setSelected(json.getBool("watermark", false));
            String custom = json.getStr("watermarkPath", "");
            if (!custom.isBlank() && Files.isRegularFile(Path.of(custom))) watermarkPath.setText(custom);
        } catch (Exception e) { Platform.runLater(() -> showError("图片工具配置读取失败，已使用默认设置")); }
    }
    private void saveSettings() {
        try {
            var json = JSONUtil.createObj().set("output", output.getText()).set("quality", Math.round(quality.getValue()))
                    .set("compress", compress.isSelected()).set("watermark", watermark.isSelected()).set("watermarkPath", watermarkPath.getText());
            FileUtil.writeUtf8String(JSONUtil.toJsonPrettyStr(json), config.toFile());
        } catch (Exception e) { throw new MyServiceException("保存图片工具配置失败", e); }
    }
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR); alert.initOwner(owner);
        alert.setTitle("操作提示"); alert.setHeaderText(null); alert.setContentText(message); alert.showAndWait();
    }
    @Override public void close() {
        cancelled.set(true); executor.shutdown();
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}


