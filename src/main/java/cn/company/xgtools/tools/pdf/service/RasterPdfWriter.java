package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.tools.pdf.model.PdfOptions;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import javax.imageio.*;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class RasterPdfWriter {
    static final int RENDER_DPI = 200;
    void render(Path engine, Path input, Path workspace, PdfInspector.Analysis analysis,
                long deadline, AtomicBoolean cancelled, Consumer<String> progress) throws IOException {
        for (var page : analysis.pages()) {
            double pixels = page.width() / 72.0 * RENDER_DPI * page.height() / 72.0 * RENDER_DPI;
            if (pixels > 60_000_000) throw new MyServiceException("页面在 200 DPI 下超过 6000 万像素，请先拆分或缩小页面");
        }
        progress.accept("整页渲染：200 DPI，JPEG 85，共 " + analysis.pages().size() + " 页");
        List<String> command = new ArrayList<>(List.of(engine.toAbsolutePath().toString(), "-dSAFER", "-dBATCH",
                "-dNOPAUSE", "-dQUIET", "-dPDFSTOPONERROR", "-sDEVICE=jpeg", "-dJPEGQ=85", "-r200", "-dUseCropBox",
                "-dTextAlphaBits=4", "-dGraphicsAlphaBits=4",
                "-sOutputFile=" + workspace.resolve("page-%06d.jpg").toAbsolutePath(), "-f", input.toAbsolutePath().toString()));
        GhostscriptRunner.run(command, workspace.resolve("render.log"), deadline, cancelled, progress);
        try (var paths = Files.list(workspace)) {
            long count = paths.filter(p -> p.getFileName().toString().matches("page-\\d{6}\\.jpg")).count();
            if (count != analysis.pages().size()) throw new MyServiceException("渲染页数不一致，停止封装");
        }
        for (int i = 0; i < analysis.pages().size(); i++)
            if (!Files.isRegularFile(pageFile(workspace, i))) throw new MyServiceException("缺少第 " + (i + 1) + " 页渲染图");
    }

    Path write(Path workspace, PdfInspector.Analysis source, int dpi, PdfOptions options,
               long deadline, AtomicBoolean cancelled, Consumer<String> progress) throws IOException {
        Path output = workspace.resolve("raster-" + dpi + ".pdf");
        try (PDDocument document = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            for (int i = 0; i < source.pages().size(); i++) {
                GhostscriptRunner.check(deadline, cancelled);
                progress.accept("栅格化 " + dpi + " DPI：封装第 " + (i + 1) + "/" + source.pages().size() + " 页");
                BufferedImage rendered = ImageIO.read(pageFile(workspace, i).toFile());
                if (rendered == null) throw new MyServiceException("渲染图无法读取");
                var size = source.pages().get(i);
                int width = Math.max(1, Math.round(size.width() / 72 * dpi));
                int height = Math.max(1, Math.round(size.height() / 72 * dpi));
                // 避免渲染时裁切或旋转尺寸异常被强制拉伸掩盖。
                double expectedWidth = size.width() / 72 * RENDER_DPI, expectedHeight = size.height() / 72 * RENDER_DPI;
                if (Math.abs(rendered.getWidth() - expectedWidth) > 3 || Math.abs(rendered.getHeight() - expectedHeight) > 3)
                    throw new MyServiceException("第 " + (i + 1) + " 页渲染尺寸异常，停止输出以避免变形");
                BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = scaled.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(rendered, 0, 0, width, height, null);
                } finally { graphics.dispose(); rendered.flush(); }
                byte[] jpeg = encode(scaled, options.jpegQuality()); scaled.flush();
                PDPage page = new PDPage(new PDRectangle(size.width(), size.height()));
                document.addPage(page);
                try (InputStream stream = new ByteArrayInputStream(jpeg);
                     PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(JPEGFactory.createFromStream(document, stream), 0, 0, size.width(), size.height());
                }
            }
            GhostscriptRunner.check(deadline, cancelled);
            document.save(output.toFile());
        }
        return output;
    }
    private static Path pageFile(Path workspace, int index) {
        return workspace.resolve(String.format(Locale.ROOT, "page-%06d.jpg", index + 1));
    }
    private static byte[] encode(BufferedImage image, int quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new MyServiceException("找不到 JPEG 编码器");
        var writer = writers.next();
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             var stream = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(stream);
            JPEGImageWriteParam params = (JPEGImageWriteParam) writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality / 100f);
            params.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            params.setOptimizeHuffmanTables(true);
            writer.write(null, new IIOImage(image, null, null), params); stream.flush();
            return buffer.toByteArray();
        } finally { writer.dispose(); }
    }
}
