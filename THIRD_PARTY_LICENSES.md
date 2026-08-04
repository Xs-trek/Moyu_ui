# Third-Party Licenses

## 运行时

本项目没有第三方运行时依赖，不加载远程字体、图片、图标、CSS 或 JavaScript。界面使用系统字体、CSS 几何形状和 Unicode 状态符号；没有包含外部品牌资产。

## 构建依赖

- Vite — MIT License
- Vite 的传递构建依赖（包括 Rollup 与 esbuild）— 依各包随附许可证分发，均只用于开发和构建，不进入业务运行时。

安装后的完整依赖许可证可从 `node_modules` 中各包的 `LICENSE` 文件核对。`package-lock.json` 固定实际安装版本。

## 本地资产

当前 logo 字标、色块、状态标记和导航符号均为本项目原创组合或通用 Unicode 字符，不包含受限字体、照片或第三方品牌图标。后续若替换 `src/assets/icons/` 或 `src/assets/logo/`，集成方必须同时更新本文件并确认允许 Android 应用再分发。
