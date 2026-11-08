package com.study.kettle;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.ObjectLocationSpecificationMethod;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.special.JobEntrySpecial;
import org.pentaho.di.job.entries.success.JobEntrySuccess;
import org.pentaho.di.job.entries.trans.JobEntryTrans;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowListener;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.datagrid.DataGridMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileField;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;

/**
 * Kettle Java API 调用演示，三个演示由浅入深：
 *
 * <ol>
 *   <li>演示一：纯代码构建“转换”并执行——不依赖任何 .ktr 文件，并把它保存为 .ktr（可用 Spoon 打开）</li>
 *   <li>演示二：从 .ktr 文件加载转换，传入命名参数后执行（最常见的业务集成方式）</li>
 *   <li>演示三：纯代码构建“作业”（START → 转换 → 成功）并执行</li>
 * </ol>
 *
 * <p>运行方式：{@code mvn compile exec:java}
 */
public class KettleDemo {

    /** 演示生成的 ktr/kjb 与输出文件目录（相对项目根目录） */
    static final String KETTLE_FILES_DIR = "kettle-files";
    static final String KTR_FILE = KETTLE_FILES_DIR + "/hello.ktr";
    static final String KJB_FILE = KETTLE_FILES_DIR + "/hello.kjb";
    /** 用户用 Spoon 设计好的现成文件 */
    static final String USER_KTR = "kettle/hello.ktr";
    static final String USER_KJB = "kettle/hello.kjb";

    public static void main(String[] args) throws Exception {
        // 任何 Kettle 调用之前，先初始化运行环境（进程内只需一次）
        KettleClient.init();

//        System.out.println("================ 演示一：纯代码生成转换并执行 ================");
//        demo1RunGeneratedTrans();
//
//        System.out.println();
//        System.out.println("================ 演示二：加载 .ktr 文件执行（传命名参数） ================");
//        demo2RunTransFromFile();
//
//        System.out.println();
//        System.out.println("================ 演示三：纯代码构建作业并执行 ================");
//        demo3RunGeneratedJob();

        System.out.println();
        System.out.println("================ 演示四：直接执行现成的 hello.ktr / hello.kjb ================");
        demo4RunUserFiles();

        System.out.println();
        System.out.println("全部演示执行完毕，到 output/ 和 kettle-files/ 目录查看产物。");
    }

    /**
     * 演示一：用代码“画”出一个转换（数据表 → 文本文件输出），保存为 .ktr 后直接在内存中执行。
     * 命名参数 DEMO_USER 不传值 → 使用声明时的默认值 "kettle"。
     */
    private static void demo1RunGeneratedTrans() throws Exception {
        TransMeta transMeta = buildHelloTrans();

        // 1) 保存成 .ktr 文件：之后可以用 Spoon 打开编辑，也可以交给 Pan/Kitchen 命令行执行
        new File(KETTLE_FILES_DIR).mkdirs();
        Files.write(Paths.get(KTR_FILE), transMeta.getXML().getBytes(StandardCharsets.UTF_8));
        System.out.println("转换已保存: " + new File(KTR_FILE).getAbsolutePath());

        // 2) 不落盘直接执行（prepare 之后才能拿到步骤实例，给它挂行监听器）
        Trans trans = new Trans(transMeta);
        trans.prepareExecution(null);

        // 行监听器：打印流经“写出文本文件”步骤的每一行数据（实时获取转换数据的常用手段）
        trans.getStepInterface("写出文本文件", 0).addRowListener(new RowListener() {
            @Override
            public void rowReadEvent(RowMetaInterface rowMeta, Object[] row) {
                // 本演示中无上一步之外的数据来源，不处理
            }

            @Override
            public void rowWrittenEvent(RowMetaInterface rowMeta, Object[] row) {
                try {
                    System.out.println("  流经的行: " + rowMeta.getString(row));
                } catch (Exception e) {
                    throw new RuntimeException("读取行数据失败", e);
                }
            }

            @Override
            public void errorRowWrittenEvent(RowMetaInterface rowMeta, Object[] row) {
                System.out.println("  出错的行: " + Arrays.toString(row));
            }
        });

        trans.startThreads();
        trans.waitUntilFinished();
        printTransResult(trans, "demo1");
    }

    /**
     * 演示二：模拟业务系统调用——从文件加载 .ktr，传入命名参数执行。
     * 参数 DEMO_USER=from-java 会替换输出文件名里的 ${DEMO_USER}。
     */
    private static void demo2RunTransFromFile() throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("DEMO_USER", "from-java");

        Trans trans = KettleClient.runTrans(KTR_FILE, params);
        printTransResult(trans, "demo2");
        System.out.println("  本次输出文件: output/hello-from-java.txt");
    }

    /**
     * 演示三：用代码构建作业：START → 运行转换(引用 hello.ktr) → 成功，保存为 .kjb 后执行。
     */
    private static void demo3RunGeneratedJob() throws Exception {
        JobMeta jobMeta = new JobMeta();
        jobMeta.setName("hello-kettle-job");

        // START 节点（每个作业的起点，Spoon 里那个绿色三角）
        JobEntryCopy start = new JobEntryCopy(new JobEntrySpecial("START", true, false));
        start.setDrawn(true);
        start.setLocation(100, 100);
        jobMeta.addJobEntry(start);

        // “转换”节点：引用演示一生成的 ktr 文件
        JobEntryTrans runTrans = new JobEntryTrans("运行hello转换");
        runTrans.setSpecificationMethod(ObjectLocationSpecificationMethod.FILENAME); // 按文件方式引用
        runTrans.setFileName(new File(KTR_FILE).getAbsolutePath());
        // 程序化构建时必须初始化参数数组（Spoon 加载 XML 时会自动分配，这里要手动给空数组）
        runTrans.parameters = new String[0];
        runTrans.parameterFieldNames = new String[0];
        runTrans.parameterValues = new String[0];
        JobEntryCopy transEntry = new JobEntryCopy(runTrans);
        transEntry.setDrawn(true);
        transEntry.setLocation(300, 100);
        jobMeta.addJobEntry(transEntry);

        // “成功”节点
        JobEntryCopy success = new JobEntryCopy(new JobEntrySuccess("成功", ""));
        success.setDrawn(true);
        success.setLocation(500, 100);
        jobMeta.addJobEntry(success);

        // 节点连线：START → 转换 → 成功
        jobMeta.addJobHop(new JobHopMeta(start, transEntry));
        jobMeta.addJobHop(new JobHopMeta(transEntry, success));

        // 保存为 .kjb 文件（可用 Spoon 打开查看画布）
        Files.write(Paths.get(KJB_FILE), jobMeta.getXML().getBytes(StandardCharsets.UTF_8));
        System.out.println("作业已保存: " + new File(KJB_FILE).getAbsolutePath());

        // 执行作业（不传参数，转换里的 DEMO_USER 使用默认值）
        Result result = KettleClient.runJob(jobMeta, null);
        printJobResult(result);
    }

    /**
     * 演示四：模拟最典型的业务集成场景——执行设计器里画好的现成文件。
     * kettle/hello.ktr：生成记录 → 写日志；kettle/hello.kjb：START → 写日志。
     */
    private static void demo4RunUserFiles() throws Exception {
        System.out.println("--- 执行 kettle/hello.ktr ---");
        Trans trans = KettleClient.runTrans(USER_KTR, null);
        printTransResult(trans, "demo4");

        System.out.println("--- 执行 kettle/hello.kjb ---");
        Result result = KettleClient.runJob(USER_KJB, null);
        printJobResult(result);
    }

    /**
     * 构建示例转换：数据表(DataGrid) → 文本文件输出(TextFileOutput)。
     */
    private static TransMeta buildHelloTrans() throws org.pentaho.di.core.parameters.DuplicateParamException {
        TransMeta transMeta = new TransMeta();
        transMeta.setName("hello-kettle-trans");
        transMeta.setDescription("由 Java API 动态生成的示例转换");

        // 声明命名参数（对应 Spoon: 转换设置 → 命名参数），带默认值
        transMeta.addParameterDefinition("DEMO_USER", "kettle", "演示命名参数：替换输出文件名");

        // ---- 步骤一：数据表(DataGrid)，造 3 行示例数据 ----
        DataGridMeta dataGrid = new DataGridMeta();
        dataGrid.setFieldName(new String[] { "id", "name", "score" });
        dataGrid.setFieldType(new String[] { "Integer", "String", "Number" });
        dataGrid.setFieldLength(new int[] { -1, -1, -1 });
        dataGrid.setFieldPrecision(new int[] { -1, -1, -1 });
        dataGrid.setFieldFormat(new String[] { "", "", "0.0" });
        dataGrid.setCurrency(new String[] { "", "", "" });
        dataGrid.setGroup(new String[] { "", "", "" });
        dataGrid.setDecimal(new String[] { "", "", "" });
        dataGrid.setFieldNullIf(new String[] { "", "", "" });
        dataGrid.setEmptyString(new boolean[] { false, false, false });
        List<List<String>> lines = new ArrayList<>();
        lines.add(Arrays.asList("1", "张三", "92.5"));
        lines.add(Arrays.asList("2", "李四", "88"));
        lines.add(Arrays.asList("3", "王五", "79.5"));
        dataGrid.setDataLines(lines);
        StepMeta dataGridStep = new StepMeta("DataGrid", "模拟数据", dataGrid);
        dataGridStep.setDraw(true);
        dataGridStep.setLocation(100, 100);
        transMeta.addStep(dataGridStep);

        // ---- 步骤二：文本文件输出(TextFileOutput) ----
        TextFileOutputMeta textOutput = new TextFileOutputMeta();
        textOutput.setFilename("output/hello-${DEMO_USER}");   // 文件名支持 ${变量}
        textOutput.setExtension("txt");
        textOutput.setSeparator(",");
        textOutput.setEncoding("UTF-8");
        textOutput.setHeaderEnabled(true);
        textOutput.setCreateParentFolder(true);
        // 输出字段留空 = 按传入顺序写出全部字段
        textOutput.setOutputFields(new TextFileField[0]);
        StepMeta outputStep = new StepMeta("TextFileOutput", "写出文本文件", textOutput);
        outputStep.setDraw(true);
        outputStep.setLocation(300, 100);
        transMeta.addStep(outputStep);

        // ---- 步骤连线：模拟数据 → 写出文本文件 ----
        transMeta.addTransHop(new TransHopMeta(dataGridStep, outputStep));
        return transMeta;
    }

    /** 打印转换执行结果摘要 */
    private static void printTransResult(Trans trans, String tag) {
        boolean ok = trans.getErrors() == 0 && trans.getResult().getResult();
        System.out.println("[" + tag + "] 转换执行" + (ok ? "成功" : "失败") + "！错误数=" + trans.getErrors());
    }

    /** 打印作业执行结果摘要 */
    private static void printJobResult(Result result) {
        System.out.println("作业执行" + (result.getResult() ? "成功" : "失败")
                + "：退出码=" + result.getExitStatus() + "，错误数=" + result.getNrErrors());
    }
}
