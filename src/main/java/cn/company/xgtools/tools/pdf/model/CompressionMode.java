package cn.company.xgtools.tools.pdf.model;

public enum CompressionMode {
    STANDARD("常规压缩"), DOWNSAMPLE("增强降采样（保留文字）"),
    RASTER("整页栅格化"), TARGET("自动压到目标大小");
    private final String label;
    CompressionMode(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
