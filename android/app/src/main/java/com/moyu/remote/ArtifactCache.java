package com.moyu.remote;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Private native cache for image artifacts. HTML receives only a same-origin local URL. */
final class ArtifactCache {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private final File root;

    ArtifactCache(File appCacheDir) {
        root = new File(appCacheDir, "artifacts");
        if ((!root.isDirectory() && !root.mkdirs()) || !root.isDirectory()) {
            throw new IllegalStateException("无法创建图片缓存");
        }
    }

    synchronized File store(String artifactId, byte[] data, String mime, String expectedSha256) throws IOException {
        validateId(artifactId);
        if (data == null || data.length == 0 || data.length > MAX_BYTES) throw new IOException("图片大小无效");
        String extension = extension(mime);
        if (!validMagic(data, mime)) throw new IOException("图片内容与格式不匹配");
        String actualSha256 = sha256(data);
        if (expectedSha256 != null && !expectedSha256.isEmpty() && !actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("图片完整性校验失败");
        }
        File target = new File(root, artifactId + extension);
        File pending = new File(root, artifactId + ".pending");
        try (FileOutputStream output = new FileOutputStream(pending, false)) {
            output.write(data);
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            pending.delete();
            throw new IOException("无法更新图片缓存");
        }
        if (!pending.renameTo(target)) {
            pending.delete();
            throw new IOException("无法写入图片缓存");
        }
        return target;
    }

    synchronized File find(String artifactId) {
        try { validateId(artifactId); } catch (IOException ignored) { return null; }
        for (String suffix : new String[]{".png", ".jpg", ".gif", ".webp"}) {
            File file = new File(root, artifactId + suffix);
            if (file.isFile() && file.length() > 0 && file.length() <= MAX_BYTES) return file;
        }
        return null;
    }

    synchronized InputStream open(String artifactId) throws IOException {
        File file = find(artifactId);
        if (file == null) throw new IOException("图片缓存不存在");
        return new FileInputStream(file);
    }

    synchronized String mime(String artifactId) {
        File file = find(artifactId);
        if (file == null) return "application/octet-stream";
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static void validateId(String value) throws IOException {
        if (!isValidId(value)) throw new IOException("图片标识无效");
    }

    static boolean isValidId(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static String extension(String mime) throws IOException {
        if ("image/png".equals(mime)) return ".png";
        if ("image/jpeg".equals(mime)) return ".jpg";
        if ("image/gif".equals(mime)) return ".gif";
        if ("image/webp".equals(mime)) return ".webp";
        throw new IOException("不支持的图片格式");
    }

    private static boolean validMagic(byte[] data, String mime) {
        if ("image/png".equals(mime)) return data.length >= 8 &&
                (data[0] & 255) == 0x89 && data[1] == 0x50 && data[2] == 0x4e && data[3] == 0x47 &&
                data[4] == 0x0d && data[5] == 0x0a && data[6] == 0x1a && data[7] == 0x0a;
        if ("image/jpeg".equals(mime)) return data.length >= 3 &&
                (data[0] & 255) == 0xff && (data[1] & 255) == 0xd8 && (data[2] & 255) == 0xff;
        if ("image/gif".equals(mime)) return data.length >= 6 && data[0] == 'G' && data[1] == 'I' &&
                data[2] == 'F' && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
        return "image/webp".equals(mime) && data.length >= 12 && data[0] == 'R' && data[1] == 'I' &&
                data[2] == 'F' && data[3] == 'F' && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (Exception error) {
            throw new IOException("无法校验图片", error);
        }
    }
}
