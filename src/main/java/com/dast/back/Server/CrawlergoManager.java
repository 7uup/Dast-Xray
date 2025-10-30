package com.dast.back.Server;

import com.dast.back.Controller.LogSseController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class CrawlergoManager {

    private final ConcurrentMap<String, CrawlergoProcessInfo> running = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(CrawlergoManager.class);

    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private String newurls;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private static final Pattern DANGEROUS_OP_PATTERN = Pattern.compile(
            "(;|&&|\\|\\||`|\\$\\(|\\$\\{|>|<|(?<!^)\\|(?!$)|(?<!^)&(?!$))"
    );

    // --- 参数配置 ---
    private static final long CHECK_INTERVAL_SEC = 10; // 每隔 10 秒检测一次
    private static final int STABLE_COUNT_REQUIRED = 3; // 连续 3 次静默即认为完成
    private static final long IDLE_THRESHOLD_SEC = 30;  // 单次静默阈值
    private static final long RAD_TRIGGER_THRESHOLD_SEC = 60; // <60s 自动触发RAD

    /**
     * 启动 crawlergo
     */
    public CrawlergoProcessInfo startCrawlergo(String exePath, String chromePath, String targetUrl,
                                               String proxyHostAndPort, List<String> extraArgs) throws IOException {

        Objects.requireNonNull(exePath, "crawlergo path is null");
        Objects.requireNonNull(chromePath, "chrome path is null");
        Objects.requireNonNull(targetUrl, "targetUrl is null");

        if (containsDangerousOperator(exePath) || containsDangerousOperator(targetUrl)
                || containsDangerousOperator(chromePath)) {
            throw new IllegalArgumentException("参数包含不安全字符");
        }

        if (proxyHostAndPort == null || proxyHostAndPort.isEmpty()) {
            throw new IllegalArgumentException("xray代理输入为空");
        }

        Path exe = Paths.get(exePath);
        if (!Files.exists(exe) || !Files.isExecutable(exe)) {
            throw new IllegalArgumentException("crawlergo 可执行文件不存在或不可执行: " + exePath);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(exePath);
        cmd.add("-c");
        cmd.add(chromePath);
        cmd.add("-t");
        cmd.add("10");
        cmd.add("--push-to-proxy");
        cmd.add(proxyHostAndPort);
        cmd.add("-f");
        cmd.add("small");
        cmd.add("--fuzz-path");
        cmd.add(targetUrl);

        if (extraArgs != null && !extraArgs.isEmpty()) cmd.addAll(extraArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("启动 crawlergo 失败", e);
        }

        String id = UUID.randomUUID().toString();
        Instant startTime = Instant.now();
        CrawlergoProcessInfo info = new CrawlergoProcessInfo(id, cmd, proc, startTime);
        running.put(id, info);

        ioExecutor.submit(() -> streamToLogger(proc.getInputStream(), "CRAWLERGO-OUT[" + id + "]", info));
        ioExecutor.submit(() -> streamToLogger(proc.getErrorStream(), "CRAWLERGO-ERR[" + id + "]", info));

        return info;
    }

    public CrawlergoProcessInfo startCrawlergo2(String exePath, String chromePath, List<String> targetUrl,
                                               String proxyHostAndPort, List<String> extraArgs) throws IOException {

        Objects.requireNonNull(exePath, "crawlergo path is null");
        Objects.requireNonNull(chromePath, "chrome path is null");
        Objects.requireNonNull(targetUrl, "targetUrl is null");


        if (containsDangerousOperator(exePath)
                || containsDangerousOperator(chromePath)) {
            throw new IllegalArgumentException("参数包含不安全字符");
        }

        if (proxyHostAndPort == null || proxyHostAndPort.isEmpty()) {
            throw new IllegalArgumentException("xray代理输入为空");
        }

        Path exe = Paths.get(exePath);
        if (!Files.exists(exe) || !Files.isExecutable(exe)) {
            throw new IllegalArgumentException("crawlergo 可执行文件不存在或不可执行: " + exePath);
        }
        StringBuilder sb = new StringBuilder();
        if (targetUrl.size() == 1) {
            newurls = targetUrl.get(0);
        } else {
            for (String urls : targetUrl) {
                sb.append(urls).append(",");
            }
            // 去掉最后一个逗号
            newurls = sb.substring(0, sb.length() - 1);
        }


        List<String> cmd = new ArrayList<>();
        cmd.add(exePath);
        cmd.add("-c");
        cmd.add(chromePath);
        cmd.add("-t");
        cmd.add("10");
        cmd.add("--push-to-proxy");
        cmd.add(proxyHostAndPort);
//        cmd.add("--request-proxy");
//        cmd.add(proxyHostAndPort);
        cmd.add("-f");
        cmd.add("small");
        cmd.add("--fuzz-path");
        cmd.add("-u");
        cmd.add(newurls);

        if (extraArgs != null && !extraArgs.isEmpty()) cmd.addAll(extraArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("启动 crawlergo 失败", e);
        }

        String id = UUID.randomUUID().toString();
        Instant startTime = Instant.now();
        CrawlergoProcessInfo info = new CrawlergoProcessInfo(id, cmd, proc, startTime);
        running.put(id, info);

        ioExecutor.submit(() -> streamToLogger(proc.getInputStream(), "CRAWLERGO-OUT[" + id + "]", info));
        ioExecutor.submit(() -> streamToLogger(proc.getErrorStream(), "CRAWLERGO-ERR[" + id + "]", info));

        return info;
    }




    /**
     * 日志流监控
     */
    private void streamToLogger(InputStream in, String tag, CrawlergoProcessInfo info) {
        ioExecutor.submit(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    info.updateLastLog();
                    log.info(tag + " " + line);
                    final String logLine = line;
                    CompletableFuture.runAsync(() -> LogSseController.sendLog(tag, logLine));

                    if (logLine.toLowerCase().contains("task finished")) {
                        log.info("⚠️ 检测到任务完成信号，启动稳定性监控...");
                        startMonitorTask(info);
                    }
                }
            } catch (IOException e) {
                log.error("读取 crawlergo 日志出错 [" + info.getId() + "]", e);
            }
        });
    }

    /**
     * 启动监控任务
     */
    private void startMonitorTask(CrawlergoProcessInfo info) {
        info.resetStableCount();

        scheduler.scheduleAtFixedRate(() -> {
            long deltaSec = Duration.between(Instant.ofEpochMilli(info.getLastLogTime()), Instant.now()).getSeconds();

            if (deltaSec >= IDLE_THRESHOLD_SEC) {
                int stable = info.incrementStableCount();
//                log.info("📊 进程 [{}] 已静默 {} 秒，连续稳定次数 {}", info.getId(), deltaSec, stable);
                if (stable >= STABLE_COUNT_REQUIRED) {
//                    log.info("✅ 确认 crawlergo [{}] 任务已完成，准备停止", info.getId());
//                    stopCrawlergo(info.getId());
                    long runtime = Duration.between(info.getStartTime(), Instant.now()).getSeconds();
                    log.info("Crawlergo [{}] 总运行时长 {} 秒", info.getId(), runtime);
                }
            } else {
                info.resetStableCount(); // 有日志刷新则重置稳定计数
            }
        }, CHECK_INTERVAL_SEC, CHECK_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /**
     * 模拟启动 RAD
     */
//    private void startRadScan(String processId) {
//        // TODO: 替换为你自己的逻辑
//        log.info("🚀 [RAD] 对进程 {} 启动补充扫描", processId);
//    }

    /**
     * 停止 crawlergo
     */
    public boolean stopCrawlergo(String id) {
        CrawlergoProcessInfo info = running.remove(id);
        if (info == null) return true;

        Process p = info.getProcess();
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        log.info("🛑 已关闭 Crawlergo [{}]", id);
        return true;
    }

    private static boolean containsDangerousOperator(String s) {
        if (s == null || s.isEmpty()) return false;
        return DANGEROUS_OP_PATTERN.matcher(s).find();
    }

    /**
     * 进程信息
     */
    public static class CrawlergoProcessInfo {
        private final String id;
        private final List<String> command;
        private final Process process;
        private final Instant startTime;
        private volatile long lastLogTime;
        private final AtomicInteger stableCount = new AtomicInteger(0);

        public CrawlergoProcessInfo(String id, List<String> command, Process process, Instant startTime) {
            this.id = id;
            this.command = Collections.unmodifiableList(new ArrayList<>(command));
            this.process = process;
            this.startTime = startTime;
            this.lastLogTime = System.currentTimeMillis();
        }

        public String getId() { return id; }
        public List<String> getCommand() { return command; }
        public Process getProcess() { return process; }
        public Instant getStartTime() { return startTime; }

        public long getLastLogTime() { return lastLogTime; }
        public void updateLastLog() { this.lastLogTime = System.currentTimeMillis(); }

        public int incrementStableCount() { return stableCount.incrementAndGet(); }
        public void resetStableCount() { stableCount.set(0); }
    }

    /**
     * 停止所有 crawlergo 实例
     */
    public void shutdown() {
        for (Map.Entry<String, CrawlergoProcessInfo> e : running.entrySet()) {
            stopCrawlergo(e.getKey());
        }
        running.clear();
        ioExecutor.shutdownNow();
        scheduler.shutdownNow();
    }
}
