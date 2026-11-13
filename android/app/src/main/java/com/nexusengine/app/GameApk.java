package com.nexusengine.app;

import com.android.apksig.ApkSigner;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

public final class GameApk {
    private static final String TEMPLATE_PACKAGE = "com.nexusengine.templateplaceholder";
    private static final int MAX_GAME_BYTES = 20 * 1024 * 1024;

    public static void build(InputStream template, File unsigned, File output, byte[] html,
                             byte[] icon, String packageId, String label, String version,
                             int build, String orientation, PrivateKey key, X509Certificate cert) throws Exception {
        if (html.length == 0 || html.length > MAX_GAME_BYTES ||
            !new String(html, 0, Math.min(html.length, 64), StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT).startsWith("<!doctype html>")) {
            throw new IllegalArgumentException("Expected a standalone game HTML file up to 20 MB.");
        }
        if (packageId.length() > 120 || !packageId.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+") ||
            packageId.equals("com.nexusengine.app") || packageId.equals(TEMPLATE_PACKAGE) ||
            label.trim().isEmpty() || label.length() > 60 || !version.matches("[0-9]{1,6}(\\.[0-9]{1,6}){2}") ||
            build < 1 || build > 999999999 || icon != null && icon.length > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid APK metadata.");
        }
        int rotation;
        switch (orientation) {
            case "auto": rotation = -1; break;
            case "portrait": rotation = 7; break;
            case "landscape": rotation = 6; break;
            default: throw new IllegalArgumentException("Invalid screen orientation.");
        }
        try (ZipInputStream zip = new ZipInputStream(template);
             ZipOutputStream apk = new ZipOutputStream(new FileOutputStream(unsigned))) {
            ZipEntry entry;
            boolean iconFound = false;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                byte[] bytes = read(zip);
                if (name.equals("AndroidManifest.xml")) bytes = manifest(bytes, packageId, label, version, build, rotation);
                if (name.startsWith("res/") && name.endsWith("/game_icon.png")) {
                    iconFound = true;
                    if (icon != null) bytes = icon;
                }
                put(apk, name, bytes, name.equals("resources.arsc"));
            }
            if (!iconFound) throw new IOException("The player template is missing its launcher icon.");
            put(apk, "assets/game.html", html, false);
        }
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
            "NEXUS", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
            .setInputApk(unsigned)
            .setOutputApk(output)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .setV4SigningEnabled(false)
            .build().sign();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes, boolean stored) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    // The template's binary XML uses indexed strings. Rebuild only the pool, preserving
    // every index used by resource IDs and XML nodes. Update the typed integer fields in place.
    private static byte[] manifest(byte[] original, String pkg, String label, String version, int build, int orientation) throws IOException {
        ByteBuffer xml = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int pool = xml.getShort(2) & 0xffff;
        if (xml.getShort(pool) != 1 || xml.getInt(pool + 12) != 0) throw new IOException("Unsupported manifest string pool.");
        int header = xml.getShort(pool + 2) & 0xffff;
        int oldSize = xml.getInt(pool + 4);
        int count = xml.getInt(pool + 8);
        int flags = xml.getInt(pool + 16);
        boolean utf8 = (flags & 0x100) != 0;
        int dataOffset = xml.getInt(pool + 20);
        String[] names = new String[count];
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        int replacements = 0;
        for (int i = 0; i < count; i++) {
            int pos = pool + dataOffset + xml.getInt(pool + header + i * 4);
            String value = readString(xml, pos, utf8);
            names[i] = value;
            switch (value) {
                case TEMPLATE_PACKAGE: value = pkg; replacements++; break;
                case "NexusPlayerLabelPlaceholder": value = label; replacements++; break;
                case "123456.123456.123456": value = version; replacements++; break;
            }
            offsets[i] = data.size();
            writeString(data, value, utf8);
        }
        if (replacements != 3) throw new IOException("Player manifest placeholders are missing.");
        while (data.size() % 4 != 0) data.write(0);
        int newSize = header + count * 4 + data.size();
        int oldEnd = pool + oldSize;
        int fields = 0;
        for (int pos = oldEnd; pos + 8 <= original.length; pos += xml.getInt(pos + 4)) {
            if (xml.getInt(pos + 4) < 8) throw new IOException("Malformed player manifest.");
            if ((xml.getShort(pos) & 0xffff) != 0x102) continue;
            int ext = pos + (xml.getShort(pos + 2) & 0xffff);
            String element = names[xml.getInt(ext + 4)];
            int first = ext + (xml.getShort(ext + 8) & 0xffff);
            int stride = xml.getShort(ext + 10) & 0xffff;
            int attributes = xml.getShort(ext + 12) & 0xffff;
            for (int i = 0; i < attributes; i++) {
                int attr = first + i * stride;
                String name = names[xml.getInt(attr + 4)];
                if (element.equals("manifest") && name.equals("versionCode")) {
                    xml.putInt(attr + 16, build);
                    fields++;
                } else if (element.equals("activity") && name.equals("screenOrientation")) {
                    xml.putInt(attr + 16, orientation);
                    fields++;
                }
            }
        }
        if (fields != 2) throw new IOException("Player manifest fields are missing.");
        ByteBuffer result = ByteBuffer.allocate(original.length - oldSize + newSize).order(ByteOrder.LITTLE_ENDIAN);
        result.put(original, 0, pool + header);
        result.putInt(4, result.capacity());
        result.putInt(pool + 4, newSize);
        result.putInt(pool + 16, flags & ~1); // Replacements may change the pool's sort order.
        result.putInt(pool + 20, header + count * 4);
        for (int offset : offsets) result.putInt(offset);
        result.put(data.toByteArray());
        result.put(original, oldEnd, original.length - oldEnd);
        return result.array();
    }

    private static String readString(ByteBuffer buffer, int pos, boolean utf8) {
        int length;
        if (utf8) {
            pos += (buffer.get(pos) & 0x80) == 0 ? 1 : 2;
            length = buffer.get(pos++) & 0xff;
            if ((length & 0x80) != 0) length = ((length & 0x7f) << 8) | (buffer.get(pos++) & 0xff);
        } else {
            length = buffer.getShort(pos) & 0xffff;
            pos += 2;
            if ((length & 0x8000) != 0) {
                length = ((length & 0x7fff) << 16) | (buffer.getShort(pos) & 0xffff);
                pos += 2;
            }
            length *= 2;
        }
        return new String(buffer.array(), pos, length, utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
    }

    private static void writeString(ByteArrayOutputStream data, String value, boolean utf8) throws IOException {
        byte[] bytes = value.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
        if (utf8) {
            length8(data, value.length());
            length8(data, bytes.length);
        } else {
            int length = value.length();
            if (length > 0x7fff) {
                data.write(length >>> 16);
                data.write((length >>> 24) | 0x80);
            }
            data.write(length);
            data.write(length >>> 8);
        }
        data.write(bytes);
        data.write(0);
        if (!utf8) data.write(0);
    }

    private static void length8(ByteArrayOutputStream data, int length) {
        if (length > 0x7f) data.write((length >>> 8) | 0x80);
        data.write(length);
    }
}
