package com.andrew.note3lanbackup;

import android.content.Context;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LanBackupServer {
    public interface Listener {
        void onServerStatus(String message);
    }

    private final Context context;
    private final String ip;
    private final Listener listener;
    private final String token;
    private final List<StorageScanner.Volume> volumes;
    private final AtomicBoolean heavyTransfer = new AtomicBoolean(false);
    private ExecutorService executor;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    public LanBackupServer(Context context, String ip, Listener listener) {
        this.context = context.getApplicationContext();
        this.ip = ip;
        this.listener = listener;
        this.token = String.format(Locale.US, "%06d", new Random().nextInt(1000000));
        this.volumes = StorageScanner.findVolumes(context);
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        IOException last = null;
        for (int candidate = 8787; candidate <= 8797; candidate++) {
            try {
                serverSocket = new ServerSocket(candidate);
                port = candidate;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (serverSocket == null) {
            throw last == null ? new IOException("没有可用端口") : last;
        }
        executor = Executors.newFixedThreadPool(3);
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "LanBackup-Accept");
        acceptThread.start();
        notifyStatus("等待电脑连接，地址：" + getAccessUrl());
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
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        heavyTransfer.set(false);
    }

    public boolean isRunning() {
        return running;
    }

    public String getToken() {
        return token;
    }

    public String getAccessUrl() {
        return "http://" + ip + ":" + port + "/" + token + "/";
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setSoTimeout(15000);
                ExecutorService current = executor;
                if (current != null) {
                    current.execute(new Runnable() {
                        @Override
                        public void run() {
                            handle(socket);
                        }
                    });
                } else {
                    socket.close();
                }
            } catch (SocketException e) {
                if (running) {
                    notifyStatus("网络监听中断：" + safeMessage(e));
                }
            } catch (IOException e) {
                if (running) {
                    notifyStatus("连接错误：" + safeMessage(e));
                }
            }
        }
    }

    private void handle(Socket socket) {
        BufferedReader reader = null;
        BufferedOutputStream output = null;
        boolean responseStarted = false;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));
            output = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() == 0) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                // Consume headers. This app only needs GET requests.
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                sendText(output, 405, "Method Not Allowed", "只支持 GET 请求", "text/plain; charset=utf-8");
                return;
            }
            responseStarted = true;
            route(parts[1], output);
        } catch (Exception e) {
            notifyStatus("传输中断：" + safeMessage(e));
            if (!responseStarted) {
                try {
                    if (output != null) {
                        sendText(output, 500, "Internal Server Error", "传输失败：" + safeMessage(e),
                                "text/plain; charset=utf-8");
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            try {
                if (output != null) {
                    output.flush();
                }
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(String rawTarget, OutputStream output) throws Exception {
        String pathPart = rawTarget;
        String queryPart = "";
        int question = rawTarget.indexOf('?');
        if (question >= 0) {
            pathPart = rawTarget.substring(0, question);
            queryPart = rawTarget.substring(question + 1);
        }
        String decodedPath = URLDecoder.decode(pathPart, "UTF-8");
        String prefix = "/" + token;
        if (!decodedPath.equals(prefix) && !decodedPath.startsWith(prefix + "/")) {
            sendText(output, 403, "Forbidden", "访问码错误", "text/plain; charset=utf-8");
            return;
        }
        String endpoint = decodedPath.substring(prefix.length());
        while (endpoint.startsWith("/")) {
            endpoint = endpoint.substring(1);
        }
        Map<String, String> query = parseQuery(queryPart);

        if (endpoint.length() == 0) {
            sendHtml(output, rootPage());
        } else if ("quick-backup.zip".equals(endpoint)) {
            runHeavyTransfer(output, "Note3_Recommended_Backup_" + timestampForFile() + ".zip",
                    new HeavyAction() {
                        @Override
                        public void run(OutputStream stream) throws Exception {
                            BackupExporter.writeQuickBackup(context, volumes, stream, progress());
                        }
                    });
        } else if ("full-backup.zip".equals(endpoint)) {
            runHeavyTransfer(output, "Note3_Full_Backup_" + timestampForFile() + ".zip",
                    new HeavyAction() {
                        @Override
                        public void run(OutputStream stream) throws Exception {
                            BackupExporter.writeFullBackup(context, volumes, stream, progress());
                        }
                    });
        } else if ("contacts.vcf".equals(endpoint)) {
            sendGenerated(output, "text/vcard; charset=utf-8", "contacts.vcf", new WriterAction() {
                @Override
                public void write(BufferedWriter writer) throws Exception {
                    BackupExporter.writeContacts(context, writer);
                }
            });
        } else if ("sms.csv".equals(endpoint)) {
            sendGenerated(output, "text/csv; charset=utf-8", "sms.csv", new WriterAction() {
                @Override
                public void write(BufferedWriter writer) throws Exception {
                    BackupExporter.writeSms(context, writer);
                }
            });
        } else if ("call-log.csv".equals(endpoint)) {
            sendGenerated(output, "text/csv; charset=utf-8", "call_log.csv", new WriterAction() {
                @Override
                public void write(BufferedWriter writer) throws Exception {
                    BackupExporter.writeCalls(context, writer);
                }
            });
        } else if ("apps.txt".equals(endpoint)) {
            sendGenerated(output, "text/plain; charset=utf-8", "installed_apps.txt", new WriterAction() {
                @Override
                public void write(BufferedWriter writer) throws Exception {
                    BackupExporter.writeApps(context, writer);
                }
            });
        } else if ("device.txt".equals(endpoint)) {
            sendGenerated(output, "text/plain; charset=utf-8", "device_info.txt", new WriterAction() {
                @Override
                public void write(BufferedWriter writer) throws Exception {
                    BackupExporter.writeDeviceInfo(context, writer);
                }
            });
        } else if ("browse".equals(endpoint)) {
            sendBrowse(output, query);
        } else if ("file".equals(endpoint)) {
            sendFile(output, query);
        } else if ("folder.zip".equals(endpoint)) {
            sendFolderZip(output, query);
        } else {
            sendText(output, 404, "Not Found", "没有这个页面", "text/plain; charset=utf-8");
        }
    }

    private interface HeavyAction {
        void run(OutputStream stream) throws Exception;
    }

    private interface WriterAction {
        void write(BufferedWriter writer) throws Exception;
    }

    private void runHeavyTransfer(OutputStream output, String filename, HeavyAction action) throws Exception {
        if (!heavyTransfer.compareAndSet(false, true)) {
            sendText(output, 429, "Too Many Requests",
                    "手机正在进行另一个 ZIP 下载。请等待它完成后再试。", "text/plain; charset=utf-8");
            return;
        }
        try {
            notifyStatus("电脑正在下载 " + filename + "。请勿锁屏。");
            sendDownloadHeaders(output, "application/zip", filename, -1);
            output.flush();
            action.run(output);
            output.flush();
            notifyStatus("下载已完成：" + filename);
        } finally {
            heavyTransfer.set(false);
        }
    }

    private BackupExporter.Progress progress() {
        return new BackupExporter.Progress() {
            @Override
            public void onProgress(String message) {
                notifyStatus(message + "。请勿锁屏。");
            }
        };
    }

    private void sendGenerated(OutputStream output, String contentType, String filename,
                               WriterAction action) throws Exception {
        notifyStatus("正在导出 " + filename);
        sendDownloadHeaders(output, contentType, filename, -1);
        output.flush();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, "UTF-8"));
        action.write(writer);
        writer.flush();
        notifyStatus("已导出 " + filename);
    }

    private void sendBrowse(OutputStream output, Map<String, String> query) throws Exception {
        StorageScanner.Volume volume = requireVolume(query.get("v"));
        String relative = query.get("p");
        File directory = StorageScanner.resolveInside(volume, relative);
        if (!directory.exists() || !directory.isDirectory() || !directory.canRead()) {
            sendText(output, 404, "Not Found", "目录不存在或不可读取", "text/plain; charset=utf-8");
            return;
        }
        sendHtml(output, browsePage(volume, directory));
    }

    private void sendFile(OutputStream output, Map<String, String> query) throws Exception {
        StorageScanner.Volume volume = requireVolume(query.get("v"));
        File file = StorageScanner.resolveInside(volume, query.get("p"));
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            sendText(output, 404, "Not Found", "文件不存在或不可读取", "text/plain; charset=utf-8");
            return;
        }
        String mime = guessMime(file.getName());
        sendDownloadHeaders(output, mime, file.getName(), file.length());
        output.flush();
        BufferedInputStream input = null;
        try {
            input = new BufferedInputStream(new FileInputStream(file), 64 * 1024);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            notifyStatus("已下载文件：" + file.getName());
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private void sendFolderZip(OutputStream output, Map<String, String> query) throws Exception {
        final StorageScanner.Volume volume = requireVolume(query.get("v"));
        final File folder = StorageScanner.resolveInside(volume, query.get("p"));
        if (!folder.exists() || !folder.canRead()) {
            sendText(output, 404, "Not Found", "目录不存在或不可读取", "text/plain; charset=utf-8");
            return;
        }
        final String name = folder.getName().length() == 0 ? volume.label : folder.getName();
        runHeavyTransfer(output, asciiFileName(name) + "_" + timestampForFile() + ".zip",
                new HeavyAction() {
                    @Override
                    public void run(OutputStream stream) throws Exception {
                        BackupExporter.writeFolderZip(folder, name, stream, progress());
                    }
                });
    }

    private StorageScanner.Volume requireVolume(String id) throws IOException {
        StorageScanner.Volume volume = StorageScanner.findVolume(volumes, id);
        if (volume == null) {
            throw new IOException("未知存储卷");
        }
        return volume;
    }

    private String rootPage() {
        String base = "/" + token + "/";
        StringBuilder html = htmlStart("Note 3 局域网备份");
        html.append("<h1>手机备份控制台</h1>");
        html.append("<div class='ok'>已连接到 ").append(escapeHtml(android.os.Build.MODEL))
                .append("，Android ").append(escapeHtml(android.os.Build.VERSION.RELEASE)).append("</div>");
        html.append("<div class='warning'><b>先点推荐一键备份。</b> 下载期间不要锁屏，不要切走手机上的备份应用。ZIP 没有预先计算大小，浏览器会一直下载到完成。</div>");

        html.append("<section><h2>推荐</h2>");
        html.append(bigButton(base + "quick-backup.zip", "下载推荐一键备份 ZIP",
                "相机照片、图片、下载、文档、微信/腾讯目录、音乐视频、通讯录、短信、通话记录和应用清单"));
        html.append(bigButton(base + "full-backup.zip", "下载全盘备份 ZIP",
                "把所有可读取的内部存储和 SD 卡文件都打包。数据多时会很慢，建议推荐备份成功后再用。"));
        html.append("</section>");

        html.append("<section><h2>单独导出记录</h2><div class='grid'>");
        html.append(smallButton(base + "contacts.vcf", "通讯录 VCF"));
        html.append(smallButton(base + "sms.csv", "短信 CSV"));
        html.append(smallButton(base + "call-log.csv", "通话记录 CSV"));
        html.append(smallButton(base + "apps.txt", "应用清单 TXT"));
        html.append(smallButton(base + "device.txt", "设备信息 TXT"));
        html.append("</div></section>");

        html.append("<section><h2>按目录浏览与下载</h2>");
        for (StorageScanner.Volume volume : volumes) {
            String browse = base + "browse?v=" + url(volume.id) + "&p=";
            html.append("<div class='volume'><b>").append(escapeHtml(volume.label)).append("</b><br>")
                    .append(escapeHtml(volume.root.getAbsolutePath())).append("<br>")
                    .append("总容量 ").append(StorageScanner.humanSize(volume.root.getTotalSpace()))
                    .append("，可用 ").append(StorageScanner.humanSize(volume.root.getUsableSpace()))
                    .append("<br><a class='secondary' href='").append(browse).append("'>打开目录</a></div>");
        }
        html.append("</section>");
        html.append("<p class='footer'>这是只读传输服务器。关闭手机上的应用或点击“停止传输”后，地址立即失效。</p>");
        html.append(htmlEnd());
        return html.toString();
    }

    private String browsePage(StorageScanner.Volume volume, File directory) throws Exception {
        String base = "/" + token + "/";
        String relative = StorageScanner.relativePath(volume, directory);
        StringBuilder html = htmlStart("浏览 " + volume.label);
        html.append("<h1>").append(escapeHtml(volume.label)).append("</h1>");
        html.append("<p class='path'>/").append(escapeHtml(relative)).append("</p>");
        html.append("<p><a class='secondary' href='").append(base).append("'>← 返回备份首页</a> ");
        html.append("<a class='secondary' href='").append(base).append("folder.zip?v=")
                .append(url(volume.id)).append("&p=").append(url(relative)).append("'>下载当前目录 ZIP</a></p>");

        if (relative.length() > 0) {
            File parent = directory.getParentFile();
            String parentRelative = parent == null ? "" : StorageScanner.relativePath(volume, parent);
            html.append("<div class='item'><a href='").append(base).append("browse?v=")
                    .append(url(volume.id)).append("&p=").append(url(parentRelative)).append("'>📁 .. 上一级</a></div>");
        }

        File[] files = directory.listFiles();
        if (files == null) {
            html.append("<div class='warning'>目录无法读取。</div>");
        } else {
            ArrayList<File> sorted = new ArrayList<File>();
            Collections.addAll(sorted, files);
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    if (left.isDirectory() != right.isDirectory()) {
                        return left.isDirectory() ? -1 : 1;
                    }
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            if (sorted.size() > 3000) {
                html.append("<div class='warning'>这个目录有 ").append(sorted.size())
                        .append(" 项。页面只显示前 3000 项，建议直接下载当前目录 ZIP。</div>");
            }
            int shown = 0;
            for (File file : sorted) {
                if (shown++ >= 3000) {
                    break;
                }
                String childRelative = StorageScanner.relativePath(volume, file);
                html.append("<div class='item'>");
                if (file.isDirectory()) {
                    html.append("<a href='").append(base).append("browse?v=").append(url(volume.id))
                            .append("&p=").append(url(childRelative)).append("'>📁 ")
                            .append(escapeHtml(file.getName())).append("</a>")
                            .append(" <a class='tiny' href='").append(base).append("folder.zip?v=")
                            .append(url(volume.id)).append("&p=").append(url(childRelative)).append("'>ZIP</a>");
                } else {
                    html.append("<a href='").append(base).append("file?v=").append(url(volume.id))
                            .append("&p=").append(url(childRelative)).append("'>📄 ")
                            .append(escapeHtml(file.getName())).append("</a>")
                            .append(" <span class='size'>").append(StorageScanner.humanSize(file.length())).append("</span>");
                }
                html.append("</div>");
            }
        }
        html.append(htmlEnd());
        return html.toString();
    }

    private StringBuilder htmlStart(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<title>").append(escapeHtml(title)).append("</title>")
                .append("<style>")
                .append("body{font-family:Arial,'Microsoft YaHei',sans-serif;max-width:900px;margin:0 auto;padding:20px;background:#f5f6f8;color:#1d2329}")
                .append("h1{font-size:28px}h2{font-size:21px;margin-top:28px}section{background:#fff;padding:18px;margin:16px 0;border-radius:12px}")
                .append("a{text-decoration:none;color:#0759b5}.button{display:block;background:#1565c0;color:#fff;padding:16px;border-radius:10px;margin:12px 0;font-size:19px}")
                .append(".button small{display:block;color:#e5f1ff;margin-top:7px;font-size:14px;line-height:1.45}.secondary{display:inline-block;background:#e9eef5;padding:10px 13px;border-radius:8px;margin:5px 4px 5px 0}")
                .append(".grid{display:flex;flex-wrap:wrap;gap:8px}.grid a{background:#e9eef5;padding:12px;border-radius:8px}.warning{background:#fff3cd;padding:14px;border-radius:10px;line-height:1.55}.ok{background:#dff3e4;padding:13px;border-radius:10px}")
                .append(".volume{border-top:1px solid #ddd;padding:14px 0;line-height:1.6}.item{background:#fff;border-bottom:1px solid #e5e5e5;padding:11px 8px;word-break:break-all}.tiny{float:right;background:#eef2f6;padding:3px 8px;border-radius:5px}.size{float:right;color:#667}.path{word-break:break-all;color:#555}.footer{color:#666;font-size:14px}")
                .append("</style></head><body>");
        return sb;
    }

    private String htmlEnd() {
        return "</body></html>";
    }

    private String bigButton(String href, String title, String detail) {
        return "<a class='button' href='" + href + "'>" + escapeHtml(title) +
                "<small>" + escapeHtml(detail) + "</small></a>";
    }

    private String smallButton(String href, String title) {
        return "<a href='" + href + "'>" + escapeHtml(title) + "</a>";
    }

    private Map<String, String> parseQuery(String query) throws Exception {
        HashMap<String, String> values = new HashMap<String, String>();
        if (query == null || query.length() == 0) {
            return values;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String key = equals >= 0 ? pair.substring(0, equals) : pair;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            values.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
        }
        return values;
    }

    private String url(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private void sendHtml(OutputStream output, String html) throws IOException {
        sendText(output, 200, "OK", html, "text/html; charset=utf-8");
    }

    private void sendText(OutputStream output, int code, String reason, String text, String contentType)
            throws IOException {
        byte[] bytes = text.getBytes("UTF-8");
        String headers = "HTTP/1.0 " + code + " " + reason + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n";
        output.write(headers.getBytes("ISO-8859-1"));
        output.write(bytes);
        output.flush();
    }

    private void sendDownloadHeaders(OutputStream output, String contentType, String filename, long length)
            throws IOException {
        String fallback = asciiFileName(filename);
        String encoded = url(filename);
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.0 200 OK\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Disposition: attachment; filename=\"").append(fallback)
                .append("\"; filename*=UTF-8''").append(encoded).append("\r\n")
                .append("Cache-Control: no-store\r\n");
        if (length >= 0) {
            headers.append("Content-Length: ").append(length).append("\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        output.write(headers.toString().getBytes("ISO-8859-1"));
    }

    private String asciiFileName(String value) {
        if (TextUtils.isEmpty(value)) {
            return "download.bin";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private String guessMime(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot >= 0 && dot < filename.length() - 1) {
            String ext = filename.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String timestampForFile() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return TextUtils.isEmpty(message) ? t.getClass().getSimpleName() : message;
    }

    private void notifyStatus(String message) {
        if (listener != null) {
            listener.onServerStatus(message);
        }
    }
}
