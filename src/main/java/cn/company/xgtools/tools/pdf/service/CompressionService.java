package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.CommonResult;
import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.tools.pdf.model.*;
import cn.hutool.core.io.FileUtil;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CompressionService {
    public record Output(Path file, long originalBytes, long outputBytes, String method, int dpi,
                         int pages, boolean targetMet, boolean originalRetained, String diagnosis) {}
    private record Candidate(Path path, String method, int dpi) {}
    private final Duration timeout;
    private final PdfInspector inspector = new PdfInspector();
    public CompressionService() { this(Duration.ofMinutes(30)); }
    public CompressionService(Duration timeout) { this.timeout = timeout; }

    public CommonResult<Output> compress(Path engine, Path input, Path directory, Quality quality, AtomicBoolean cancelled) {
        return compress(engine, input, directory, PdfOptions.standard(quality), cancelled, message -> {});
    }

    public CommonResult<Output> compress(Path engine, Path input, Path directory, PdfOptions options,
                                         AtomicBoolean cancelled, Consumer<String> progress) {
        Path workspace = null;
        try {
            PdfInspector.check(cancelled);
            if (!Files.isRegularFile(engine)) throw new MyServiceException("Ghostscript 程序不存在，请选择 gswin64c.exe");
            long deadline = System.nanoTime() + timeout.toNanos();
            progress.accept("正在诊断页数、页面尺寸和内容流…");
            var analysis = inspector.analyze(input, cancelled);
            GhostscriptRunner.check(deadline, cancelled);
            progress.accept(analysis.summary());
            long original = analysis.bytes();
            if (options.mode() == CompressionMode.TARGET && original <= options.targetBytes())
                return CommonResult.success("原文件已满足目标，已跳过",
                        new Output(input, original, original, "原件已达标", 0, analysis.pages().size(), true, true, analysis.summary()));
            Files.createDirectories(directory);
            workspace = Files.createTempDirectory(directory.toAbsolutePath(), ".xg-pdf-");
            Candidate best = null;
            if (options.mode() == CompressionMode.STANDARD || options.mode() == CompressionMode.DOWNSAMPLE) {
                best = vector(engine, input, workspace, options, options.mode() == CompressionMode.DOWNSAMPLE,
                        deadline, cancelled, progress);
            } else if (options.mode() == CompressionMode.TARGET && analysis.contentBytes() <= options.targetBytes() * .6) {
                try {
                    progress.accept("优先尝试保留文字的增强降采样");
                    best = vector(engine, input, workspace, options, true, deadline, cancelled, progress);
                    inspector.validate(best.path(), analysis, cancelled);
                } catch (MyServiceException e) {
                    GhostscriptRunner.check(deadline, cancelled);
                    progress.accept("降采样未成功，改用栅格化：" + e.getMessage());
                    best = null;
                }
            }
            boolean rasterNeeded = options.mode() == CompressionMode.RASTER
                    || options.mode() == CompressionMode.TARGET && (best == null || Files.size(best.path()) > options.targetBytes());
            if (rasterNeeded) {
                RasterPdfWriter raster = new RasterPdfWriter();
                raster.render(engine, input, workspace, analysis, deadline, cancelled, progress);
                int[] levels = options.mode() == CompressionMode.TARGET
                        ? new int[]{150, 140, 130, 120, 110, 100, 90, 80} : new int[]{options.rasterDpi()};
                for (int dpi : levels) {
                    GhostscriptRunner.check(deadline, cancelled);
                    Candidate current = new Candidate(raster.write(workspace, analysis, dpi, options, deadline, cancelled, progress),
                            "整页栅格化", dpi);
                    long bytes = Files.size(current.path());
                    progress.accept(String.format(Locale.ROOT, "%d DPI 成品 %.2f MB", dpi, bytes / 1048576.0));
                    if (best == null || bytes < Files.size(best.path())) best = current;
                    // 按最终 PDF 实际体积判断，并预留 5% 余量；不只统计 JPEG 大小。
                    if (options.mode() != CompressionMode.TARGET || bytes <= options.targetBytes() * .95) break;
                }
            }
            if (best == null) throw new MyServiceException("没有生成有效候选文件");
            progress.accept("校验完整性、页数、逐页尺寸，并抽样渲染首/中/末页…");
            inspector.validate(best.path(), analysis, cancelled);
            GhostscriptRunner.check(deadline, cancelled);
            long compressed = Files.size(best.path());
            boolean retained = compressed >= original;
            long finalSize = retained ? original : compressed;
            boolean targetMet = options.mode() != CompressionMode.TARGET || finalSize <= options.targetBytes();
            String message = targetMet ? (retained ? "未获得体积收益，保留原文件" : "压缩完成")
                    : "未达到目标大小；已尝试至 80 DPI，请降低 JPEG 质量或提高目标大小";
            String method = retained ? "保留原件" : best.method();
            int dpi = retained ? 0 : best.dpi();
            Path result = retained ? input : moveUnique(best.path(), directory,
                    FileUtil.mainName(input.toFile()) + (options.mode() == CompressionMode.TARGET
                            ? "-" + options.targetMb() + "M" + (targetMet ? "" : "_未达标") : options.mode() == CompressionMode.RASTER ? "_栅格压缩" : "_压缩"));
            return CommonResult.success(message, new Output(result, original, finalSize, method, dpi,
                    analysis.pages().size(), targetMet, retained, analysis.summary()));
        } catch (CancellationException e) { return CommonResult.failure("已取消"); }
        catch (MyServiceException e) { throw e; }
        catch (Exception e) { throw new MyServiceException("压缩失败：" + e.getMessage(), e); }
        finally { cleanup(workspace, directory); }
    }

    private Candidate vector(Path engine, Path input, Path workspace, PdfOptions options, boolean downsample,
                             long deadline, AtomicBoolean cancelled, Consumer<String> progress) {
        Path output = workspace.resolve("vector.pdf");
        List<String> command = new ArrayList<>(List.of(engine.toAbsolutePath().toString(), "-dSAFER", "-dBATCH",
                "-dNOPAUSE", "-dQUIET", "-dPDFSTOPONERROR", "-sDEVICE=pdfwrite",
                "-dPDFSETTINGS=" + (downsample ? "/screen" : options.quality().preset()), "-dAutoRotatePages=/None"));
        // PDFSETTINGS 使用 sRGB / DeviceIndependentColor；GS 10.07+ 默认 Simple
        // 不能将这些空间用于透明混合。普通 PDF 压缩保留原混合空间，避免 rangecheck。
        command.add("-dBlendConversionStrategy=/None");
        if (downsample) {
            command.add("-dCompatibilityLevel=1.5"); command.add("-dPassThroughJPEGImages=false");
            for (String type : List.of("Color", "Gray")) {
                command.add("-dDownsample" + type + "Images=true");
                command.add("-d" + type + "ImageResolution=" + options.imageDpi());
                command.add("-d" + type + "ImageDownsampleType=/Bicubic");
                command.add("-d" + type + "ImageDownsampleThreshold=1.0");
                command.add("-dAutoFilter" + type + "Images=false");
                command.add("-d" + type + "ImageFilter=/DCTEncode");
            }
        }
        command.add("-sOutputFile=" + output.toAbsolutePath()); command.add("-f"); command.add(input.toAbsolutePath().toString());
        progress.accept(downsample ? "增强降采样：" + options.imageDpi() + " DPI，保留文字" : "常规压缩：" + options.quality());
        GhostscriptRunner.run(command, workspace.resolve("vector.log"), deadline, cancelled, progress);
        return new Candidate(output, downsample ? "增强降采样" : "常规压缩", downsample ? options.imageDpi() : 0);
    }

    private static Path moveUnique(Path temporary, Path directory, String base) throws IOException {
        for (int index = 0; ; index++) {
            Path output = directory.resolve(base + (index == 0 ? "" : "_" + index) + ".pdf");
            try { return Files.move(temporary, output); }
            catch (FileAlreadyExistsException ignored) { }
        }
    }
    private static void cleanup(Path workspace, Path directory) {
        if (workspace == null) return;
        Path parent = directory.toAbsolutePath().normalize();
        Path owned = workspace.toAbsolutePath().normalize();
        if (!owned.getParent().equals(parent) || !owned.getFileName().toString().startsWith(".xg-pdf-")) return;
        // 每个任务使用独立随机目录；只清理本任务直接创建的文件，不递归跟随目录或链接。
        try (var files = Files.newDirectoryStream(owned)) {
            for (Path file : files) {
                if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    try { Files.deleteIfExists(file); }
                    catch (IOException e) { file.toFile().deleteOnExit(); }
                }
            }
        } catch (IOException ignored) { }
        try { Files.deleteIfExists(owned); } catch (IOException e) { owned.toFile().deleteOnExit(); }
    }
}
