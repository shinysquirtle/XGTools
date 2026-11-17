package cn.company.xgtools.core;

import cn.company.xgtools.tools.pdf.PdfCompressorTool;
import cn.company.xgtools.tools.image.ImageTool;
import java.util.List;

/** 在此注册新工具，主窗口会自动生成对应的导航入口。 */
public final class ToolRegistry {
    private ToolRegistry() {}
    public static List<DesktopTool> createTools() { return List.of(new PdfCompressorTool(), new ImageTool()); }
}
