# Minecraft 1.21.1 → 26.1.2 迁移记录

> 项目：Observable  
> 目标版本：26.1.2 (NeoForge 26.1.2.81 / Fabric Loader 0.19.3 / Architectury 20.0.9)  
> 迁移日期：2026-07-16

---

## 修改的文件

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `common/.../client/Overlay.kt` | **重写** | 渲染 API 全面重构 |
| `common/.../client/MinecraftHelper.java` | **新建** | Kotlin/Java 互操作辅助类，绕过私有字段冲突 |
| `common/.../mixin/ServerLevelMixin.java` | **修改** | `FluidState.tick()` 方法签名变更 |
| `forge/.../ForgeClientHooks.kt` | **重写** | NeoForge 渲染事件 API 重构 |
| `common/build.gradle` | **修改** | `shadowJar` 启用 `zip64 = true` |
| `fabric/build.gradle` | **修改** | shadow 插件升级到 9.5.1；移除 `mainSpec`；启用 `zip64`；JVM target 21→25 |
| `forge/build.gradle` | **修改** | shadow 插件升级到 9.5.1；移除 `mainSpec`；启用 `zip64` |
| `forge/.../ObservableForge.java` | **修改** | `FMLEnvironment.dist` → `getDist()` |
| `fabric/.../Client.kt` | **重写** | Fabric 渲染事件 API 重构 |
| `build.gradle` | **修改** | 根项目及子项目 Kotlin JVM target 统一为 25 |
| `common/.../Observable.kt` | **修改** | 修复 trailing comma（ktLint） |
| `common/.../ResourceKeySerializer.kt` | **修改** | 修复 import 排序（ktLint） |
| `common/.../ProfilingData.kt` | **修改** | 修复 import 排序（ktLint） |

---

## API 变更详情

### 1. 渲染系统全面重构

这是本次迁移最大的变更。Minecraft 26.1.2 的渲染管线完全重构：

- **`RenderType`** 从 `net.minecraft.client.renderer` 移至 `net.minecraft.client.renderer.rendertype`，类变为 `final`，不再支持子类化
- **`VertexBuffer`** 已被移除，替换为 `MeshData`（`BufferBuilder.build()` 直接返回）
- **`BufferBuilder`** 构造器从 4 参数变为 3 参数：`BufferBuilder(ByteBufferBuilder, VertexFormat.Mode, VertexFormat)`
- **`RenderType`** 现在通过 `RenderType.create(name, setup)` 配合 `RenderSetup.builder(pipeline)` 创建
- **`RenderSystem`** 以下静态方法全部移除（渲染状态由 `RenderType`/`RenderPipeline` 管理）：
  - `enableBlend()` / `disableBlend()`
  - `blendFunc()` / `defaultBlendFunc()`
  - `enableDepthTest()` / `disableDepthTest()`
- **`GameRenderer.getPositionColorShader()`** 移除
- **`RenderStateShard`** / **`CompositeState`** 移除，替换为 `RenderSetup` / `RenderPipeline`
- 自定义渲染类型现在基于 `RenderPipelines.DEBUG_QUADS` 和 POSITION_COLOR shader

```kotlin
// 旧 API
class OverlayRenderType(...) : RenderType(...) { ... }  // 反射创建
val vbo = VertexBuffer(VertexBuffer.Usage.DYNAMIC)
vbo.bind(); vbo.drawWithShader(...); VertexBuffer.unbind()
RenderSystem.enableBlend()
RenderSystem.disableDepthTest()

// 新 API
val setup = RenderSetup.builder(RenderPipelines.DEBUG_QUADS)
    .setOutputTarget(OutputTarget.MAIN_TARGET)
    .createRenderSetup()
val renderType = RenderType.create("observable_overlay", setup)
val mesh = buf.build()  // 返回 MeshData
renderType.draw(mesh)   // 渲染状态由 Pipeline 管理
```

### 2. Camera 字段私有化

| 旧 API | 新 API | 处理方式 |
|--------|--------|---------|
| `camera.position` (公开字段) | `camera.position()` (公开方法) | Java 辅助类 `MinecraftHelper.getCameraPosition()` |
| `camera.blockPosition` (公开字段) | `camera.blockPosition()` (公开方法) | Java 辅助类 `MinecraftHelper.getCameraBlockPosition()` |

> **根因**：Kotlin 编译器遇到 Java 类中同名的私有字段和公开方法时，优先匹配字段导致编译错误。  
> **解决方案**：通过 Java 辅助类调用 `camera.position()`，因为 Java 代码中方法调用与字段访问语法不同，不会产生歧义。

### 3. ChunkPos 构造器变更

```java
// 旧 API
new ChunkPos(BlockPos)              // 便捷构造器已移除

// 新 API
ChunkPos.containing(BlockPos)       // 静态工厂方法
ChunkPos(int, int)                  // (x, z) 构造器仍保留
```

### 4. ResourceKey API 重命名

```java
// 旧 API
resourceKey.location()              // 返回 Identifier

// 新 API
resourceKey.identifier()            // 返回 Identifier
```

涉及文件：`Overlay.kt` 中 `level.dimension().location()` → `level.dimension().identifier()`

### 5. FluidState.tick() 新增参数

```java
// 旧 API
fluidState.tick(Level, BlockPos)

// 新 API
fluidState.tick(ServerLevel, BlockPos, BlockState)
```

- Mixin `@Redirect` 的 `target` 签名需要同步更新
- 处理函数需要捕获并转发新增的 `BlockState` 参数

### 6. Minecraft 客户端字段私有化

| 旧 API | 新 API |
|--------|--------|
| `Minecraft.getInstance().timer` | `Minecraft.getInstance().getDeltaTracker()` → `DeltaTracker` 接口 |
| `Minecraft.getInstance().renderBuffers` | `Minecraft.getInstance().renderBuffers()` |

- `DeltaTracker.getGameTimeDeltaPartialTick(boolean)` 功能与旧 `Timer` 类相同

### 7. NeoForge 渲染事件重构

```java
// 旧 API
@SubscribeEvent
fun onRender(ev: RenderLevelStageEvent) {
    if (ev.stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
        ev.poseStack           // 公开字段
        ev.partialTick         // 公开字段
        ev.projectionMatrix    // 公开字段
    }
}

// 新 API — 订阅具体的阶段子类
@SubscribeEvent
fun onRender(ev: RenderLevelStageEvent.AfterTranslucentParticles) {
    ev.getPoseStack()          // getter 方法
    // projectionMatrix 已移除，由 RenderType.draw() 内部处理
    // partialTick 需要从 DeltaTracker 获取
}
```

可用的阶段子类：
- `AfterLevel`, `AfterOpaqueBlocks`, `AfterOpaqueFeatures`
- `AfterSky`, `AfterTranslucentBlocks`, `AfterTranslucentFeatures`
- `AfterTranslucentParticles`, `AfterWeather`

### 8. Fabric 渲染事件重构

```java
// 旧 API
WorldRenderEvents.LAST.register { ctx ->
    ctx.matrixStack()
    ctx.projectionMatrix()
    ctx.tickCounter().getGameTimeDeltaPartialTick(true)
}

// 新 API
LevelRenderEvents.END_MAIN.register { ctx ->
    ctx.poseStack()
    // projectionMatrix 已移除
    // tickCounter 需要从 DeltaTracker 获取
}
```

### 9. NeoForge FMLEnvironment 变更

```java
// 旧 API
FMLEnvironment.dist               // 公开字段

// 新 API
FMLEnvironment.getDist()          // 静态 getter 方法
```

### 10. JVM 编译目标升级

- Java 编译目标：`options.release = 25`
- Kotlin JVM 目标：`jvmTarget = "25"` (必须在根项目和所有子项目中统一设置)

---

## 新增辅助类

### `MinecraftHelper.java` (common/src/main/java/observable/client/)

```java
public class MinecraftHelper {
    public static Vec3 getCameraPosition(Camera camera);
    public static BlockPos getCameraBlockPosition(Camera camera);
    public static RenderBuffers getRenderBuffers(Minecraft minecraft);
    public static DeltaTracker getDeltaTracker(Minecraft minecraft);
}
```

用于解决 Kotlin 访问 Java 类时，同名私有字段与公开 getter 方法之间的编译器歧义问题。

---

## 编译状态

| 模块 | compileKotlin | compileJava | jar |
|------|:---:|:---:|:---:|
| common | ✅ | ✅ | ✅ |
| fabric | ✅ | ✅ | ✅ |
| forge | ✅ | ✅ | ✅ |

---

## 构建流程错误修复

### 1. ktLint 代码风格违规（3 处）

| 文件 | 违规 | 修复 |
|------|------|------|
| `Observable.kt:47` | 不必要的尾随逗号 `trailing-comma-on-call-site` | `KEYBIND_CATEGORY,` → `KEYBIND_CATEGORY` |
| `ResourceKeySerializer.kt:3` | import 顺序不合规 `import-ordering` | `Identifier` 和 `ResourceKey` 交换位置 |
| `ProfilingData.kt:10` | import 顺序不合规 `import-ordering` | 同上 |

ktLint 要求 import 严格按字典序排列，且同包内无空行。

### 2. shadowJar Zip64 溢出

**错误**：`Zip64RequiredException: archive contains more than 65535 entries`

**原因**：Minecraft 26.1.2 的依赖包含大量 class 文件，打包后的 jar 条目数超过 ZIP 格式的 65535 上限。

**修复**：在 `common/build.gradle` 中启用 zip64：

```groovy
tasks.shadowJar {
    zip64 = true
}
```

> 注意：`com.gradleup.shadow` 9.x 使用 `zip64` 属性名，而非 Gradle 标准 `isZip64`。

### 3. JVM Target 不一致

**错误**：`Inconsistent JVM-target compatibility detected for tasks 'compileJava' (25) and 'compileKotlin' (21)`

**修复**：
- `fabric/build.gradle`：`jvmTarget = "21"` → `"25"`
- `build.gradle`：根项目 `compileKotlin` / `compileTestKotlin` 的 `jvmTarget` 同步为 `"25"`

### 4. shadow 插件与 Gradle 9.x 不兼容（导致产出无效的 NeoForge 文件）

**错误**：
- `groovy.lang.MissingPropertyException: No such property: mode`
- `forge-5.5.0-raw.jar` 仅 4KB，缺失 common 模块代码

**根因**：fabric 和 forge 模块使用 `com.github.johnrengelman.shadow` 8.1.1（旧版），该版本依赖 Gradle 内部 API `FileCopyDetails.mode`，Gradle 9.x 已移除此属性。`shadowJar` 任务静默失败，未产出包含 common 代码的最终合并 jar。

**修复**：
- `fabric/build.gradle`、`forge/build.gradle`：shadow 插件从 `com.github.johnrengelman.shadow:8.1.1` 升级到 `com.gradleup.shadow:9.5.1`
- 移除 `mainSpec.sourcePaths.clear()`（v9.x 不再需要，插件不再自动包含 main sourceSet）
- 添加 `zip64 = true`（Minecraft 26.1.2 依赖 class 数量超出 ZIP 65535 条目限制）

**最终产物**：

| 文件 | 大小 | 内容 |
|------|------|------|
| `fabric/build/libs/fabric-5.5.0.jar` | 357KB | `fabric.mod.json` + common 代码 + kotlinx-serialization ✅ |
| `forge/build/libs/forge-5.5.0.jar` | 357KB | `neoforge.mods.toml` + common 代码 + kotlinx-serialization ✅ |

---

## 最终编译状态

所有模块 `build` 任务全部通过（34 个任务，0 失败）：

| 检查项 | 状态 |
|--------|:---:|
| ktLint | ✅ |
| compileKotlin (common/fabric/forge) | ✅ |
| compileJava (common/fabric/forge) | ✅ |
| processResources | ✅ |
| jar | ✅ |
| shadowJar (common/fabric/forge) | ✅ |
| detect (detekt) | ✅ |

### 产出文件验证

```bash
# Fabric
fabric/build/libs/fabric-5.5.0.jar    357KB  ← 有效 mod (含 fabric.mod.json)

# NeoForge
forge/build/libs/forge-5.5.0.jar      357KB  ← 有效 mod (含 META-INF/neoforge.mods.toml)
forge/build/libs/forge-5.5.0-raw.jar    4KB  ← 仅 forge 代码，非最终产物
```
