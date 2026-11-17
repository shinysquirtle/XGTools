package cn.company.xgtools.tools.pdf.model;

public enum Quality {
    CLEAR("清晰优先", "/printer"), BALANCED("均衡推荐", "/ebook"), SMALL("体积优先", "/screen");
    private final String label;
    private final String preset;
    Quality(String label, String preset) { this.label = label; this.preset = preset; }
    public String preset() { return preset; }
    @Override public String toString() { return label; }
}
