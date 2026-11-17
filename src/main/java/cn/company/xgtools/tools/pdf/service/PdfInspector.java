package cn.company.xgtools.tools.pdf.service;

import cn.company.xgtools.common.MyServiceException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PdfInspector {
    public record PageSize(float width, float height) {}
    public record Analysis(long bytes, long imageBytes, long contentBytes, int croppedPages, List<PageSize> pages) {
        public String summary() {
            return String.format(Locale.ROOT, "%d 页；总计 %.2f MB；图像流 %.2f MB；内容/Form 流估算 %.2f MB；裁切页 %d",
                    pages.size(), bytes / 1048576.0, imageBytes / 1048576.0, contentBytes / 1048576.0, croppedPages);
        }
        public String recommendation() {
            return contentBytes > 15L * 1048576
                    ? "内容流较大，建议尝试栅格化或自动目标模式（经验判断，不保证体积）"
                    : "建议先尝试增强降采样；未达目标时再考虑栅格化";
        }
    }

    public Analysis analyze(Path file, AtomicBoolean cancelled) {
        try {
            checkEnvelope(file);
            try (PDDocument document = Loader.loadPDF(file.toFile())) {
                if (document.isEncrypted()) throw new MyServiceException("暂不支持加密 PDF，请先解密");
                if (document.getNumberOfPages() < 1) throw new MyServiceException("PDF 没有有效页面");
                List<PageSize> pages = new ArrayList<>();
                int cropped = 0;
                for (PDPage page : document.getPages()) {
                    check(cancelled);
                    var crop = page.getCropBox(); var media = page.getMediaBox();
                    float unit = page.getUserUnit();
                    float width = crop.getWidth() * unit, height = crop.getHeight() * unit;
                    if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0)
                        throw new MyServiceException("PDF 页面尺寸无效");
                    int rotation = Math.floorMod(page.getRotation(), 360);
                    if (rotation == 90 || rotation == 270) { float swap = width; width = height; height = swap; }
                    pages.add(new PageSize(width, height));
                    if (Math.abs(crop.getLowerLeftX() - media.getLowerLeftX()) > 1
                            || Math.abs(crop.getLowerLeftY() - media.getLowerLeftY()) > 1
                            || Math.abs(crop.getUpperRightX() - media.getUpperRightX()) > 1
                            || Math.abs(crop.getUpperRightY() - media.getUpperRightY()) > 1) cropped++;
                    // 强制解码所有页面内容流，避免只读取页树而漏掉损坏流。
                    try (InputStream contents = page.getContents()) { contents.transferTo(OutputStream.nullOutputStream()); }
                }
                long images = 0, content = 0;
                Set<COSStream> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                var cos = document.getDocument();
                for (var key : new ArrayList<>(cos.getXrefTable().keySet())) {
                    check(cancelled);
                    var value = cos.getObjectFromPool(key).getObject();
                    if (!(value instanceof COSStream stream) || !seen.add(stream)) continue;
                    String subtype = stream.getNameAsString(COSName.SUBTYPE);
                    String type = stream.getNameAsString(COSName.TYPE);
                    long length = stream.getLength();
                    if ("Image".equals(subtype)) images += length;
                    else if (!Set.of("XRef", "ObjStm", "Metadata").contains(type == null ? "" : type)
                            && !"XML".equals(subtype) && !stream.containsKey(COSName.getPDFName("Length1")))
                        content += length;
                }
                return new Analysis(Files.size(file), images, content, cropped, List.copyOf(pages));
            }
        } catch (MyServiceException | CancellationException e) { throw e; }
        catch (Exception e) { throw new MyServiceException("PDF 诊断失败：" + e.getMessage(), e); }
    }

    public void validate(Path file, Analysis source, AtomicBoolean cancelled) {
        Analysis output = analyze(file, cancelled);
        if (output.pages().size() != source.pages().size()) throw new MyServiceException("输出页数不一致，已丢弃结果");
        for (int i = 0; i < source.pages().size(); i++) {
            PageSize expected = source.pages().get(i), actual = output.pages().get(i);
            if (Math.abs(expected.width() - actual.width()) > 1 || Math.abs(expected.height() - actual.height()) > 1)
                throw new MyServiceException("第 " + (i + 1) + " 页显示尺寸不一致，已丢弃结果");
        }
        // 首、中、末页实际解码渲染；这不能替代用户的清晰度目视抽检。
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            Set<Integer> samples = new LinkedHashSet<>(List.of(0, document.getNumberOfPages() / 2, document.getNumberOfPages() - 1));
            for (int page : samples) {
                check(cancelled);
                PageSize size = output.pages().get(page);
                float dpi = Math.min(96, 1200 * 72 / Math.max(size.width(), size.height()));
                renderer.renderImageWithDPI(page, dpi).flush();
            }
        } catch (CancellationException e) { throw e; }
        catch (Exception e) { throw new MyServiceException("输出抽样页面无法渲染", e); }
    }

    private static void checkEnvelope(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new MyServiceException("PDF 文件不存在");
        try (var channel = Files.newByteChannel(file)) {
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(1024, channel.size()));
            channel.read(head); head.flip();
            if (!StandardCharsets.ISO_8859_1.decode(head).toString().contains("%PDF-"))
                throw new MyServiceException("文件缺少 PDF 标识");
            channel.position(Math.max(0, channel.size() - 4096));
            ByteBuffer tail = ByteBuffer.allocate(4096); channel.read(tail); tail.flip();
            if (!StandardCharsets.ISO_8859_1.decode(tail).toString().contains("%%EOF"))
                throw new MyServiceException("PDF 缺少 EOF 结束标记，文件可能已截断");
        }
    }
    public static void check(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
