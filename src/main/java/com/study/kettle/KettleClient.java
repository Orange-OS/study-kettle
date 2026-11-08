package com.study.kettle;

import java.util.Map;

import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;

/**
 * Kettle（Pentaho Data Integration）嵌入式调用工具类。
 *
 * <p>核心 API 只有两组四类：
 * <pre>
 *   转换(Transformation, .ktr)：TransMeta（元数据） + Trans（执行器）
 *   作业(Job,           .kjb)：JobMeta （元数据） + Job （执行器）
 * </pre>
 *
 * <p>使用前必须先调用 {@link #init()} 初始化 Kettle 运行环境（一个 JVM 进程只需一次），
 * 之后即可反复执行转换和作业。
 */
public final class KettleClient {

    /** Kettle 环境是否已初始化（一个 JVM 进程只能初始化一次） */
    private static volatile boolean initialized = false;

    private KettleClient() {
    }

    /**
     * 初始化 Kettle 运行环境：加载插件注册表（步骤/作业项/数据库类型等）。
     * 重复调用无副作用。
     */
    public static synchronized void init() throws KettleException {
        if (!initialized) {
            // 如果要连接 Kettle 资源库(Repository)，还需要在此处额外初始化
            KettleEnvironment.init();
            initialized = true;
        }
    }

    /**
     * 执行一个 .ktr 转换文件。
     *
     * @param ktrFile 转换文件路径
     * @param params  命名参数（必须先在转换里声明，见 Spoon“转换设置→命名参数”），可为 null
     * @return 执行完的 Trans 对象，可继续调用 getErrors()/getResult() 获取执行信息
     */
    public static Trans runTrans(String ktrFile, Map<String, String> params) throws KettleException {
        return runTrans(new TransMeta(ktrFile), params);
    }

    /**
     * 执行一个（内存中构建的）转换。
     */
    public static Trans runTrans(TransMeta transMeta, Map<String, String> params) throws KettleException {
        init();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                // 命名参数：优先级高于普通变量，执行前会把值写回变量空间
                transMeta.setParameterValue(e.getKey(), e.getValue());
            }
        }
        Trans trans = new Trans(transMeta);
        trans.execute(null);          // 开始执行（参数也可以用命令行参数方式传入）
        trans.waitUntilFinished();    // 阻塞等待执行结束
        return trans;
    }

    /**
     * 执行一个 .kjb 作业文件。
     *
     * @param kjbFile 作业文件路径
     * @param params  命名参数（必须先在作业里声明），可为 null
     * @return 作业级执行结果：成功/失败、读写行数、日志文本等
     */
    public static Result runJob(String kjbFile, Map<String, String> params) throws KettleException {
        return runJob(new JobMeta(kjbFile, null), params);
    }

    /**
     * 执行一个（内存中构建的）作业。
     */
    public static Result runJob(JobMeta jobMeta, Map<String, String> params) throws KettleException {
        init();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                jobMeta.setParameterValue(e.getKey(), e.getValue());
            }
        }
        // 第一个参数是 Kettle 资源库(Repository)，使用文件方式时传 null 即可
        Job job = new Job(null, jobMeta);
        job.start();                  // 作业在独立线程中启动
        job.waitUntilFinished();      // 阻塞等待作业执行结束
        return job.getResult();
    }
}
