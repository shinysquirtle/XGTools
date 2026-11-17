# XGTools 应用图标
选定方案：四个圆角方块模块，右上模块略微上移；蓝色主题、透明背景。
生成方式：内置 image_gen 编辑已选方案，非 CLI。
提示词：保留四块造型、布局、圆角和立体高光；仅将紫色改为深钴蓝、亮天蓝、中蓝和浅冰蓝；透明背景，不增加文字或物体。
xgtools.png 为生成原图；xgtools.ico 为同图的 Windows 多尺寸容器。
包含 16、20、24、32、40、48、64、128、256 像素尺寸。
运行 scripts/generate-icon.ps1 可从 PNG 重新生成 ICO，仅做尺寸适配和格式转换。
XGToolsApp 加载 PNG 作为窗口、任务栏和侧栏图标；package.ps1 使用 ICO 打包 EXE。