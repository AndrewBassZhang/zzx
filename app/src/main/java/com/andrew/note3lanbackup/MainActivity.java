package com.andrew.note3lanbackup;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity implements LanBackupServer.Listener {
    private final Handler handler = new Handler();
    private TextView statusView;
    private TextView addressView;
    private TextView detailView;
    private Button startButton;
    private Button stopButton;
    private LanBackupServer server;
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        refreshDeviceSummary();
    }

    private void buildUi() {
        int pad = dp(18);
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("局域网备份助手");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("专为 Android 5.0 老手机准备。电脑与手机连同一个 Wi‑Fi，电脑浏览器直接下载照片、文件、通讯录、短信和通话记录。全程只读，不会删除手机内容。");
        subtitle.setTextSize(16);
        subtitle.setLineSpacing(0, 1.25f);
        root.addView(subtitle, matchWrap());

        statusView = makePanel("状态：尚未启动");
        root.addView(statusView, panelParams());

        addressView = makePanel("启动后，这里会显示电脑访问地址。");
        addressView.setTextSize(19);
        addressView.setTextIsSelectable(true);
        root.addView(addressView, panelParams());

        startButton = new Button(this);
        startButton.setText("启动局域网传输");
        startButton.setTextSize(18);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServer();
            }
        });
        root.addView(startButton, buttonParams());

        stopButton = new Button(this);
        stopButton.setText("停止传输");
        stopButton.setTextSize(18);
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });
        root.addView(stopButton, buttonParams());

        Button wifiButton = new Button(this);
        wifiButton.setText("打开 Wi‑Fi 设置");
        wifiButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
        root.addView(wifiButton, buttonParams());

        detailView = new TextView(this);
        detailView.setTextSize(15);
        detailView.setLineSpacing(0, 1.2f);
        detailView.setPadding(0, dp(14), 0, dp(10));
        root.addView(detailView, matchWrap());

        TextView warning = makePanel(
                "使用时请注意：\n" +
                "1. 手机和电脑必须连接同一个 Wi‑Fi。\n" +
                "2. 下载期间保持本应用在前台，不要锁屏。\n" +
                "3. 先下载“推荐一键备份”，再按需下载全盘 ZIP。\n" +
                "4. 只有知道屏幕上访问码的人才能下载，备份结束后立刻点“停止传输”。");
        root.addView(warning, panelParams());

        setContentView(scrollView);
    }

    private TextView makePanel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackgroundColor(0xffeeeeee);
        view.setLineSpacing(0, 1.2f);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams panelParams() {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(12);
        return p;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(10);
        p.height = dp(54);
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshDeviceSummary() {
        List<StorageScanner.Volume> volumes = StorageScanner.findVolumes(this);
        StringBuilder sb = new StringBuilder();
        sb.append("检测到的备份来源：\n");
        for (StorageScanner.Volume volume : volumes) {
            sb.append("• ").append(volume.label)
                    .append("：").append(StorageScanner.humanSize(volume.root.getTotalSpace()))
                    .append("，可用 ").append(StorageScanner.humanSize(volume.root.getUsableSpace()))
                    .append("\n");
        }
        sb.append("\n可导出：共享存储全部文件、相机照片、下载目录、微信/腾讯目录、通讯录、短信、通话记录、已安装应用清单和设备信息。\n\n");
        sb.append("不能导出：其他应用被系统隔离的私有数据、账号密码、受 DRM 保护的数据。没有 Root 的普通应用无法读取这些内容。");
        detailView.setText(sb.toString());
    }

    private void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }
        String ip = findLocalIpv4();
        if (TextUtils.isEmpty(ip)) {
            statusView.setText("状态：没有检测到局域网 IPv4 地址。请先连接 Wi‑Fi，再重试。");
            Toast.makeText(this, "请先连接 Wi‑Fi", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            server = new LanBackupServer(this, ip, this);
            server.start();
            acquireWifiLock();
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            statusView.setText("状态：服务器运行中。请保持此页面亮着。");
            addressView.setText(
                    "在电脑浏览器地址栏输入：\n\n" + server.getAccessUrl() +
                    "\n\n访问码：" + server.getToken());
        } catch (Exception e) {
            server = null;
            releaseWifiLock();
            statusView.setText("启动失败：" + safeMessage(e));
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        releaseWifiLock();
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusView.setText("状态：已停止，电脑无法再访问手机文件。");
        addressView.setText("传输已停止。");
    }

    private void acquireWifiLock() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (manager != null) {
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Note3LanBackup");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
        wifiLock = null;
    }

    private String findLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress() && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return TextUtils.isEmpty(message) ? t.getClass().getSimpleName() : message;
    }

    @Override
    public void onServerStatus(final String message) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (server != null && server.isRunning()) {
                    statusView.setText("状态：" + message);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
