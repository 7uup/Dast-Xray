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

/**
 * RadManager —— 调用 rad 对 crawlergo 漏洞扫描结果进行补充扫描
 * 与 crawlergo 联动使用
 */
public class RadManager {

    private static final Logger log = LoggerFactory.getLogger(RadManager.class);

    private final ConcurrentMap<String, RadProcessInfo> running = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private static final Pattern DANGEROUS_OP_PATTERN = Pattern.compile(
            "(;|&&|\\|\\||`|\\$\\(|\\$\\{|>|<|(?<!^)\\|(?!$)|(?<!^)&(?!$))"
    );

    // 控制常量
    private static final long CHECK_INTERVAL_SEC = 10;
    private static final int STABLE_COUNT_REQUIRED = 3;
    private static final long IDLE_THRESHOLD_SEC = 30;

    /**
     * 启动 rad 扫描任务
     *
     * @param radPath rad 可执行文件路径
     * @param targetUrl 目标地址
     * @param proxyHostAndPort xray 代理端口（crawlergo 传入）
     */
    public RadProcessInfo startRad(String radPath, String targetUrl, String proxyHostAndPort)
            throws IOException {

        Objects.requireNonNull(radPath, "rad path is null");
        Objects.requireNonNull(targetUrl, "targetUrl is null");

        if (containsDangerousOperator(radPath) || containsDangerousOperator(targetUrl)) {
            throw new IllegalArgumentException("参数包含不安全字符");
        }

        Path exe = Paths.get(radPath);
        if (!Files.exists(exe) || !Files.isExecutable(exe)) {
            throw new IllegalArgumentException("rad 可执行文件不存在或不可执行: " + radPath);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(radPath);
        cmd.add("-uf");
        cmd.add(targetUrl);
        cmd.add("--http-proxy");
        cmd.add(proxyHostAndPort);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("启动 rad 失败", e);
        }

        String id = UUID.randomUUID().toString();
        Instant startTime = Instant.now();
        RadProcessInfo info = new RadProcessInfo(id, cmd, proc, startTime);
        running.put(id, info);

        ioExecutor.submit(() -> streamToLogger(proc.getInputStream(), "RAD-OUT[" + id + "]", info));
        ioExecutor.submit(() -> streamToLogger(proc.getErrorStream(), "RAD-ERR[" + id + "]", info));

        return info;
    }

    /**
     * 监控输出日志
     */
    private void streamToLogger(InputStream in, String tag, RadProcessInfo info) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                info.updateLastLog();
                log.info(tag + " " + line);
                String finalLine = line;
                CompletableFuture.runAsync(() -> LogSseController.sendLog(tag, finalLine));

                if (line.toLowerCase().contains("scan finished") || line.toLowerCase().contains("task finished")) {
                    log.info("⚠️ RAD 扫描任务完成标识检测到，开始稳定性检测...");
                    startMonitorTask(info);
                }
            }
        } catch (IOException e) {
            log.error("读取 RAD 输出错误", e);
        }
    }

    /**
     * 稳定性监控
     */
    private void startMonitorTask(RadProcessInfo info) {
        info.resetStableCount();
        scheduler.scheduleAtFixedRate(() -> {
            long delta = Duration.between(Instant.ofEpochMilli(info.getLastLogTime()), Instant.now()).getSeconds();

            if (delta >= IDLE_THRESHOLD_SEC) {
                int stable = info.incrementStableCount();
                if (stable >= STABLE_COUNT_REQUIRED) {
                    stopRad(info.getId());
                    log.info("✅ RAD [{}] 扫描完成并已自动结束", info.getId());
                }
            } else {
                info.resetStableCount();
            }
        }, CHECK_INTERVAL_SEC, CHECK_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /**
     * 停止 rad 进程
     */
    public boolean stopRad(String id) {
        RadProcessInfo info = running.remove(id);
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
        log.info("🛑 已停止 RAD [{}]", id);
        return true;
    }

    /**
     * 用于联动 crawlergo 的触发函数
     */


    private static boolean containsDangerousOperator(String s) {
        if (s == null || s.isEmpty()) return false;
        return DANGEROUS_OP_PATTERN.matcher(s).find();
    }

    /**
     * rad 进程信息
     */
    public static class RadProcessInfo {
        private final String id;
        private final List<String> command;
        private final Process process;
        private final Instant startTime;
        private volatile long lastLogTime;
        private final AtomicInteger stableCount = new AtomicInteger(0);

        public RadProcessInfo(String id, List<String> command, Process process, Instant startTime) {
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
     * 停止所有任务
     */
    public void shutdown() {
        for (String id : running.keySet()) {
            stopRad(id);
        }
        running.clear();
        ioExecutor.shutdownNow();
        scheduler.shutdownNow();
    }
}
