package com.fongmi.chaquo;

import com.github.catvod.utils.Path;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 桌面版 Python 桥：以系统 Python 子进程承载 spider 脚本，stdin/stdout JSON 行协议
 * （FongMi 移植；依赖本机 python 与脚本所需的 pip 包如 requests）。
 */
final class PythonBridge {

    private static final Gson GSON = new Gson();

    private Process process;
    private BufferedReader reader;
    private OutputStream writer;

    private static final class Loader {
        static volatile PythonBridge INSTANCE = new PythonBridge();
    }

    static PythonBridge get() {
        return Loader.INSTANCE;
    }

    private synchronized void start() {
        if (process != null && process.isAlive()) return;
        try {
            File runner = extractRunner();
            String python = resolvePython();
            if (python == null) {
                throw new IllegalStateException("未找到可用的 python（请安装 Python 并加入 PATH，或设置 PYTHON 环境变量）");
            }
            ProcessBuilder builder = new ProcessBuilder(python, "-X", "utf8", runner.getAbsolutePath());
            // Windows 上 python 的 stdout/stdin 默认跟随系统 ANSI 代码页（中文系统 gbk、部分环境 ascii），
            // JSON 行含中文即 UnicodeEncodeError——环境变量与 -X utf8 双保险强制 UTF-8
            builder.environment().put("PYTHONUTF8", "1");
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.redirectErrorStream(false);
            process = builder.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            writer = process.getOutputStream();
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
        } catch (IOException e) {
            throw new IllegalStateException("Python 子进程启动失败（需要本机 python + 脚本依赖如 requests）", e);
        }
    }

    private static String resolvePython() {
        for (String candidate : new String[]{System.getenv("PYTHON"), "python", "python3"}) {
            if (candidate == null || candidate.isBlank()) continue;
            if (isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private static boolean isExecutable(String command) {
        try {
            Process probe = new ProcessBuilder(command, "--version").start();
            boolean done = probe.waitFor(5, TimeUnit.SECONDS);
            probe.destroyForcibly();
            return done && probe.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static File extractRunner() throws IOException {
        File runner = new File(Path.py(), "runner.py");
        try (InputStream is = PythonBridge.class.getResourceAsStream("/py/runner.py")) {
            if (is == null) throw new IOException("runner.py resource missing");
            Files.copy(is, runner.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return runner;
    }

    synchronized Object load(String api, String cache) {
        return request(request("load", api, null, List.of(api, cache)));
    }

    synchronized Object call(String api, String method, Object... args) {
        return request(request("call", api, method, List.of(args)));
    }

    private static JsonObject request(String op, String api, String method, List<Object> args) {
        JsonObject request = new JsonObject();
        request.addProperty("op", op);
        request.addProperty("api", api);
        if (method != null) request.addProperty("method", method);
        request.add("args", GSON.toJsonTree(args));
        return request;
    }

    private Object request(JsonObject request) {
        start();
        try {
            writer.write((GSON.toJson(request) + "\n").getBytes(StandardCharsets.UTF_8));
            writer.flush();
            String line = reader.readLine();
            if (line == null) throw new IOException("python runner exited");
            JsonObject response = GSON.fromJson(line, JsonObject.class);
            if (response.has("error")) throw new RuntimeException(response.get("error").getAsString());
            return GSON.fromJson(response.get("result"), Object.class);
        } catch (IOException e) {
            throw new RuntimeException("python bridge io error", e);
        }
    }
}
