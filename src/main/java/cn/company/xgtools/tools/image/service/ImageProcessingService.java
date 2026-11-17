package cn.company.xgtools.tools.image.service;

import cn.company.xgtools.common.CommonResult;
import cn.company.xgtools.common.MyServiceException;
import cn.company.xgtools.tools.image.model.ImageOptions;
import cn.hutool.core.img.Img;
import cn.hutool.core.io.FileUtil;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ImageProcessingService {
    public static final String DEFAULT_WATERMARK = "/watermark/guoshi.png";
    private static final Set<String> FORMATS = Set.of("jpg", "jpeg", "png", "bmp");
    public record Output(Path file, long originalBytes, long outputBytes, int width, int height) {}

    public static boolean supports(Path file) {
        return FORMATS.contains(FileUtil.extName(file.toFile()).toLowerCase(Locale.ROOT));
    }

    public CommonResult<Output> process(Path source, Path directory, ImageOptions options, AtomicBoolean cancelled) {
        Path temporary = null;
        try {
            checkCancelled(cancelled);
            if (!Files.isRegularFile(source) || !supports(source))
                throw new MyServiceException("请选择 JPG、JPEG、PNG 或 BMP 图片");
            BufferedImage image = readImage(source);
            int width = image.getWidth(), height = image.getHeight();
            if (options.watermark()) {
                BufferedImage logo;
                if (options.customWatermark() == null) {
                    try (InputStream stream = getClass().getResourceAsStream(DEFAULT_WATERMARK)) {
                        if (stream == null) throw new MyServiceException("内置默认水印不存在");
                        logo = ImageIO.read(stream);
                    }
                } else {
                    logo = readImage(options.customWatermark());
                }
                if (logo == null) throw new MyServiceException("水印文件无法解析");
                image = applyWatermark(image, logo, cancelled);
            }
            checkCancelled(cancelled);
            String format = !options.compress() && FileUtil.extName(source.toFile()).equalsIgnoreCase("png") ? "png" : "jpg";
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".xgtools-image-", "." + format);
            if (options.compress()) {
                // 沿用参考项目的 Hutool JPG 编码、白底和质量设置。
                try (OutputStream stream = Files.newOutputStream(temporary)) {
                    if (!Img.from(image).setTargetImageType("jpg").setQuality(options.quality())
                            .setBackgroundColor(Color.WHITE).write(stream))
                        throw new MyServiceException("找不到 JPG 编码器");
                }
            } else if (!ImageIO.write(image, format, temporary.toFile())) {
                throw new MyServiceException("找不到图片编码器：" + format);
            }
            checkCancelled(cancelled);
            BufferedImage verified = readImage(temporary);
            if (verified.getWidth() != width || verified.getHeight() != height)
                throw new MyServiceException("输出尺寸校验失败");
            long before = Files.size(source), after = Files.size(temporary);
            checkCancelled(cancelled);
            String suffix = options.compress() ? (options.watermark() ? "_水印_压缩" : "_压缩") : "_水印";
            Path destination = moveUnique(temporary, directory, FileUtil.mainName(source.toFile()) + suffix, format);
            temporary = null;
            String message = options.compress() && after >= before ? "已输出 JPG，但体积未减小" : "处理完成";
            return CommonResult.success(message, new Output(destination, before, after, width, height));
        } catch (CancellationException e) {
            return CommonResult.failure("已取消");
        } catch (MyServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new MyServiceException("图片处理失败：" + e.getMessage(), e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { temporary.toFile().deleteOnExit(); }
            }
        }
    }

    private static BufferedImage readImage(Path path) {
        try (var stream = ImageIO.createImageInputStream(path.toFile())) {
            if (stream == null) throw new MyServiceException("无法读取图片：" + path.getFileName());
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new MyServiceException("图片损坏或格式不支持：" + path.getFileName());
            var reader = readers.next();
            try {
                reader.setInput(stream);
                // 在解码前检查尺寸，防止超大位图耗尽桌面进程内存。
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000L)
                    throw new MyServiceException("图片超过 4000 万像素，请先缩小尺寸");
                BufferedImage image = reader.read(0);
                if (image == null) throw new MyServiceException("图片无法解码：" + path.getFileName());
                return image;
            } finally { reader.dispose(); }
        } catch (MyServiceException e) { throw e; }
        catch (IOException e) { throw new MyServiceException("读取图片失败：" + path.getFileName(), e); }
    }

    private static BufferedImage applyWatermark(BufferedImage source, BufferedImage logo, AtomicBoolean cancelled) {
        int drawWidth = 340;
        int drawHeight = Math.max(1, (int) ((long) logo.getHeight() * drawWidth / logo.getWidth()));
        if (drawHeight > 10000) throw new MyServiceException("水印纵横比例过大");
        BufferedImage scaled = new BufferedImage(drawWidth, drawHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D logoGraphics = scaled.createGraphics();
        try {
            logoGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            logoGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            logoGraphics.drawImage(logo, 0, 0, drawWidth, drawHeight, null);
        } finally { logoGraphics.dispose(); }
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(),
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, null);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            graphics.rotate(Math.toRadians(-45), source.getWidth() / 2.0, source.getHeight() / 2.0);
            int stepX = drawWidth + 60, stepY = drawHeight + 180;
            int canvas = (int) Math.hypot(source.getWidth(), source.getHeight());
            int row = 0;
            for (int y = -canvas; y < canvas * 2; y += stepY) {
                checkCancelled(cancelled);
                int offset = (row++ % 2 == 0) ? 0 : stepX / 2;
                for (int x = -canvas + offset; x < canvas * 2; x += stepX) {
                    checkCancelled(cancelled);
                    graphics.drawImage(scaled, x, y, null);
                }
            }
        } finally { graphics.dispose(); }
        return result;
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    private static Path moveUnique(Path temporary, Path directory, String name, String extension) throws IOException {
        for (int index = 0; ; index++) {
            Path output = directory.resolve(name + (index == 0 ? "" : "_" + index) + "." + extension);
            try { return Files.move(temporary, output); }
            catch (FileAlreadyExistsException ignored) { }
        }
    }
}
