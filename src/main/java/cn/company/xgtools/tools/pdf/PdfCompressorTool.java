package cn.company.xgtools.tools.pdf;

import cn.company.xgtools.core.DesktopTool;
import javafx.scene.Node;

import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.common.UserDirectories;
import cn.company.xgtools.common.SettingHelp;
import cn.company.xgtools.common.ResultTableSupport;
import cn.company.xgtools.tools.pdf.model.Quality;
import cn.company.xgtools.tools.pdf.service.CompressionService;
import cn.company.xgtools.tools.pdf.service.BundledEngine;
import cn.company.xgtools.tools.pdf.service.SettingsService;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.company.xgtools.tools.pdf.model.CompressionMode;
import cn.company.xgtools.tools.pdf.model.PdfOptions;
import cn.company.xgtools.tools.pdf.service.PdfInspector;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PdfCompressorTool implements DesktopTool {
    private final SettingsService settings = new SettingsService();
    private final CompressionService service = new CompressionService();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "pdf-compression"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final TextField output = new TextField();
    private final ComboBox<Quality> quality = new ComboBox<>(FXCollections.observableArrayList(Quality.values()));
    private final ComboBox<CompressionMode> mode = new ComboBox<>(FXCollections.observableArrayList(CompressionMode.values()));
    private final Spinner<Integer> imageDpi = new Spinner<>(60, 300, 110, 10);
    private final Spinner<Integer> rasterDpi = new Spinner<>(80, 200, 150, 10);
    private final Spinner<Integer> jpegQuality = new Spinner<>(10, 95, 72, 1);
    private final Spinner<Integer> targetMb = new Spinner<>(1, 2048, 20, 1);
    private final Label modeHint = new Label();
    private final Label summary = new Label("添加 PDF 文件开始使用 · 文件仅在本机处理");
    private final ProgressBar progress = new ProgressBar(0);
    private final Button start = new Button("开始压缩");
    private final Button cancel = new Button("取消任务");
    private final TableView<Row> table = new TableView<>(rows);
    private final List<Control> editingControls = new ArrayList<>();
    private volatile boolean running;
    private Stage window;

    private static class Row {
        final Path source;
        String parameters = "尚未执行";
        Path result;
        final ReadOnlyStringWrapper name, before, after = new ReadOnlyStringWrapper("—"),
                saving = new ReadOnlyStringWrapper("—"), status = new ReadOnlyStringWrapper("等待处理"),
                detail = new ReadOnlyStringWrapper("");
        boolean done;
        Row(Path source) {
            this.source = source;
            name = new ReadOnlyStringWrapper(source.getFileName().toString());
            before = new ReadOnlyStringWrapper(FileUtil.readableFileSize(source.toFile().length()));
        }
    }
    @Override public String id() { return "pdf-compress"; }
    @Override public String name() { return "PDF 压缩"; }
    @Override public String description() { return "批量压缩 PDF，保留原件并比较文件大小。"; }
    @Override public boolean isBusy() { return running; }

    @Override public Node createView(Stage stage) {
        window = stage;
        JSONObject config;
        String configurationError = null;
        try { config = settings.load(); }
        catch (MyServiceException e) { config = new JSONObject(); configurationError = e.getMessage(); }
        output.setText(config.getStr("output", UserDirectories.desktop().toString()));
        try { quality.setValue(Quality.valueOf(config.getStr("quality", "BALANCED"))); }
        catch (IllegalArgumentException e) { quality.setValue(Quality.BALANCED); }
        try { mode.setValue(CompressionMode.valueOf(config.getStr("mode", "STANDARD"))); }
        catch (IllegalArgumentException e) { mode.setValue(CompressionMode.STANDARD); }
        imageDpi.getValueFactory().setValue(Math.max(60, Math.min(300, config.getInt("imageDpi", 110))));
        rasterDpi.getValueFactory().setValue(Math.max(80, Math.min(200, config.getInt("rasterDpi", 150))));
        jpegQuality.getValueFactory().setValue(Math.max(10, Math.min(95, config.getInt("jpegQuality", 72))));
        targetMb.getValueFactory().setValue(Math.max(1, Math.min(2048, config.getInt("targetMb", 20))));
        output.setPromptText("选择结果保存目录");
        Label title = new Label("PDF 压缩工具");
        title.getStyleClass().add("title");
        Label subtitle = new Label("批量压缩 · 原件保留 · 本地处理");
        subtitle.getStyleClass().add("muted");
        Button add = new Button("添加 PDF");
        add.setOnAction(e -> chooseFiles());
        Button remove = new Button("移除选中");
        remove.setOnAction(e -> rows.removeAll(new ArrayList<>(table.getSelectionModel().getSelectedItems())));
        Button clear = new Button("清空列表");
        clear.setOnAction(e -> rows.clear());
        Button diagnose = new Button("诊断选中 / 全部");
        diagnose.setOnAction(e -> diagnose());
        SettingHelp.attach("诊断文件内容流、图片体积、页数和裁切情况，帮助选择压缩方式。只分析原文件，不修改内容；估算不能保证最终大小。", diagnose);
        Button view = new Button("打开选中结果");
        view.setOnAction(e -> {
            Row row = table.getSelectionModel().getSelectedItem();
            if (row == null) { showError("请先选择一个文件"); return; }
            try { new ProcessBuilder("explorer.exe", (row.result == null ? row.source : row.result).toAbsolutePath().toString()).start(); }
            catch (Exception ex) { showError("无法打开文件：" + ex.getMessage()); }
        });
        HBox toolbar = new HBox(10, add, remove, clear, diagnose, view,
                ResultTableSupport.copyButton(table, this::detailText), ResultTableSupport.detailButton(table, this::detailText));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        column("文件", row -> row.name, 270);
        column("原始大小", row -> row.before, 100);
        column("结果大小", row -> row.after, 100);
        column("减少", row -> row.saving, 85);
        column("状态", row -> row.status, 125);
        column("详情", row -> row.detail, 290);
        table.setPlaceholder(new Label("将 PDF 拖到这里，或点击「添加 PDF」"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        ResultTableSupport.install(table, this::detailText);
        Button outputBrowse = new Button("选择目录");
        outputBrowse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择输出目录");
            File file = chooser.showDialog(window);
            if (file != null) output.setText(file.getAbsolutePath());
        });
        GridPane configPane = new GridPane();
        configPane.setHgap(16); configPane.setVgap(12);
        ColumnConstraints leftLabel = new ColumnConstraints(108);
        ColumnConstraints leftControl = new ColumnConstraints();
        leftControl.setHgrow(Priority.ALWAYS); leftControl.setFillWidth(true);
        ColumnConstraints rightLabel = new ColumnConstraints(108);
        ColumnConstraints rightControl = new ColumnConstraints();
        rightControl.setHgrow(Priority.ALWAYS); rightControl.setFillWidth(true);
        configPane.getColumnConstraints().addAll(leftLabel, leftControl, rightLabel, rightControl);
        configPane.add(settingLabel("输出目录", output, outputBrowse), 0, 0);
        configPane.add(output, 1, 0, 2, 1);
        configPane.add(outputBrowse, 3, 0);
        configPane.addRow(1, settingLabel("压缩方式", mode), mode, settingLabel("质量预设", quality), quality);
        configPane.addRow(2, settingLabel("降采样 DPI", imageDpi), imageDpi, settingLabel("栅格 DPI", rasterDpi), rasterDpi);
        configPane.addRow(3, settingLabel("JPEG 质量", jpegQuality), jpegQuality, settingLabel("目标大小 MB", targetMb), targetMb);
        for (Control control : List.of(output, outputBrowse, mode, quality, imageDpi, rasterDpi, jpegQuality, targetMb)) {
            control.setMinHeight(38); control.setPrefHeight(38); control.setMaxHeight(38);
            control.setMaxWidth(Double.MAX_VALUE);
        }
        for (Spinner<Integer> spinner : List.of(imageDpi, rasterDpi, jpegQuality, targetMb)) {
            spinner.setPrefWidth(220);
            spinner.getEditor().setAlignment(Pos.CENTER_LEFT);
        }
        mode.setPrefWidth(300); quality.setPrefWidth(220);
        mode.getStyleClass().add("pdf-choice"); quality.getStyleClass().add("pdf-choice");
        mode.setCellFactory(list -> modeCell(true));
        quality.setCellFactory(list -> qualityCell());
        mode.setButtonCell(modeCell(false));
        outputBrowse.getStyleClass().add("directory-button");
        quality.disableProperty().bind(start.disabledProperty().or(mode.valueProperty().isNotEqualTo(CompressionMode.STANDARD)));
        imageDpi.disableProperty().bind(start.disabledProperty().or(
                mode.valueProperty().isNotEqualTo(CompressionMode.DOWNSAMPLE).and(mode.valueProperty().isNotEqualTo(CompressionMode.TARGET))));
        rasterDpi.disableProperty().bind(start.disabledProperty().or(mode.valueProperty().isNotEqualTo(CompressionMode.RASTER)));
        jpegQuality.disableProperty().bind(start.disabledProperty().or(
                mode.valueProperty().isNotEqualTo(CompressionMode.RASTER).and(mode.valueProperty().isNotEqualTo(CompressionMode.TARGET))));
        targetMb.disableProperty().bind(start.disabledProperty().or(mode.valueProperty().isNotEqualTo(CompressionMode.TARGET)));
        modeHint.setWrapText(true); modeHint.setMaxWidth(Double.MAX_VALUE); modeHint.getStyleClass().add("mode-description");
        mode.valueProperty().addListener((o, old, selected) -> updateModeHint());
        updateModeHint();
        GridPane.setHgrow(output, Priority.ALWAYS);
        Label hint = new Label("整页栅格化会失去文字搜索/复制及交互功能；签名会失效。保留原文件，完成后请抽检小字、图例和裁切页。");
        hint.setWrapText(true); hint.getStyleClass().add("muted");
        start.getStyleClass().add("primary");
        start.setOnAction(e -> begin());
        cancel.setDisable(true);
        cancel.setOnAction(e -> { cancelled.set(true); cancel.setDisable(true); summary.setText("正在取消，请稍候…"); });
        Button open = new Button("打开输出目录");
        open.setOnAction(e -> openOutput());
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox actions = new HBox(10, start, cancel, open);
        actions.setAlignment(Pos.CENTER_LEFT);
        Label settingsTitle = new Label("压缩设置");
        settingsTitle.getStyleClass().add("settings-title");
        Label settingsCaption = new Label("悬停设置项或 ? 查看用途与影响");
        settingsCaption.getStyleClass().add("settings-caption");
        Region headerSpacer = new Region(); HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox settingsHeader = new HBox(12, settingsTitle, headerSpacer, settingsCaption);
        settingsHeader.setAlignment(Pos.CENTER_LEFT);
        VBox optionsContent = new VBox(12, settingsHeader, configPane, modeHint, hint);
        optionsContent.getStyleClass().add("pdf-settings");
        optionsContent.setPadding(new Insets(18, 20, 18, 20));
        ScrollPane optionsScroll = new ScrollPane(optionsContent);
        optionsScroll.setFitToWidth(true); optionsScroll.setPrefViewportHeight(330); optionsScroll.setMaxHeight(370);
        optionsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        optionsScroll.getStyleClass().add("settings-scroll");
        table.setMinHeight(130);
        summary.setWrapText(true);
        VBox root = new VBox(10, title, subtitle, toolbar, table, optionsScroll, progress, summary, actions);
        root.setPadding(new Insets(24)); VBox.setVgrow(table, Priority.ALWAYS);
        editingControls.addAll(List.of(add, remove, clear, diagnose, mode, output, outputBrowse));
        root.setOnDragOver(e -> {
            if (!running && e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        root.setOnDragDropped(e -> {
            boolean accepted = !running && e.getDragboard().hasFiles();
            if (accepted) addFiles(e.getDragboard().getFiles());
            e.setDropCompleted(accepted); e.consume();
        });
        if (configurationError != null) {
            String message = configurationError;
            Platform.runLater(() -> showError(message));
        }
        return root;
    }
    private static Label settingLabel(String text, Node... controls) {
        String explanation = switch (text) {
            case "输出目录" -> "输出目录\n处理后的文件保存到这里，默认使用桌面，并记住你手动选择的目录。\n只影响保存位置，不影响清晰度和压缩率。原文件不覆盖，重名时自动加序号；未获得体积收益时保留原文件。";
            case "压缩方式" -> "压缩方式\n常规压缩：使用质量预设，适合先尝试。\n增强降采样：降低图片分辨率，保留原本可搜索的文字。\n整页栅格化：把所有页面变成图片，通常更适合矢量内容很多的 PDF，但文字无法搜索或复制。\n自动目标：先判断文件，再尝试降采样或逐档栅格化，不保证所有文件都能达标。";
            case "质量预设" -> "质量预设｜仅常规压缩生效\n清晰优先：侧重保留图像细节，结果通常较大。\n均衡推荐：在清晰度与体积之间折中。\n体积优先：更积极降低图片清晰度，结果通常较小。\n这不是无损压缩开关；矢量内容很多时，换预设也可能几乎不变小。其他模式下此项置灰。";
            case "降采样 DPI" -> "降采样 DPI｜增强降采样、自动目标模式生效\n控制 PDF 中彩色和灰度图片的目标分辨率，不会把整页文字转成图片。默认 110，范围 60～300。\n调低通常更小，但图片细节和图片里的文字更模糊；调高通常更清晰，但体积更大。低分辨率原图或矢量内容可能几乎不受影响。";
            case "栅格 DPI" -> "栅格 DPI｜仅手动整页栅格化生效\n控制最终页面图片的像素密度，默认 150，范围 80～200；页面纸张尺寸保持不变。\n调高能保留更多小字和图例细节，但体积、处理耗时通常增加。调低通常更小，低于 110 时中文小字更容易发虚。\n自动目标模式自行按 150、140…80 DPI 尝试，不使用此框的值。";
            case "JPEG 质量" -> "JPEG 质量｜整页栅格化、自动目标模式生效\n控制栅格化页面图片的有损编码质量，默认 72，范围 10～95。\n调低通常更小，但可能出现色块、边缘毛刺和文字发糊；调高通常更清晰、体积更大。\n不改变页数和页面尺寸，也不会恢复文字搜索；常规及增强降采样不使用此参数。";
            case "目标大小 MB" -> "目标大小｜仅自动目标模式生效\n设置每个 PDF 的体积上限，默认 20 MB；这里 1 MB = 1024×1024 字节。\n已经达标的原件会跳过。目标越小，越可能改用栅格化或降低 DPI，清晰度也可能下降。\n栅格化优先留出 5% 余量；尝试到 80 DPI 仍超限时会显示“未达目标”，不会假报成功达标。";
            default -> text;
        };
        return SettingHelp.label(text, explanation, controls);
    }
    private static String modeHelp(CompressionMode mode) {
        return switch (mode) {
            case STANDARD -> "常规压缩\n根据质量预设重新生成 PDF，通常保留原有文字和矢量内容。图片较多时更可能缩小，矢量内容多时收益可能有限。";
            case DOWNSAMPLE -> "增强降采样\n按“降采样 DPI”处理图片，保留原本可搜索的文字。DPI 越低，图片细节损失通常越多；大量矢量路径仍可能占据较大体积。";
            case RASTER -> "整页栅格化\n将裁切后可见页面全部转换为图片，按“栅格 DPI”和“JPEG 质量”输出。可明显减少复杂矢量内容，但丢失文字搜索/复制、链接和交互表单等结构。";
            case TARGET -> "自动压到目标大小\n原件已达标则跳过；否则根据内容流估算先尝试降采样或直接栅格化，并按 150→80 DPI 尝试。\n可能牺牲文字搜索和清晰度；最低档仍超限会标记未达标。";
        };
    }
    private static ListCell<Quality> qualityCell() {
        return new ListCell<>() {
            @Override protected void updateItem(Quality item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
                setTooltip(empty || item == null ? null : SettingHelp.tooltip(switch (item) {
                    case CLEAR -> "清晰优先：优先保留图像细节，输出通常更大；不代表无损。仅常规压缩使用。";
                    case BALANCED -> "均衡推荐：在图像清晰度与体积之间折中；对已优化的 PDF 可能收益有限。仅常规压缩使用。";
                    case SMALL -> "体积优先：更积极降低图像清晰度，通常更小；细字、扫描件或图例可能变模糊。仅常规压缩使用。";
                }));
            }
        };
    }
    private static ListCell<CompressionMode> modeCell(boolean detailed) {
        return new ListCell<>() {
            @Override protected void updateItem(CompressionMode item, boolean empty) {
                super.updateItem(item, empty);
                setText(null); setGraphic(null); setTooltip(null);
                if (empty || item == null) return;
                if (detailed) setTooltip(SettingHelp.tooltip(modeHelp(item)));
                if (!detailed) { setText(item.toString()); return; }
                Label title = new Label(item.toString());
                title.getStyleClass().add("mode-item-title");
                Label subtitle = new Label(switch (item) {
                    case STANDARD -> "快速处理 · 使用常规质量预设";
                    case DOWNSAMPLE -> "降低图像分辨率 · 保留文字";
                    case RASTER -> "整页转为图片 · 深度压缩";
                    case TARGET -> "设定文件大小 · 自动逐档尝试";
                });
                subtitle.getStyleClass().add("mode-item-caption");
                VBox content = new VBox(4, title, subtitle);
                content.setPadding(new Insets(5, 4, 5, 4));
                setGraphic(content);
            }
        };
    }
    private String detailText(Row row) {
        return "文件：" + row.source.toAbsolutePath() + "\n状态：" + row.status.get()
                + "\n处理设置：" + row.parameters
                + "\n原始大小：" + row.before.get() + "\n结果大小：" + row.after.get()
                + "\n减少：" + row.saving.get()
                + "\n\n完整详情：\n" + row.detail.get();
    }
    private void column(String title, java.util.function.Function<Row, ReadOnlyStringWrapper> value, int width) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> value.apply(cell.getValue()));
        column.setPrefWidth(width);
        ResultTableSupport.styleColumn(column, title.equals("状态"), List.of("原始大小", "结果大小", "减少", "体积变化").contains(title));
        table.getColumns().add(column);
    }
    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF 文件", "*.pdf", "*.PDF"));
        List<File> files = chooser.showOpenMultipleDialog(window);
        if (files != null) addFiles(files);
    }
    private void addFiles(List<File> files) {
        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();
            if (file.isFile() && StrUtil.endWithIgnoreCase(file.getName(), ".pdf")
                    && rows.stream().noneMatch(row -> row.source.equals(path))) rows.add(new Row(path));
        }
        summary.setText("已添加 " + rows.size() + " 个 PDF 文件");
    }
    private void begin() {
        if (running) return;
        try {
            if (StrUtil.isBlank(output.getText())) throw new MyServiceException("请选择输出目录");
            Path executable = new BundledEngine().executable();
            Path directory = Path.of(output.getText().trim()).toAbsolutePath();
            List<Row> queue = List.copyOf(rows);
            if (queue.isEmpty()) throw new MyServiceException("请添加 PDF 文件");
            Files.createDirectories(directory);
            PdfOptions options = new PdfOptions(mode.getValue(), quality.getValue(), imageDpi.getValue(),
                    rasterDpi.getValue(), jpegQuality.getValue(), targetMb.getValue());
            settings.save(directory.toString(), options);
            cancelled.set(false);
            setRunning(true);
            progress.setProgress(0);
            queue.forEach(row -> {
                row.status.set("等待处理"); row.detail.set(""); row.after.set("—"); row.saving.set("—"); row.result = null; row.done = false;
                row.parameters = options.mode() + "；预设=" + options.quality() + "；降采样DPI=" + options.imageDpi()
                        + "；栅格DPI=" + options.rasterDpi() + "；JPEG质量=" + options.jpegQuality() + "；目标MB=" + options.targetMb();
            });
            worker.submit(() -> runQueue(queue, executable, directory, options));
        } catch (Exception e) { showError(e.getMessage()); }
    }
    private void runQueue(List<Row> queue, Path executable, Path directory, PdfOptions options) {
        int finished = 0;
        try {
            for (Row row : queue) {
                if (cancelled.get()) break;
                Platform.runLater(() -> { row.status.set("正在压缩"); summary.setText("正在处理：" + row.source.getFileName()); });
                try {
                    var result = service.compress(executable, row.source, directory, options, cancelled,
                            message -> Platform.runLater(() -> {
                                summary.setText(row.source.getFileName() + " · " + message);
                                row.detail.set(message);
                            }));
                    Platform.runLater(() -> {
                        row.detail.set(result.message());
                        if (!result.success()) { row.status.set("已取消"); return; }
                        var data = result.data();
                        row.result = data.file(); row.done = true;
                        row.after.set(FileUtil.readableFileSize(data.outputBytes()));
                        row.saving.set(String.format(Locale.ROOT, "%.1f%%", 100.0 * (data.originalBytes() - data.outputBytes()) / data.originalBytes()));
                        row.status.set(!data.targetMet() ? "未达目标" : data.originalRetained() ? "保留原件" : "完成");
                        row.detail.set(result.message() + " · " + data.method() + (data.dpi() > 0 ? " " + data.dpi() + " DPI" : "")
                                + " · " + data.pages() + " 页 · " + data.file() + " · " + data.diagnosis());
                    });
                } catch (MyServiceException e) {
                    Platform.runLater(() -> { row.status.set("失败"); row.detail.set(e.getMessage()); });
                }
                int count = ++finished;
                Platform.runLater(() -> progress.setProgress((double) count / queue.size()));
            }
        } finally {
            int count = finished;
            Platform.runLater(() -> {
                if (cancelled.get()) queue.stream().filter(row -> row.status.get().equals("等待处理")).forEach(row -> row.status.set("已取消"));
                setRunning(false);
                summary.setText((cancelled.get() ? "已取消" : "批次完成") + " · 已处理 " + count + "/" + queue.size() + " 个文件；再次开始将按当前设置处理整个列表");
            });
        }
    }
    private void updateModeHint() {
        modeHint.setText(switch (mode.getValue()) {
            case STANDARD -> "常规模式使用原有三档预设，不做整页栅格化。";
            case DOWNSAMPLE -> "增强降采样：默认 110 DPI，重编码图片并保留文字；适合图像占比较高的 PDF。";
            case RASTER -> "整页栅格化：按 CropBox 以 200 DPI 渲染一次，再缩至指定 DPI；默认 JPEG 质量 72。";
            case TARGET -> "自动模式：默认 20 MB（1 MB = 1024² 字节）；低内容流先降采样，必要时按 150→80 DPI 栅格化，预留 5% 余量。未达标会明确提示。";
        });
    }
    private void diagnose() {
        if (running) return;
        List<Row> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        List<Row> queue = selected.isEmpty() ? List.copyOf(rows) : selected;
        if (queue.isEmpty()) { showError("请先添加 PDF"); return; }
        cancelled.set(false); setRunning(true); progress.setProgress(0);
        worker.submit(() -> {
            int count = 0;
            try {
                for (Row row : queue) {
                    if (cancelled.get()) break;
                    Platform.runLater(() -> { row.status.set("诊断中"); summary.setText("正在诊断：" + row.source.getFileName()); });
                    try {
                        var analysis = new PdfInspector().analyze(row.source, cancelled);
                        Platform.runLater(() -> {
                            row.status.set("已诊断"); row.detail.set(analysis.summary() + " · " + analysis.recommendation());
                            summary.setText(row.source.getFileName() + " · " + analysis.summary());
                        });
                    } catch (CancellationException e) {
                        Platform.runLater(() -> row.status.set("已取消")); break;
                    } catch (MyServiceException e) {
                        Platform.runLater(() -> { row.status.set("诊断失败"); row.detail.set(e.getMessage()); });
                    }
                    int done = ++count;
                    Platform.runLater(() -> progress.setProgress((double) done / queue.size()));
                }
            } finally { Platform.runLater(() -> setRunning(false)); }
        });
    }
    private void setRunning(boolean value) {
        running = value; start.setDisable(value); cancel.setDisable(!value);
        editingControls.forEach(control -> control.setDisable(value));
    }
    private void openOutput() {
        try {
            Path directory = Path.of(output.getText().trim()).toAbsolutePath();
            Files.createDirectories(directory);
            new ProcessBuilder("explorer.exe", directory.toString()).start();
        } catch (Exception e) { showError("无法打开目录：" + e.getMessage()); }
    }
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(window); alert.setTitle("操作提示"); alert.setHeaderText(null); alert.setContentText(message); alert.showAndWait();
    }
    @Override public void close() {
        cancelled.set(true);
        worker.shutdown();
        try { if (!worker.awaitTermination(7, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException e) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}





