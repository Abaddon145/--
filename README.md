# Luca 兴趣课堂

离线 Android 应用，用来管理孩子的兴趣班：课程、课时套餐、打卡、日历里的请假和调课、费用与统计，以及本机提醒、备份和导出。

- 显示名称：Luca 兴趣课堂
- applicationId：`com.luca.classmanager`
- minSdk 28，compileSdk / targetSdk 35
- Kotlin、Jetpack Compose、Material 3、MVVM + Repository

应用不申请网络访问、定位或通讯录权限。通知使用 `POST_NOTIFICATIONS`（Android 13 及以上在设置里授权）。备份和 CSV 通过系统文件选择器保存，不申请存储权限。重启后重新登记提醒使用 `RECEIVE_BOOT_COMPLETED`。WorkManager 还会合并 `WAKE_LOCK` 和 `FOREGROUND_SERVICE`，只用于本机定时；已从合并清单里去掉 `ACCESS_NETWORK_STATE`。

## 安装

可安装的精简包大约 3.6MB（R8 压缩代码和资源，用本机 Android debug 密钥签名，`debuggable` 为 false）：

- `artifacts/app-debug-slim.apk`
- 或构建后的 `app/build/outputs/apk/debugMinified/app-debugMinified.apk`

完整 Debug 包不压缩，体积大约 60MB，仍可安装：

- `artifacts/app-debug.apk`
- 或构建后的 `app/build/outputs/apk/debug/app-debug.apk`

未签名的 Release 包同样开启了 `minifyEnabled` 和 `shrinkResources`，安装前需要自己签名：

- `artifacts/app-release-unsigned.apk`
- 或 `app/build/outputs/apk/release/app-release-unsigned.apk`

精简包使用 Android Gradle Plugin 在本机生成的 `~/.android/debug.keystore`，仓库里没有这份密钥，也不要提交它。Release 构建默认不签名。要自己签名，在 `app/build.gradle.kts` 的 `release` 里换成你的 keystore，例如：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("/secure/path/luca.jks")
        storePassword = "你的密码"
        keyAlias = "luca"
        keyPassword = "你的密码"
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
    }
}
```

不要把 keystore 或密码提交到仓库。

## 构建

1. 安装 JDK 17 或更新版本，以及 Android SDK 35（Build-Tools 35）。
2. 用 Android Studio 打开本目录。
3. 如果本地 SDK 路径没有自动写入，在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/Android/sdk
```

4. 构建和测试：

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugMinified
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

`assembleDebugMinified` 生成可安装的精简包。构建完成后可以复制为 `artifacts/app-debug-slim.apk`。

## 功能

- 首页看今天的课、剩余课时和费用。点一节课可以打卡。
- 课程可以新增、编辑、暂停、归档或删除。每周课表只生成计划，改课表不会改写已经打过的卡。
- 课时套餐、缴费、手动调整和打卡共用一套账。未知金额显示「待补充」，不会当成 0。赠送课时默认 0 元，可在设置里改成沿用该套餐已知的购买单价。
- 日历有月视图和周视图。可以请假、调课、补课和检查时间重叠。请假默认不扣课时。
- 统计和费用中心的柱状图来自本机记录，可以按课程、月份和年份筛选。
- 设置里可以切换浅色、深色或跟随系统。

## 提醒

在设置里打开「提醒已开启」。Android 13 及以上会先请求通知权限。

- 上课前：按所选提前时间（10 分钟、30 分钟、1 小时或 2 小时）提醒。用的是系统允许的非精确闹钟，省电模式下可能略有偏差。
- 当天未打卡：到达设定的小时，并且这节课已经结束、还没有打卡时提醒。
- 续费：剩余课时低于提醒线时提醒。默认少于 3 课时，可在设置或单门课程里改。
- 暂停课程：当天提醒哪些课处于暂停。

检查大约每 15 分钟由 WorkManager 做一次。下一节课还会再登记一个闹钟。手机关机重启后，系统会恢复 WorkManager；开机广播也会再登记一次。强制停止应用后，要重新打开应用，提醒才会继续。

## 备份与导出

设置里可以导出 JSON 完整备份，或分别导出课程、打卡、缴费的 CSV。文件由系统选择器保存到你指定的位置。

JSON 备份带有 `version`（当前为 1）和内容的 SHA-256 校验。照片如果已经复制进应用私有目录 `files/attachments`，会以 Base64 放进同一份 JSON，恢复时写回该目录。恢复前会先校验；校验失败不会改数据库。确认覆盖后才写入。写入过程放在一个数据库事务里，失败会回滚，原来的课程数据还在。

CSV 使用 UTF-8，并带 BOM，方便表格软件打开中文。金额未知的格子留空。

## 数据规则

- 金额用整数分，课时用整数半课时（1 单位 = 0.5 课时）。
- 可用课时 = 购买 + 赠送 + 手动调整。已消耗 = 带日期的打卡 + 还没补日期的历史记录。剩余 = 可用 − 已消耗。
- 多套餐按购买先后消耗金额。套餐自动带出的缴费不会再加进累计缴费。
- 数据库版本升级只走 `LucaMigrations`，不会清空用户数据。
- 首次启动写入一次预置课程：篮球、学而思思维、羽毛球。历史已上次数没有具体日期。补录时转成打卡，已消耗课时不会再加一次。
