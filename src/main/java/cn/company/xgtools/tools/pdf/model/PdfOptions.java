package cn.company.xgtools.tools.pdf.model;

import cn.company.xgtools.common.MyServiceException;

public record PdfOptions(CompressionMode mode, Quality quality, int imageDpi, int rasterDpi,
                         int jpegQuality, int targetMb) {
    public PdfOptions {
        if (mode == null || quality == null) throw new MyServiceException("请选择压缩方式和质量");
        if (imageDpi < 60 || imageDpi > 300 || rasterDpi < 80 || rasterDpi > 200)
            throw new MyServiceException("降采样 DPI 应为 60～300，栅格化 DPI 应为 80～200");
        if (jpegQuality < 10 || jpegQuality > 95) throw new MyServiceException("JPEG 质量应为 10～95");
        if (targetMb < 1 || targetMb > 2048) throw new MyServiceException("目标大小应为 1～2048 MB");
    }
    public long targetBytes() { return targetMb * 1024L * 1024L; }
    public static PdfOptions standard(Quality quality) {
        return new PdfOptions(CompressionMode.STANDARD, quality, 110, 150, 72, 20);
    }
}
