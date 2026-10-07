# 清爽 M3U8 下载

独立编写的 Android 点播 M3U8 下载器。没有广告 SDK、统计 SDK、会员、账号或远程配置。

[从 GitHub Releases 下载 APK](../../releases/latest)。仓库只保存源码；Release 中的 APK 由 GitHub Actions 编译生成。

## 功能

- M3U8 主播放列表选择最高 BANDWIDTH，支持相对路径和 HTTP 跳转。
- 顺序下载点播 TS 片段；支持 AES-128、IV、BYTERANGE、Referer/Cookie 等自定义请求头。
- 保存到系统文件选择器指定的位置；可选择原始 TS 或尝试用 Android MediaExtractor/MediaMuxer 无损封装 MP4。
- 前台通知显示进度，可以取消。下载期间保持应用的前台服务运行。

## 限制

- 不支持 Widevine 等 DRM、SAMPLE-AES、直播持续录制、独立音轨、动态切换码率。
- MP4 封装依赖手机系统的解复用器，只适合其能识别的编码和 TS 流。失败时选择 TS 格式重新下载。
- 没有原应用的格式转换、剪切、提取音频、视频列表和购买/登录功能。
- 流的服务器必须允许客户端下载；自定义请求头不会自动从浏览器抓取。
- 当前只有一个下载任务；无断点续传，应用缓存需容纳整个视频。

## 构建

安装 Android SDK Platform 35 和 Build Tools 35，运行 `bash build.sh`。构建结果 `dist/clean-hls-debug.apk`。

构建脚本会在本机生成调试签名密钥；签名密钥不纳入仓库。GitHub Actions 每次运行也会生成新的调试密钥，所以目前新生成的 APK 不能覆盖安装先前签名的版本；需要卸载旧版后安装。后续若需要无缝升级，应改用受保护的固定发布签名密钥。
