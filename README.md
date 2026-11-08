# study-kettle — Java 调用 Kettle (Pentaho Data Integration) 示例项目

用 Java 代码嵌入方式调用 [Kettle/PDI 9.4](https://github.com/pentaho/pentaho-kettle)：
执行转换（.ktr）、执行作业（.kjb）、纯代码动态构建转换和作业、传递命名参数、监听流经的数据行。

- 环境要求：**JDK 8**、Maven 3.x（PDI 9.4 不支持 JDK 11+，9.3/9.4 官方仅保证 Java 8）
- 运行入口：`src/main/java/com/study/kettle/KettleDemo.java`
- 工具类：`src/main/java/com/study/kettle/KettleClient.java`

## 快速开始

```cmd
:: 一次性准备：把 PDI 发行包里的核心 jar 安装到本地 Maven 仓库
:: (jar 已在 downloads\extracted\ 中，若没有，从 pdi-ce-9.4.0.0-343.zip 的 data-integration\lib\ 中复制)
scripts\install-kettle-jars.cmd

:: 编译并运行演示（四个演示依次执行）
mvn compile exec:java
```

## 四个演示

| 演示 | 内容 | 关键 API |
|---|---|---|
| 一 | 纯代码"画"转换（数据表→文本文件），保存为 .ktr 并执行 | `TransMeta` / `DataGridMeta` / `TextFileOutputMeta` / `RowListener` |
| 二 | 从 .ktr 文件加载，传入命名参数执行（最常用的业务集成方式） | `new TransMeta(文件)` / `setParameterValue` |
| 三 | 纯代码"画"作业（START→转换→成功），保存为 .kjb 并执行 | `JobMeta` / `JobEntrySpecial` / `JobEntryTrans` / `JobEntrySuccess` / `JobHopMeta` |
| 四 | 直接执行现成的 `kettle/hello.ktr` 与 `kettle/hello.kjb` | `KettleClient.runTrans / runJob` |

运行产物：
- `kettle-files/hello.ktr`、`kettle-files/hello.kjb` —— 代码生成的文件，可用 Spoon 打开对照画布
- `output/hello-kettle.txt`、`output/hello-from-java.txt` —— 转换写出的数据文件

## 核心 API 速查

Kettle 嵌入式调用只有两组四个核心类：

```
转换 (.ktr)   TransMeta 元数据  →  Trans 执行器
作业  (.kjb)  JobMeta   元数据  →  Job  执行器
```

```java
// 0. 初始化（每个 JVM 进程一次）
KettleEnvironment.init();

// 1. 执行转换文件
TransMeta transMeta = new TransMeta("path/to/a.ktr");
transMeta.setParameterValue("PARAM_NAME", "value");   // 命名参数（须已在转换中声明）
Trans trans = new Trans(transMeta);
trans.execute(null);
trans.waitUntilFinished();
Result result = trans.getResult();                    // 成败、读写行数、错误数

// 2. 执行作业文件
JobMeta jobMeta = new JobMeta("path/to/b.kjb", null); // 第二个参数是资源库，文件方式传 null
Job job = new Job(null, jobMeta);
job.start();                                          // 作业在独立线程启动
job.waitUntilFinished();
Result jobResult = job.getResult();

// 3. 实时监听流经某步骤的数据行
trans.prepareExecution(null);
trans.getStepInterface("步骤名", 0).addRowListener(new RowListener() { ... });
trans.startThreads();
trans.waitUntilFinished();
```

### 常见的坑（本项目踩过并已在代码中规避）

1. **`KettleEnvironment.init()` 只能调用一次**，重复调用会报错，用单例/标志位保护。
2. **命名参数必须先声明**（Spoon 中"转换设置→命名参数"，或 `addParameterDefinition`），否则 `setParameterValue` 无效。
3. **程序化构建 `JobEntryTrans` 时**，字段 `specificationMethod` 与参数数组 `parameters`/`parameterFieldNames`/`parameterValues` 不会被初始化（Spoon 走 XML 加载路径才有默认值），必须手动赋值，否则执行时 NPE：
   ```java
   jobEntryTrans.setSpecificationMethod(ObjectLocationSpecificationMethod.FILENAME);
   jobEntryTrans.parameters = new String[0];
   jobEntryTrans.parameterFieldNames = new String[0];
   jobEntryTrans.parameterValues = new String[0];
   ```
4. **程序化构建 `DataGridMeta` 时**要设置全部属性数组（`fieldName`/`fieldType`/`fieldLength`/`fieldPrecision`/`fieldFormat`/`currency`/`decimal`/`group`/`fieldNullIf`/`setEmptyString`），漏任何一个在 `getXML()`/`getFields()` 时 NPE。
5. `TextFileOutputMeta.setOutputFields(new TextFileField[0])` 留空 = 写出全部传入字段；字段类名是 `TextFileField`（不是 TextFileOutputField）。
6. `RowListener` 在 `org.pentaho.di.trans.step` 包（不在 core.row）。
7. `Result` 的行数方法带 `Nr` 前缀：`getNrLinesRead()`、`getNrLinesWritten()`；但注意文本文件输出步骤不回写这些计数器，"流经多少行"以 `RowListener` 或步骤日志 (W=) 为准。
8. `mvn exec:java` 结束时可能打印 commons-vfs `CloseAction` 的 ClassNotFound 告警——这是 exec 插件隔离类加载器在关闭 VFS 时的噪音，不影响执行结果；介意的话用 `dependency:copy-dependencies` + `java -cp` 方式启动。

## 依赖说明（重要）

Pentaho 官方 Maven 仓库（`repo.pentaho.com`、原 `nexus.pentaho.org`）**已改为认证访问**，社区镜像（如 `cn.benma666`）缺父 POM 无法解析。因此本项目采用**本地安装发行包 jar** 的方式：

```
pdi-ce-9.4.0.0-343.zip
└── data-integration/lib/
    ├── kettle-core-9.4.0.0-343.jar          → org.pentaho.di:kettle-core
    ├── kettle-engine-9.4.0.0-343.jar        → org.pentaho.di:kettle-engine
    ├── metastore-9.4.0.0-343.jar            → pentaho:metastore
    ├── pdi-engine-api-9.4.0.0-343.jar       → org.pentaho:pdi-engine-api
    ├── pentaho-registry-9.4.0.0-343.jar     → org.pentaho:pentaho-registry
    └── pentaho-encryption-support-9.4.0.0-343.jar → org.pentaho:pentaho-encryption-support
```

第三方依赖（log4j2、commons-*、guava、owasp-encoder、slf4j 等）均可在 Maven 中央仓库/阿里云镜像正常获取，已在 pom.xml 中按 PDI 9.4 lib 的版本对齐声明。

`src/main/resources/kettle-password-encoder-plugins.xml` 来自发行包的 `data-integration/classes/` 目录，缺失会导致 `KettleEnvironment.init()` 报 "Unable to find plugin with ID 'Kettle'"。

PDI 发行包下载地址（社区存档镜像）：
https://github.com/ambientelivre/legacy-pentaho-ce/releases/download/pdi-ce-9.4.0.0-343/pdi-ce-9.4.0.0-343.zip

## 目录结构

```
study-kettle/
├── pom.xml                     Maven 配置（依赖版本与 PDI 9.4 对齐）
├── src/main/java/com/study/kettle/
│   ├── KettleClient.java       嵌入式调用工具类（init/runTrans/runJob）
│   └── KettleDemo.java         四个演示的入口
├── src/main/resources/
│   ├── kettle-password-encoder-plugins.xml   Kettle 必需的插件注册文件
│   └── log4j2.properties       日志配置
├── kettle/                     你用 Spoon 画好的现成文件（演示四执行）
├── kettle-files/               [运行生成] 代码生成的 ktr/kjb
├── output/                     [运行生成] 转换写出的数据文件
├── scripts/
│   └── install-kettle-jars.cmd 把发行包 jar 安装到本地 Maven 仓库
├── downloads/                  PDI 发行包 zip 与提取出的 jar（不入库）
└── pdi-ce-9.4/                 [解压生成] 完整 PDI，含 Spoon 图形界面
    └── data-integration/Spoon.bat
```
