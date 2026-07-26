package com.andrew.note3lanbackup;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.ContactsContract;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupExporter {
    public interface Progress {
        void onProgress(String message);
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    private BackupExporter() {
    }

    public static void writeQuickBackup(Context context, List<StorageScanner.Volume> volumes,
                                        OutputStream output, Progress progress) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.setLevel(Deflater.BEST_SPEED);
        Set<String> visited = new HashSet<String>();
        int[] fileCount = new int[]{0};

        String[] common = new String[]{
                "DCIM", "Pictures", "Download", "Downloads", "Documents", "Movies", "Music",
                "Recordings", "Sounds", "bluetooth", "Bluetooth", "tencent", "Tencent",
                "WhatsApp", "Samsung", "S Note", "SNote", "ScreenRecorder"
        };

        for (StorageScanner.Volume volume : volumes) {
            String volumePrefix = safeZipName(volume.label) + "/";
            for (String relative : common) {
                File candidate = new File(volume.root, relative);
                if (candidate.exists() && candidate.canRead()) {
                    addPath(zip, candidate, volumePrefix + relative, visited, fileCount, progress);
                }
            }
        }

        addGeneratedRecords(context, zip, progress);
        zip.finish();
        zip.flush();
        if (progress != null) {
            progress.onProgress("推荐备份完成，共打包 " + fileCount[0] + " 个文件");
        }
    }

    public static void writeFullBackup(Context context, List<StorageScanner.Volume> volumes,
                                       OutputStream output, Progress progress) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.setLevel(Deflater.BEST_SPEED);
        Set<String> visited = new HashSet<String>();
        int[] fileCount = new int[]{0};

        for (StorageScanner.Volume volume : volumes) {
            String prefix = safeZipName(volume.label);
            addPath(zip, volume.root, prefix, visited, fileCount, progress);
        }
        addGeneratedRecords(context, zip, progress);
        zip.finish();
        zip.flush();
        if (progress != null) {
            progress.onProgress("全盘备份完成，共打包 " + fileCount[0] + " 个文件");
        }
    }

    public static void writeFolderZip(File source, String displayName, OutputStream output,
                                      Progress progress) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(output);
        zip.setLevel(Deflater.BEST_SPEED);
        Set<String> visited = new HashSet<String>();
        int[] fileCount = new int[]{0};
        addPath(zip, source, safeZipName(displayName), visited, fileCount, progress);
        zip.finish();
        zip.flush();
        if (progress != null) {
            progress.onProgress("目录下载完成，共 " + fileCount[0] + " 个文件");
        }
    }

    private static void addGeneratedRecords(Context context, ZipOutputStream zip,
                                            Progress progress) throws IOException {
        writeZipTextEntry(zip, "_records/device_info.txt", new TextWriter() {
            @Override
            public void write(Context ctx, Writer writer) throws IOException {
                writeDeviceInfo(ctx, writer);
            }
        }, context);

        writeZipTextEntry(zip, "_records/installed_apps.txt", new TextWriter() {
            @Override
            public void write(Context ctx, Writer writer) throws IOException {
                writeApps(ctx, writer);
            }
        }, context);

        writeProviderEntry(zip, "_records/contacts.vcf", context, new TextWriter() {
            @Override
            public void write(Context ctx, Writer writer) throws IOException {
                writeContacts(ctx, writer);
            }
        });

        writeProviderEntry(zip, "_records/sms.csv", context, new TextWriter() {
            @Override
            public void write(Context ctx, Writer writer) throws IOException {
                writeSms(ctx, writer);
            }
        });

        writeProviderEntry(zip, "_records/call_log.csv", context, new TextWriter() {
            @Override
            public void write(Context ctx, Writer writer) throws IOException {
                writeCalls(ctx, writer);
            }
        });

        if (progress != null) {
            progress.onProgress("通讯录、短信、通话记录和应用清单已加入备份");
        }
    }

    private interface TextWriter {
        void write(Context context, Writer writer) throws IOException;
    }

    private static void writeZipTextEntry(ZipOutputStream zip, String name, TextWriter writer,
                                          Context context) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(zip, "UTF-8"));
        writer.write(context, bufferedWriter);
        bufferedWriter.flush();
        zip.closeEntry();
    }

    private static void writeProviderEntry(ZipOutputStream zip, String name, Context context,
                                           TextWriter writer) throws IOException {
        try {
            writeZipTextEntry(zip, name, writer, context);
        } catch (SecurityException e) {
            writeErrorEntry(zip, name + ".ERROR.txt", "没有读取权限：" + e.getMessage());
        } catch (RuntimeException e) {
            writeErrorEntry(zip, name + ".ERROR.txt", "导出失败：" + e.getMessage());
        }
    }

    private static void writeErrorEntry(ZipOutputStream zip, String name, String message) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        byte[] bytes = message.getBytes("UTF-8");
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void addPath(ZipOutputStream zip, File source, String entryName,
                                Set<String> visited, int[] fileCount, Progress progress) throws IOException {
        if (source == null || !source.exists() || !source.canRead()) {
            return;
        }
        String canonical;
        try {
            canonical = source.getCanonicalPath();
        } catch (IOException e) {
            return;
        }
        if (!visited.add(canonical)) {
            return;
        }

        entryName = normalizeEntryName(entryName);
        if (source.isDirectory()) {
            if (!entryName.endsWith("/")) {
                entryName += "/";
            }
            ZipEntry directoryEntry = new ZipEntry(entryName);
            directoryEntry.setTime(source.lastModified());
            try {
                zip.putNextEntry(directoryEntry);
                zip.closeEntry();
            } catch (IOException ignored) {
            }

            File[] children = source.listFiles();
            if (children == null) {
                return;
            }
            ArrayList<File> sorted = new ArrayList<File>();
            Collections.addAll(sorted, children);
            Collections.sort(sorted, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    if (left.isDirectory() != right.isDirectory()) {
                        return left.isDirectory() ? -1 : 1;
                    }
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            for (File child : sorted) {
                addPath(zip, child, entryName + safeZipName(child.getName()), visited, fileCount, progress);
            }
        } else if (source.isFile()) {
            ZipEntry fileEntry = new ZipEntry(entryName);
            fileEntry.setTime(source.lastModified());
            zip.putNextEntry(fileEntry);
            BufferedInputStream input = null;
            try {
                input = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    zip.write(buffer, 0, read);
                }
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
                zip.closeEntry();
            }
            fileCount[0]++;
            if (progress != null && fileCount[0] % 50 == 0) {
                progress.onProgress("正在打包，已处理 " + fileCount[0] + " 个文件：" + source.getName());
            }
        }
    }

    private static String normalizeEntryName(String value) {
        String result = value.replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        result = result.replace("../", "_");
        return result.length() == 0 ? "backup" : result;
    }

    private static String safeZipName(String value) {
        if (value == null || value.length() == 0) {
            return "unnamed";
        }
        return value.replace('/', '_').replace('\\', '_').replace(':', '_');
    }

    public static void writeContacts(Context context, Writer writer) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Cursor contacts = null;
        try {
            contacts = resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME},
                    null, null, ContactsContract.Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC");
            if (contacts == null) {
                writer.write("# 无法读取通讯录\n");
                return;
            }
            int idIndex = contacts.getColumnIndex(ContactsContract.Contacts._ID);
            int nameIndex = contacts.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            while (contacts.moveToNext()) {
                String id = contacts.getString(idIndex);
                String name = contacts.getString(nameIndex);
                if (name == null) {
                    name = "";
                }
                writer.write("BEGIN:VCARD\r\n");
                writer.write("VERSION:3.0\r\n");
                writer.write("FN:" + escapeVCard(name) + "\r\n");
                writer.write("N:;" + escapeVCard(name) + ";;;\r\n");

                Cursor phones = null;
                try {
                    phones = resolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER,
                                    ContactsContract.CommonDataKinds.Phone.TYPE},
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                            new String[]{id}, null);
                    if (phones != null) {
                        int numberIndex = phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                        int typeIndex = phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE);
                        while (phones.moveToNext()) {
                            String number = phones.getString(numberIndex);
                            int type = phones.getInt(typeIndex);
                            writer.write("TEL;TYPE=" + phoneType(type) + ":" + escapeVCard(number) + "\r\n");
                        }
                    }
                } finally {
                    if (phones != null) {
                        phones.close();
                    }
                }

                Cursor emails = null;
                try {
                    emails = resolver.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            new String[]{ContactsContract.CommonDataKinds.Email.ADDRESS},
                            ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                            new String[]{id}, null);
                    if (emails != null) {
                        int addressIndex = emails.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS);
                        while (emails.moveToNext()) {
                            String address = emails.getString(addressIndex);
                            writer.write("EMAIL;TYPE=INTERNET:" + escapeVCard(address) + "\r\n");
                        }
                    }
                } finally {
                    if (emails != null) {
                        emails.close();
                    }
                }
                writer.write("END:VCARD\r\n");
            }
        } finally {
            if (contacts != null) {
                contacts.close();
            }
        }
    }

    private static String phoneType(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                return "HOME";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                return "WORK";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                return "CELL";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME:
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK:
                return "FAX";
            default:
                return "VOICE";
        }
    }

    private static String escapeVCard(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    public static void writeSms(Context context, Writer writer) throws IOException {
        writer.write('\ufeff');
        writer.write("date,type,address,read,body\r\n");
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://sms/"),
                    new String[]{"date", "type", "address", "read", "body"},
                    null, null, "date ASC");
            if (cursor == null) {
                writer.write(csvRow("", "", "", "", "无法读取短信"));
                return;
            }
            int dateIndex = cursor.getColumnIndex("date");
            int typeIndex = cursor.getColumnIndex("type");
            int addressIndex = cursor.getColumnIndex("address");
            int readIndex = cursor.getColumnIndex("read");
            int bodyIndex = cursor.getColumnIndex("body");
            while (cursor.moveToNext()) {
                long date = cursor.getLong(dateIndex);
                int type = cursor.getInt(typeIndex);
                writer.write(csvRow(
                        formatDate(date),
                        smsType(type),
                        cursor.getString(addressIndex),
                        cursor.getString(readIndex),
                        cursor.getString(bodyIndex)));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static String smsType(int type) {
        switch (type) {
            case 1:
                return "received";
            case 2:
                return "sent";
            case 3:
                return "draft";
            case 4:
                return "outbox";
            case 5:
                return "failed";
            case 6:
                return "queued";
            default:
                return String.valueOf(type);
        }
    }

    public static void writeCalls(Context context, Writer writer) throws IOException {
        writer.write('\ufeff');
        writer.write("date,type,name,number,duration_seconds\r\n");
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.NUMBER, CallLog.Calls.DURATION},
                    null, null, CallLog.Calls.DATE + " ASC");
            if (cursor == null) {
                writer.write(csvRow("", "", "", "", "无法读取通话记录"));
                return;
            }
            int dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE);
            int typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE);
            int nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
            int numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER);
            int durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION);
            while (cursor.moveToNext()) {
                writer.write(csvRow(
                        formatDate(cursor.getLong(dateIndex)),
                        callType(cursor.getInt(typeIndex)),
                        cursor.getString(nameIndex),
                        cursor.getString(numberIndex),
                        cursor.getString(durationIndex)));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static String callType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "missed";
            case CallLog.Calls.REJECTED_TYPE:
                return "rejected";
            default:
                return String.valueOf(type);
        }
    }

    public static void writeApps(Context context, Writer writer) throws IOException {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo left, ApplicationInfo right) {
                return left.packageName.compareToIgnoreCase(right.packageName);
            }
        });
        writer.write("Installed applications\n");
        writer.write("Generated: " + formatDate(System.currentTimeMillis()) + "\n\n");
        for (ApplicationInfo app : apps) {
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(app));
            } catch (Exception e) {
                label = app.packageName;
            }
            String version = "";
            try {
                PackageInfo info = pm.getPackageInfo(app.packageName, 0);
                version = info.versionName == null ? "" : info.versionName;
            } catch (Exception ignored) {
            }
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            writer.write(label + "\t" + app.packageName + "\t" + version + "\t" +
                    (system ? "system" : "user") + "\n");
        }
    }

    public static void writeDeviceInfo(Context context, Writer writer) throws IOException {
        writer.write("局域网备份助手设备信息\n");
        writer.write("生成时间：" + formatDate(System.currentTimeMillis()) + "\n");
        writer.write("制造商：" + Build.MANUFACTURER + "\n");
        writer.write("品牌：" + Build.BRAND + "\n");
        writer.write("型号：" + Build.MODEL + "\n");
        writer.write("设备：" + Build.DEVICE + "\n");
        writer.write("Android：" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
        writer.write("Build ID：" + Build.DISPLAY + "\n\n");
        List<StorageScanner.Volume> volumes = StorageScanner.findVolumes(context);
        for (StorageScanner.Volume volume : volumes) {
            writer.write(volume.label + "\n");
            writer.write("  路径：" + volume.root.getAbsolutePath() + "\n");
            writer.write("  总容量：" + StorageScanner.humanSize(volume.root.getTotalSpace()) + "\n");
            writer.write("  可用：" + StorageScanner.humanSize(volume.root.getUsableSpace()) + "\n");
        }
    }

    private static String csvRow(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csv(values[i]));
        }
        sb.append("\r\n");
        return sb.toString();
    }

    private static String csv(String value) {
        if (value == null) {
            value = "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String formatDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }
}
