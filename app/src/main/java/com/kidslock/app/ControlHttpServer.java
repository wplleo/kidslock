package com.kidslock.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 电视端轻量 HTTP 服务器：让同一 WiFi 下的手机浏览器可以直接控制锁屏。
 *
 * 不引入任何第三方库，纯 Java ServerSocket 实现，避免 TV 设备的依赖/兼容问题。
 * 所有写操作都必须带家长 PIN，且复用 PrefManager 的防爆破计数。
 *
 * 路由：
 *   GET  /                     -> 手机控制页 (assets/control.html)
 *   GET  /api/ping             -> 连通性探测，不需要 PIN
 *   GET  /api/status?pin=      -> 实时状态
 *   POST /api/lock?pin=        -> 立即锁屏
 *   POST /api/unlock?pin=      -> 立即解锁
 *   POST /api/timer?minutes=   -> 设置观看时长并开始计时
 *   POST /api/addtime?minutes= -> 临时加时
 *   POST /api/stoptimer        -> 停止计时
 *   POST /api/settings         -> 改 PIN / 解锁题数 / 开机自启 / 默认时长
 */
public class ControlHttpServer {

    private static final String TAG = "ControlHttpServer";

    private final Context context;
    private final PrefManager pref;
    private final int port;
    private final String ip;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running = false;
    private String controlPageHtml = null;

    public ControlHttpServer(Context context, PrefManager pref, int port, String ip) {
        this.context = context.getApplicationContext();
        this.pref = pref;
        this.port = port;
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    /** 绑定端口并开始接受连接。绑定失败会抛 IOException，由上层做端口回退。 */
    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "kidslock-http-accept");
        acceptThread.start();
        Log.i(TAG, "HTTP control server listening on " + ip + ":" + port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(10_000);
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running) Log.e(TAG, "accept failed", e);
                else break;
            }
        }
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        pool.shutdownNow();
        Log.i(TAG, "HTTP control server stopped");
    }

    // ==================== 请求处理 ====================

    private void handle(Socket socket) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            // 读完请求头（不读取 body，前端 POST 不带 body）
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // discard headers
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String fullPath = parts[1];

            String path = fullPath;
            Map<String, String> query = new HashMap<>();
            int qIndex = fullPath.indexOf('?');
            if (qIndex >= 0) {
                path = fullPath.substring(0, qIndex);
                query = parseQuery(fullPath.substring(qIndex + 1));
            }

            Response resp = route(method, path, query);
            writeResponse(s.getOutputStream(), resp);
        } catch (Exception e) {
            Log.w(TAG, "handle request failed: " + e.getMessage());
        }
    }

    private Response route(String method, String path, Map<String, String> q) {
        if ("/".equals(path) || "/index.html".equals(path)) {
            return html(loadControlPage());
        }
        if ("/api/ping".equals(path)) {
            return json(pingJson());
        }

        // 以下接口都需要 PIN
        if (!checkPin(q.get("pin"))) {
            return json(errorJson(pref.isPinLocked() ? "PIN_LOCKED" : "PIN_WRONG",
                    pref.isPinLocked() ? "尝试次数过多，请稍后再试" : "家长PIN不正确"));
        }

        switch (path) {
            case "/api/status":
                return json(statusJson());
            case "/api/lock":
                if (!"POST".equals(method)) break;
                return json(doLock());
            case "/api/unlock":
                if (!"POST".equals(method)) break;
                return json(doUnlock());
            case "/api/timer":
                if (!"POST".equals(method)) break;
                return json(doStartTimer(q.get("minutes")));
            case "/api/addtime":
                if (!"POST".equals(method)) break;
                return json(doAddTime(q.get("minutes")));
            case "/api/stoptimer":
                if (!"POST".equals(method)) break;
                return json(doStopTimer());
            case "/api/settings":
                if (!"POST".equals(method)) break;
                return json(doSettings(q));
            default:
                break;
        }
        return json(errorJson("NOT_FOUND", "未知接口"));
    }

    // ==================== 业务动作 ====================

    private JSONObject doLock() {
        try {
            LockService.requestLock(context);
            return okJson("已锁屏");
        } catch (Exception e) {
            Log.e(TAG, "lock failed", e);
            return errorJson("FAILED", "锁屏失败：" + e.getMessage());
        }
    }

    private JSONObject doUnlock() {
        try {
            LockService.requestUnlock(context);
            return okJson("已解锁");
        } catch (Exception e) {
            Log.e(TAG, "unlock failed", e);
            return errorJson("FAILED", "解锁失败：" + e.getMessage());
        }
    }

    private JSONObject doStartTimer(String minutesStr) {
        int minutes = parseInt(minutesStr, -1);
        if (minutes < 1 || minutes > 720) {
            return errorJson("BAD_PARAM", "时长需在 1~720 分钟之间");
        }
        pref.setWatchLimitMinutes(minutes);
        pref.setLocked(false);
        pref.setHomeAliasEnabled(context, false);
        pref.startTimer(minutes);
        startTimerService();
        return okJson("已设置 " + minutes + " 分钟并开始计时");
    }

    private JSONObject doAddTime(String minutesStr) {
        int minutes = parseInt(minutesStr, -1);
        if (minutes < 1 || minutes > 720) {
            return errorJson("BAD_PARAM", "加时需在 1~720 分钟之间");
        }
        boolean wasLocked = pref.isLocked();
        // 同步清理锁屏状态，不走异步 unlock（避免和紧接着的 startTimer 抢顺序）
        pref.setLocked(false);
        pref.setHomeAliasEnabled(context, false);
        pref.resetPinFailures();
        long base = pref.isTimerActive() ? Math.max(System.currentTimeMillis(), pref.getTimerEndTime())
                : System.currentTimeMillis();
        pref.setTimerEndTime(base + (long) minutes * 60_000L);
        pref.setTimerActive(true);
        startTimerService();
        return okJson(wasLocked ? ("已解锁并加时 " + minutes + " 分钟") : ("已加时 " + minutes + " 分钟"));
    }

    private JSONObject doStopTimer() {
        pref.stopTimer();
        // 不直接 stopService：让 LockService 自己按状态决定退出或继续守护锁屏，
        // 避免锁屏状态下误停守护服务导致孩子绕到桌面。
        startTimerService();
        return okJson("已停止计时");
    }

    private JSONObject doSettings(Map<String, String> q) {
        StringBuilder changed = new StringBuilder();
        String watch = q.get("watch_limit");
        if (watch != null && !watch.isEmpty()) {
            int v = parseInt(watch, -1);
            if (v < 1 || v > 720) return errorJson("BAD_PARAM", "观看时长无效");
            pref.setWatchLimitMinutes(v);
            changed.append("观看时长=").append(v).append("分钟 ");
        }
        String count = q.get("unlock_count");
        if (count != null && !count.isEmpty()) {
            int v = parseInt(count, -1);
            if (v < 1 || v > 20) return errorJson("BAD_PARAM", "解锁题数无效");
            pref.setUnlockCount(v);
            changed.append("解锁题数=").append(v).append(" ");
        }
        String auto = q.get("autostart");
        if (auto != null && !auto.isEmpty()) {
            boolean v = "1".equals(auto) || "true".equalsIgnoreCase(auto);
            pref.setAutoStart(v);
            changed.append("开机自启=").append(v ? "开" : "关").append(" ");
        }
        String newPin = q.get("newpin");
        if (newPin != null && !newPin.isEmpty()) {
            if (!newPin.matches("\\d{4}")) return errorJson("BAD_PARAM", "PIN 必须是 4 位数字");
            pref.setParentPin(newPin);
            changed.append("PIN已修改 ");
        }
        if (changed.length() == 0) return errorJson("BAD_PARAM", "没有要修改的设置");
        return okJson("已保存：" + changed.toString().trim());
    }

    private void startTimerService() {
        Intent i = new Intent(context, LockService.class);
        i.putExtra("action", "start_timer");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    // ==================== PIN 校验 ====================

    private boolean checkPin(String pin) {
        if (pref.isPinLocked()) return false;
        if (pin == null || !pin.equals(pref.getParentPin())) {
            pref.recordPinFailure();
            return false;
        }
        pref.resetPinFailures();
        return true;
    }

    // ==================== JSON ====================

    private JSONObject okJson(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("msg", msg);
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject errorJson(String code, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("code", code).put("msg", msg);
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject pingJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("app", "kidslock").put("version", "1.1")
                    .put("ip", ip == null ? "" : ip).put("port", port);
        } catch (Exception ignored) {
        }
        return o;
    }

    private JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            long remainSec = pref.getRemainingMillis() / 1000;
            o.put("ok", true)
                    .put("locked", pref.isLocked())
                    .put("timerActive", pref.isTimerActive())
                    .put("remainingSec", remainSec)
                    .put("watchLimitMin", pref.getWatchLimitMinutes())
                    .put("unlockCount", pref.getUnlockCount())
                    .put("autoStart", pref.isAutoStart())
                    .put("remoteEnabled", pref.isRemoteControlEnabled())
                    .put("pinLocked", pref.isPinLocked())
                    .put("pinLockRemainingSec", pref.getPinLockRemainingMillis() / 1000)
                    .put("ip", ip == null ? "" : ip)
                    .put("port", port);
        } catch (Exception ignored) {
        }
        return o;
    }

    // ==================== HTTP 细节 ====================

    private static class Response {
        final String contentType;
        final byte[] body;

        Response(String contentType, byte[] body) {
            this.contentType = contentType;
            this.body = body;
        }
    }

    private Response html(String content) {
        return new Response("text/html; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    private Response json(JSONObject o) {
        return new Response("application/json; charset=utf-8",
                o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeResponse(OutputStream out, Response resp) throws IOException {
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + resp.contentType + "\r\n"
                + "Content-Length: " + resp.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(resp.body);
        out.flush();
    }

    private Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            try {
                if (idx >= 0) {
                    map.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                            URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                } else {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private synchronized String loadControlPage() {
        if (controlPageHtml != null) return controlPageHtml;
        try (InputStream is = context.getAssets().open("control.html")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            controlPageHtml = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "load control.html failed", e);
            controlPageHtml = "<html><body><h2>控制页加载失败</h2></body></html>";
        }
        return controlPageHtml;
    }

    /** 取本机局域网 IPv4，兼容 WiFi 和有线（小米电视常插网线）。 */
    public static String getLocalIpAddress() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) continue;
                    String host = addr.getHostAddress();
                    if (host == null) continue;
                    // 优先 wlan / eth
                    if (name.startsWith("wlan") || name.startsWith("eth")) return host;
                    if (fallback == null) fallback = host;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getLocalIpAddress failed: " + e.getMessage());
        }
        return fallback;
    }
}
