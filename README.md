# AI 分类相册 (AI Photo Gallery)

Android 原生 App（Kotlin），在手机上用 TensorFlow Lite 离线扫描本地相册，自动识别照片场景（风景、美食、动物、交通工具等）并按标签分类展示。

## 特性
- **完全离线**：AI 模型打包进 APK，无需网络，隐私安全
- **场景识别**：使用 EfficientNet-Lite0（约 3.7MB）识别 1000 类 ImageNet 场景/物体
- **相册扫描**：自动扫描手机本地图片并逐张分类
- **网格展示**：按识别标签分组显示照片及置信度

## 技术栈
- Kotlin + Android (minSdk 26 / target 34)
- TensorFlow Lite 2.14 (`org.tensorflow:tensorflow-lite`)
- Coil 图片加载
- Jetpack AppCompat / Material 3
- GitHub Actions 自动构建 APK

## 构建
在 Termux 里改代码后 push 到 GitHub，Actions 自动出 APK：
```bash
git add .
git commit -m "..."
git push origin main
```
构建完成后到仓库 **Actions** 页面下载 `app-release` artifact（APK），安装到手机即可。

## 模型
- `app/src/main/assets/mobilenet_v2_1.0_224.tflite` — EfficientNet-Lite0 分类模型
- `app/src/main/assets/labels.txt` — ImageNet 1000 类标签

## 权限
- Android 13+：`READ_MEDIA_IMAGES`
- Android 12 及以下：`READ_EXTERNAL_STORAGE`