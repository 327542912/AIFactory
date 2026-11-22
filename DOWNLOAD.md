# AIFactory 下载总表

四个大文件分别在四个固定 tag 里（App 里写死的默认地址、流水线发布的 tag 就是它们，拆开是为了 App 能直接按地址拉）。
这一页只做索引：地址、体积、sha256 都在下面，任何一条都能直接 `curl`。

| # | 文件 | 体积 | sha256 | 直链 |
|---|---|---|---|---|
| 1 | App 安装包 `AIFactory-0.3.5-debug.apk` | 17,206,356 B | `7a8755ab1b24e2b2d7e92207baaff39b1577f23aabf74e8a81d21f138229f50e` | `https://github.com/327542912/AIFactory/releases/download/apk-latest/AIFactory-latest.apk` |
| 2 | Runtime `bootstrap-aarch64.zip` | 29,611,216 B | `45730c16e8a7c852621f33a06ce1fa802cafcec0116b9f11940178aff6486343` | `https://github.com/327542912/AIFactory/releases/download/runtime-latest/bootstrap-aarch64.zip` |
| 3 | Android SDK `android-sdk-aarch64.zip` | 143,210,636 B | `70d10e0a7f180a510134f904b43f4a0c9c7b9dcdcbcb927493b5c8a6a966faa3` | `https://github.com/327542912/AIFactory/releases/download/sdk-latest/android-sdk-aarch64.zip` |
| 4 | Gradle 8.7 `gradle_8.7-1_all.deb` | 131,417,012 B | `027493b1bf4eb5a870074f6b3a32b25ffec6a628996700fefe75e632fc62ce09` | `https://github.com/327542912/AIFactory/releases/download/runtime-extra/gradle_8.7-1_all.deb` |

## 装法（按顺序）

1. 装 1，**覆盖安装，不要卸载**（签名从 0.2.3 起固定，2.3→2.7 可原地升级）（卸载会删 `/data/data/com.aifactory.app`，runtime 和 SDK 全丢）。
2. 设置页第 1、3 张卡直接点「下载并安装」（地址已预填成 2 和 3）。
3. 终端补 Gradle 8.7：

```bash
echo 'deb [trusted=yes] https://github.com/327542912/AIFactory/releases/download/runtime-extra/ ./' >> $PREFIX/etc/apt/sources.list
pkg update && pkg install -y openjdk-21 gradle apksigner zip unzip git
gradle --version    # 8.7
```

`runtime-repo`（261 个 deb）是 apt 源，不需要手工下载，`pkg` 自己拉。
