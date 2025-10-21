//package com.dast.back.Server;
//
//import com.dast.back.Bean.TaskReport;
//import com.dast.back.Controller.LogSseController;
//import com.dast.back.mapper.ReportMapper;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.io.*;
//import java.net.ServerSocket;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.*;
//import java.util.*;
//import java.util.concurrent.*;
//import java.util.regex.Pattern;
//
//
//public class XrayManager {
//
//
//    private ReportMapper reportMapper;
//
//    private final ConcurrentMap<Integer, Process> runningProcesses = new ConcurrentHashMap<>();
//    private static final Logger log = LoggerFactory.getLogger(XrayManager.class);
//    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._\\-]+$");
//    private final Path resultDir;
//
//    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
//        Thread t = new Thread(r);
//        t.setDaemon(true);
//        return t;
//    });
//
//    public XrayManager(Path resultDir, ReportMapper reportMapper) throws IOException {
//        this.resultDir = resultDir;
//        this.reportMapper=reportMapper;
//        if (!Files.exists(resultDir)) {
//            Files.createDirectories(resultDir);
//        }
//    }
//
//    /**
//     * @param xrayPath 可执行文件路径或命令（建议使用绝对路径）
//     * @param format "html" 或 "json"
//     * @param output 输出文件名（仅文件名，不含路径）
//     * @return 分配到的监听端口（int）
//     * @throws IOException 若启动失败或参数不合法
//     */
//    public int startXray(String xrayPath, String format, String output,Long id,String name,String url,Integer source,String uuid) throws IOException {
//        // 1. 参数校验
//        Objects.requireNonNull(xrayPath, "xrayPath is null");
//        Objects.requireNonNull(format, "format is null");
//        Objects.requireNonNull(output, "output is null");
//
//        format = format.trim().toLowerCase(Locale.ROOT);
//        if (!"html".equals(format) && !"json".equals(format)) {
//            throw new IllegalArgumentException("format must be 'html' or 'json'");
//        }
//
//        if (!SAFE_FILENAME.matcher(output).matches()) {
//            throw new IllegalArgumentException("output filename contains unsafe characters");
//        }
//
//        Path xrayExecutable = Paths.get(xrayPath);
//        if (xrayExecutable.isAbsolute()) {
//            if (!Files.exists(xrayExecutable) || !Files.isRegularFile(xrayExecutable) || !Files.isExecutable(xrayExecutable)) {
//                throw new IllegalArgumentException("xrayPath not found or not executable: " + xrayPath);
//            }
//        }
//
//        int port = allocateEphemeralPort();
//
//        String outFileName = output;
//        Path outPath = resultDir.resolve(outFileName);
//
//
//
//        List<String> cmd = new ArrayList<>();
//        cmd.add(xrayPath);
//        cmd.add("webscan");
//        cmd.add("--listen");
//        cmd.add("127.0.0.1:" + port);
//        if ("html".equals(format)) {
//            cmd.add("--html-output");
//            cmd.add(outPath.toString());
//        } else {
//            cmd.add("--json-output");
//            cmd.add(outFileName.toString());
//        }
//
//        Path xrayDir = xrayExecutable.getParent();
//
//
//        ProcessBuilder pb = new ProcessBuilder(cmd);
//        pb.directory(xrayDir.toFile());
//        pb.redirectErrorStream(true);
//
//
//        final Process proc;
//        try {
//            proc = pb.start();
//        } catch (IOException e) {
//            throw new IOException("failed to start xray process", e);
//        }
//
//        TaskReport report = new TaskReport();
//        report.setTask_id(id);
//        report.setName(name);
//        report.setUrl(url);
//        report.setGroupId(uuid);
//        report.setReport_path(xrayDir+"/result/"+output.toString());
//        report.setStatus(0);
//        report.setSource(source);
//        reportMapper.insertReport(report);
//
//        ioExecutor.submit(() -> streamToLogger(proc.getInputStream(), "XRAY-OUT[" + port + "]",uuid,id,port));
//        ioExecutor.submit(() -> streamToLogger(proc.getErrorStream(), "XRAY-ERR[" + port + "]",uuid,id,port));
//
//        runningProcesses.put(port, proc);
//
//        return port;
//    }
//
//    /**
//     * 停止指定端口对应的 xray 进程
//     * @param port 监听端口
//     * @return true 如果成功停止或已不存在
//     */
//    public boolean stopXray(int port) {
//        Process p = runningProcesses.remove(port);
//        if (p == null) return true;
//        p.destroy();
//        try {
//            if (!p.waitFor(5, TimeUnit.SECONDS)) {
//                p.destroyForcibly();
//            }
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//            p.destroyForcibly();
//        }
//        return true;
//    }
//
//
//    private volatile long lastLogTime = System.currentTimeMillis();
//
//    public void streamToLogger(InputStream in, String tag, String uuid, Long taskId, int port) {
//        ioExecutor.submit(() -> {
//            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
//                String line;
//                while ((line = reader.readLine()) != null) {
//                    final String logLine = line;
//                    lastLogTime = System.currentTimeMillis(); // 更新最后日志时间
//
//                    log.info(tag + " " + logLine);
//                    CompletableFuture.runAsync(() -> LogSseController.sendLog(tag, logLine));
//
//                    // 当检测到扫描完成标志时，启动监控
//                    if (logLine.contains("All pending requests have been scanned")) {
//                        log.info("⚠️ 检测到扫描完成信号，启动 30 秒稳定性监控...");
//
//                        ioExecutor.submit(() -> monitorScanCompletion(uuid, taskId, port));
//                    }
//                }
//            } catch (IOException e) {
//                log.error("❌ 日志读取异常", e);
//            }
//        });
//    }
//
//    private void monitorScanCompletion(String uuid, Long taskId, int port) {
//        try {
//            // 等待 30 秒确认是否真的没有新日志
//            Thread.sleep(30000);
//            if (System.currentTimeMillis() - lastLogTime >= 30000) {
//                log.info("✅ 日志静默超过 30 秒，确认扫描结束，停止 Xray (" + port + ")");
//                reportMapper.updateStatus(uuid, 1);
//                reportMapper.updateTaskStatus(taskId, 2);
//                stopXray(port);
//            } else {
//                log.info("📡 检测到新的日志输出，取消自动停止...");
//            }
//        } catch (InterruptedException e) {
//            Thread.currentThread().interrupt();
//        }
//    }
//
//
//    private int extractPort(String tag) {
//        try {
//            int start = tag.indexOf('[');
//            int end = tag.indexOf(']');
//            if (start >= 0 && end > start) {
//                return Integer.parseInt(tag.substring(start + 1, end));
//            }
//        } catch (Exception ignored) {}
//        return -1;
//    }
//
//
//
//    /**
//     * 分配一个临时可用端口（通过绑定 ServerSocket 0）
//     */
//    private int allocateEphemeralPort() throws IOException {
//        try (ServerSocket ss = new ServerSocket(0)) {
//            ss.setReuseAddress(true);
//            return ss.getLocalPort();
//        }
//    }
//
//    /**
//     * 关闭管理器（清理线程池与运行进程）
//     */
//    public void shutdown() {
//        for (Map.Entry<Integer, Process> e : runningProcesses.entrySet()) {
//            Process p = e.getValue();
//            try {
//                p.destroy();
//                if (!p.waitFor(3, TimeUnit.SECONDS)) {
//                    p.destroyForcibly();
//                }
//            } catch (Exception ignored) {}
//        }
//        runningProcesses.clear();
//        ioExecutor.shutdownNow();
//    }
//}
//



package com.dast.back.Server;

import com.dast.back.Bean.TaskReport;
import com.dast.back.Controller.LogSseController;
import com.dast.back.mapper.ReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XrayManager {

    private final ReportMapper reportMapper;
    private final ConcurrentMap<Integer, Process> runningProcesses = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, XrayProcessInfo> processInfos = new ConcurrentHashMap<>();


    private static final Logger log = LoggerFactory.getLogger(XrayManager.class);
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9._\\-]+$");
    private static final Pattern XRAY_STATUS_PATTERN = Pattern.compile("pending:\\s*(\\d+)");

    private final Path resultDir;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // 可配置参数
    private final int checkIntervalSeconds = 30;      // 每30秒检测一次
    private final int idleTimeoutSeconds = 60;        // 60秒内无新日志视为静默
    private final int consecutiveRequired = 3;        // 连续3次静默才判定完成

    public XrayManager(Path resultDir, ReportMapper reportMapper) throws IOException {
        this.resultDir = resultDir;
        this.reportMapper = reportMapper;
        if (!Files.exists(resultDir)) {
            Files.createDirectories(resultDir);
        }
    }

    public int startXray(String xrayPath, String format, String output,
                         Long id, String name, String url, Integer source, String uuid) throws IOException {

        Objects.requireNonNull(xrayPath);
        Objects.requireNonNull(format);
        Objects.requireNonNull(output);

        format = format.trim().toLowerCase(Locale.ROOT);
        if (!"html".equals(format) && !"json".equals(format)) {
            throw new IllegalArgumentException("format must be 'html' or 'json'");
        }

        if (!SAFE_FILENAME.matcher(output).matches()) {
            throw new IllegalArgumentException("output filename contains unsafe characters");
        }

        Path xrayExecutable = Paths.get(xrayPath);
        if (xrayExecutable.isAbsolute() &&
                (!Files.exists(xrayExecutable) || !Files.isExecutable(xrayExecutable))) {
            throw new IllegalArgumentException("xrayPath not found or not executable: " + xrayPath);
        }

        int port = allocateEphemeralPort();
        Path outPath = resultDir.resolve(output);

        List<String> cmd = new ArrayList<>();
        cmd.add(xrayPath);
        cmd.add("webscan");
        cmd.add("--listen");
        cmd.add("127.0.0.1:" + port);
        if ("html".equals(format)) {
            cmd.add("--html-output");
            cmd.add(outPath.toString());
        } else {
            cmd.add("--json-output");
            cmd.add(outPath.toString());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(xrayExecutable.getParent().toFile());
        pb.redirectErrorStream(true);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to start xray", e);
        }

        // 插入报告记录
        TaskReport report = new TaskReport();
        report.setTask_id(id);
        report.setName(name);
        report.setUrl(url);
        report.setGroupId(uuid);
        report.setReport_path(resultDir + "/" + output);
        report.setStatus(0);
        report.setSource(source);
        reportMapper.insertReport(report);

        // 启动日志读取
        XrayProcessInfo info = new XrayProcessInfo(port, uuid, id, proc);
        processInfos.put(port, info);
        runningProcesses.put(port, proc);

        ioExecutor.submit(() -> streamToLogger(proc.getInputStream(), "XRAY-OUT[" + port + "]", info));
        return port;
    }

    private void streamToLogger(InputStream in, String tag, XrayProcessInfo info) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String logLine = line;
                log.info(tag + " " + logLine);
                LogSseController.sendLog(tag, logLine);

                info.lastLogTime = Instant.now();
                Matcher m = XRAY_STATUS_PATTERN.matcher(logLine);
                if (m.find()) {
                    info.lastPending = Integer.parseInt(m.group(1));
                }

                // 启动或重置监控任务
                if (logLine.contains("All pending requests have been scanned")) {
                    startOrResetMonitor(info);
                }
            }
        } catch (IOException e) {
            log.error("❌ 日志读取异常 [" + info.port + "]", e);
        }
    }

    /** 启动或重置监控任务 */
    private void startOrResetMonitor(XrayProcessInfo info) {
        if (info.monitorTask != null && !info.monitorTask.isCancelled()) {
            info.monitorTask.cancel(false);
            log.info("🔁 重置 Xray (" + info.port + ") 监控任务");
        }
        info.monitorTask = scheduler.scheduleAtFixedRate(() -> checkScanCompletion(info),
                checkIntervalSeconds, checkIntervalSeconds, TimeUnit.SECONDS);
    }

    /** 检查是否满足停止条件 */
    private void checkScanCompletion(XrayProcessInfo info) {
        long idleDuration = Duration.between(info.lastLogTime, Instant.now()).getSeconds();
        boolean idle = idleDuration >= idleTimeoutSeconds;
        boolean pendingZero = info.lastPending == 0;

        if (idle && pendingZero) {
            info.silentCount++;
            log.info("📡 [Port {}] 静默检测 {}/{} (pending=0)", info.port, info.silentCount, consecutiveRequired);
        } else {
            info.silentCount = 0;
        }

        if (info.silentCount >= consecutiveRequired) {
            log.info("✅ Xray [{}] 连续 {} 次静默，确认扫描完成，准备停止", info.port, consecutiveRequired);
            stopAndFinalize(info);
        }
    }

    private void stopAndFinalize(XrayProcessInfo info) {
        try {
            if (info.monitorTask != null) info.monitorTask.cancel(true);
            stopXray(info.port);
            reportMapper.updateStatus(info.uuid, 1);
            reportMapper.updateTaskStatus(info.taskId, 2);
            log.info("🛑 已停止 Xray [{}]", info.port);
        } catch (Exception e) {
            log.error("⚠️ 停止 Xray 进程出错 [" + info.port + "]", e);
        }
    }

    public boolean stopXray(int port) {
        Process p = runningProcesses.remove(port);
        processInfos.remove(port);
        if (p == null) return true;
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        return true;
    }

    private int allocateEphemeralPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }

    public void shutdown() {
        // 显式写出类型 Map.Entry<Integer, Process>
        for (Map.Entry<Integer, Process> e : runningProcesses.entrySet()) {
            try {
                e.getValue().destroy();
                e.getValue().waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }

        runningProcesses.clear();
        processInfos.clear();
        ioExecutor.shutdownNow();
        scheduler.shutdownNow();
    }


    /** 每个 Xray 实例独立状态 */
    private static class XrayProcessInfo {
        final int port;
        final String uuid;
        final Long taskId;
        final Process process;
        volatile Instant lastLogTime = Instant.now();
        volatile int lastPending = -1;
        volatile int silentCount = 0;
        volatile ScheduledFuture<?> monitorTask;

        XrayProcessInfo(int port, String uuid, Long taskId, Process process) {
            this.port = port;
            this.uuid = uuid;
            this.taskId = taskId;
            this.process = process;
        }
    }
}

