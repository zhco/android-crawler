# 通用爬虫 - Android 可视化爬虫 App

一个运行在安卓手机上的通用网页爬虫，通过 WebView 加载目标网页，点选元素即可提取数据并导出 CSV。

## 功能

- 输入任意网页 URL，在 App 内置浏览器中加载
- **选列表项**：点击列表区域，自动识别列表容器
- **选字段**：点击元素设为提取字段，可设置多个字段
- 一键爬取，数据保存在内存中
- 导出为 UTF-8 BOM CSV 到手机 Downloads 目录

## 使用教程

1. 打开 App，输入目标网页 URL，点击 GO
2. 等待页面加载完成（支持 JS 渲染页面）
3. 底部切换模式：
   - **选列表项**：点击列表中的任意一项，App 自动识别整个列表容器
   - **选字段**：点击元素，在弹出的面板上为字段命名
4. 配置完成后，点击 **开始爬取**
5. 爬取完成后，点击顶部 **导出 CSV** 保存到 Downloads

## 下载

前往 [Releases](https://github.com/zhco/android-crawler/releases) 下载最新 APK。

## 编译

```bash
./gradlew assembleRelease
```
APK 输出在 `app/build/outputs/apk/release/`
