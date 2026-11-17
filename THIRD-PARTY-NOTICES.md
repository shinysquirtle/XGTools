# 第三方组件
- JavaFX：GPL v2 with Classpath Exception；https://openjfx.io/
- Hutool：MulanPSL-2.0；https://github.com/dromara/hutool
- Ghostscript：AGPL v3 或 Artifex 商业许可；https://artifex.com/licensing

应用内置 Ghostscript 10.08.0，运行时固定使用应用目录内的引擎；不提供引擎选择。
引擎组件来自开发环境现有安装，保留 doc/COPYING 及附带文档。当前用于本机功能测试，尚未制作对外分发包。
内置时应保留引擎附带的许可证，并按适用许可提供所需源码及其他材料。
仅在公司内部使用、独立进程调用或不捆绑引擎，并不自动免除适用的许可义务。
第三方 JAR、Java 运行环境中附带的许可文件应随交付保留。

- Apache PDFBox：Apache License 2.0；https://pdfbox.apache.org/ （用于结构和页数校验）
