package com.andrew.note3lanbackup;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class StorageScanner {
    public static final class Volume {
        public final String id;
        public final String label;
        public final File root;

        Volume(String id, String label, File root) {
            this.id = id;
            this.label = label;
            this.root = root;
        }
    }

    private StorageScanner() {
    }

    public static List<Volume> findVolumes(Context context) {
        ArrayList<Volume> result = new ArrayList<Volume>();
        HashSet<String> canonicalPaths = new HashSet<String>();

        File primary = Environment.getExternalStorageDirectory();
        addVolume(result, canonicalPaths, "primary", "手机内部存储", primary);

        String secondary = System.getenv("SECONDARY_STORAGE");
        if (secondary != null && secondary.length() > 0) {
            String[] paths = secondary.split(":");
            for (String path : paths) {
                addVolume(result, canonicalPaths, "sd" + result.size(), "外置 SD 卡", new File(path));
            }
        }

        try {
            File[] appDirs = context.getExternalFilesDirs(null);
            if (appDirs != null) {
                for (File appDir : appDirs) {
                    File root = deriveVolumeRoot(appDir);
                    if (root != null) {
                        addVolume(result, canonicalPaths, "sd" + result.size(), "外置 SD 卡", root);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        String[] likelyPaths = new String[]{
                "/storage/extSdCard",
                "/storage/sdcard1",
                "/storage/external_SD",
                "/mnt/extSdCard",
                "/mnt/sdcard1",
                "/mnt/external_sd"
        };
        for (String path : likelyPaths) {
            addVolume(result, canonicalPaths, "sd" + result.size(), "外置 SD 卡", new File(path));
        }

        File storageRoot = new File("/storage");
        File[] children = storageRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                String name = child.getName().toLowerCase(Locale.US);
                if ("emulated".equals(name) || "self".equals(name)) {
                    continue;
                }
                addVolume(result, canonicalPaths, "sd" + result.size(), "其他存储", child);
            }
        }

        return result;
    }

    private static File deriveVolumeRoot(File appDir) {
        if (appDir == null) {
            return null;
        }
        File current = appDir;
        while (current != null) {
            if ("Android".equals(current.getName())) {
                return current.getParentFile();
            }
            current = current.getParentFile();
        }
        return null;
    }

    private static void addVolume(List<Volume> result, Set<String> canonicalPaths,
                                  String proposedId, String proposedLabel, File root) {
        if (root == null || !root.exists() || !root.isDirectory() || !root.canRead()) {
            return;
        }
        try {
            String canonical = root.getCanonicalPath();
            if (canonicalPaths.contains(canonical)) {
                return;
            }
            canonicalPaths.add(canonical);
            String id = result.isEmpty() ? "primary" : "sd" + result.size();
            String label = result.isEmpty() ? "手机内部存储" : proposedLabel + " " + result.size();
            result.add(new Volume(id, label, new File(canonical)));
        } catch (IOException ignored) {
        }
    }

    public static Volume findVolume(List<Volume> volumes, String id) {
        if (id == null) {
            return null;
        }
        for (Volume volume : volumes) {
            if (id.equals(volume.id)) {
                return volume;
            }
        }
        return null;
    }

    public static File resolveInside(Volume volume, String relativePath) throws IOException {
        File root = volume.root.getCanonicalFile();
        File target;
        if (relativePath == null || relativePath.length() == 0 || "/".equals(relativePath)) {
            target = root;
        } else {
            while (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            target = new File(root, relativePath).getCanonicalFile();
        }
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException("非法路径");
        }
        return target;
    }

    public static String relativePath(Volume volume, File file) throws IOException {
        String root = volume.root.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (path.equals(root)) {
            return "";
        }
        if (!path.startsWith(root + File.separator)) {
            throw new IOException("文件不在存储卷中");
        }
        return path.substring(root.length() + 1);
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = new String[]{"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }
}
