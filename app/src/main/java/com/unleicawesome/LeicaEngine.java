package com.unleicawesome;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public class LeicaEngine {

    static final String RC4_SUFFIX = "niubi_f4d7a2c9e1b3f5a7d2c4e6b8f0a1c3d5e7b9f2a4c6e8b0f1a3c5e7b9f0a2c4d6";

    public static class ContainerInfo {
        public String rawItemName = "";
        public String metaItemName = "";
        public int rawStart;
        public int rawLength;
        public int rawWidth;
        public int rawHeight;
        public int rawStride;
        public int rawOrient;
        public int rawShaLength;
        public int metaStart;
        public int metaLength;
        public int metaShaLength;
        public int primaryShaLength;
        public boolean valid;
    }

    private static class ContainerItem {
        String name = "";
        String mime = "";
        String offsetType = "";
        int start;
        int length;
        int offset;
        int width;
        int height;
        int stride;
        int orient;
        int shaLength;
    }

    public static class FileState {
        public byte[] jpegData;
        public ContainerInfo containerInfo;
        public String originalFilename;
        public byte[] keystream;
        public int[] pixels;

        public Bitmap sourceBitmap;

        public int[] previewSourcePixels;
        public int previewW, previewH;

        public int[] cfa = {2, 1, 1, 0};
        public int cfaColOffset = 0;
        public boolean isTele = false;
        public int rawSize = 0;
        public int rawWidth = 0;
        public int rawHeight = 0;
        public int rawStride = 0;
        public int rawEditWidth = 0;
        public int origColorTemp = 0;
        public int protoOrientation = 0;
        public boolean loaded = false;
        public String statusMsg = "";
    }

    public static byte[] rc4Keystream(byte[] key, int dataLen) {
        int[] S = new int[256];
        for (int i = 0; i < 256; i++) S[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + S[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            int tmp = S[i]; S[i] = S[j]; S[j] = tmp;
        }
        byte[] out = new byte[dataLen];
        int si = 0;
        j = 0;
        for (int k = 0; k < dataLen; k++) {
            si = (si + 1) & 0xFF;
            j = (j + S[si]) & 0xFF;
            int tmp = S[si]; S[si] = S[j]; S[j] = tmp;
            out[k] = (byte) S[(S[si] + S[j]) & 0xFF];
        }
        return out;
    }

    private static int findPattern(byte[] data, int from, String pattern) {
        byte[] pat = pattern.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = from; i <= data.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    public static ContainerInfo parseContainer(byte[] data) {
        ContainerInfo info = new ContainerInfo();

        ContainerItem raw = findSupportedRawItem(data, data.length);
        ContainerItem meta = findMatchingMetaItem(data, data.length, raw);
        ContainerItem primary = findContainerItem(data, "Primary");

        if (raw != null) {
            info.rawItemName = raw.name;
            info.rawLength = raw.length;
            info.rawStart = resolveItemStart(data.length, raw);
            info.rawWidth = raw.width;
            info.rawHeight = raw.height;
            info.rawStride = raw.stride;
            info.rawOrient = raw.orient;
            info.rawShaLength = raw.shaLength;
        }

        if (meta != null) {
            info.metaItemName = meta.name;
            info.metaLength = meta.length;
            info.metaStart = resolveItemStart(data.length, meta);
            info.metaShaLength = meta.shaLength;
        }

        if (primary != null) {
            info.primaryShaLength = primary.shaLength;
        }

        info.valid = isUsableRawItem(data.length, raw, info.rawStart) &&
                isUsableMetaItem(data.length, meta, info.metaStart);
        return info;
    }

    public static boolean hasSupportedRawHeader(byte[] data, int length) {
        int len = Math.max(0, Math.min(length, data.length));
        ContainerItem raw = findSupportedRawItem(data, len);
        ContainerItem meta = findMatchingMetaItem(data, len, raw);
        return looksLikeSupportedRawItem(raw) &&
                meta != null &&
                "meta/protobuf".equalsIgnoreCase(meta.mime);
    }

    private static ContainerItem findSupportedRawItem(byte[] data, int limit) {
        ContainerItem fallback = null;
        int pos = 0;
        while (true) {
            pos = findPattern(data, pos, "<MiContainer:Item");
            if (pos < 0 || pos >= limit) return fallback;
            ContainerItem item = parseContainerItem(data, pos, limit);
            if (looksLikeSupportedRawItem(item)) {
                if (fallback == null) fallback = item;
                if (findMatchingMetaItem(data, limit, item) != null) return item;
            }
            pos += 17;
        }
    }

    private static ContainerItem findMatchingMetaItem(byte[] data, int limit, ContainerItem raw) {
        if (raw == null || raw.name == null || raw.name.isEmpty()) return null;

        ContainerItem named = findContainerItem(data, limit, raw.name + ".meta");
        if (looksLikeMetaItem(named)) return named;

        int pos = 0;
        while (true) {
            pos = findPattern(data, pos, "<MiContainer:Item");
            if (pos < 0 || pos >= limit) return null;
            ContainerItem item = parseContainerItem(data, pos, limit);
            if (looksLikeMetaItem(item)) return item;
            pos += 17;
        }
    }

    private static ContainerItem findContainerItem(byte[] data, String name) {
        return findContainerItem(data, data.length, name);
    }

    private static ContainerItem findContainerItem(byte[] data, int limit, String name) {
        int pos = 0;
        while (true) {
            pos = findPattern(data, pos, "<MiContainer:Item");
            if (pos < 0 || pos >= limit) return null;
            ContainerItem item = parseContainerItem(data, pos, limit);
            if (name.equals(item.name)) return item;
            pos += 17;
        }
    }

    private static ContainerItem parseContainerItem(byte[] data, int pos, int limit) {
        int end = pos;
        int maxEnd = Math.min(data.length, limit);
        while (end < maxEnd && data[end] != '>') end++;
        if (end >= maxEnd) end = maxEnd;

        String itemText = new String(data, pos, end - pos, StandardCharsets.ISO_8859_1);
        ContainerItem item = new ContainerItem();
        item.name = extractAttr(itemText, "MiItem:name");
        item.mime = extractAttr(itemText, "MiItem:Mime");
        item.offsetType = extractAttr(itemText, "MiItem:OffsetType");
        item.length = parsePositiveInt(extractAttr(itemText, "MiItem:length"));
        item.offset = parsePositiveInt(extractAttr(itemText, "MiItem:Offset"));
        item.width = parsePositiveInt(extractAttr(itemText, "MiItem:width"));
        item.height = parsePositiveInt(extractAttr(itemText, "MiItem:height"));
        item.stride = parsePositiveInt(extractAttr(itemText, "MiItem:stride"));
        item.orient = parsePositiveInt(extractAttr(itemText, "MiItem:Orient"));
        item.shaLength = parsePositiveInt(extractAttr(itemText, "MiItem:SHA_length"));
        return item;
    }

    private static String extractAttr(String text, String attr) {
        String needle = attr + "=\"";
        int start = text.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        int end = text.indexOf('"', start);
        if (end < 0) return "";
        return text.substring(start, end);
    }

    private static int parsePositiveInt(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 || parsed > Integer.MAX_VALUE) return 0;
            return (int) parsed;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int resolveItemStart(int fileLength, ContainerItem item) {
        if (item == null || item.offset <= 0) return -1;
        if ("EOF".equalsIgnoreCase(item.offsetType)) return fileLength - item.offset;
        return item.offset;
    }

    private static boolean looksLikeSupportedRawItem(ContainerItem item) {
        if (item == null) return false;
        if (!"image/mipiraw10".equalsIgnoreCase(item.mime)) return false;
        if (item.length <= 0 || item.width <= 0 || item.stride <= 0) return false;
        if (item.width % 4 != 0) return false;
        if (item.stride % 5 != 0) return false;
        int activeRowBytes = item.width * 5 / 4;
        if (item.stride < activeRowBytes) return false;
        if (item.length % item.stride != 0) return false;
        int computedHeight = item.length / item.stride;
        return item.height <= 0 || item.height == computedHeight;
    }

    private static boolean isUsableRawItem(int fileLength, ContainerItem item, int start) {
        return looksLikeSupportedRawItem(item) &&
                start >= 0 &&
                item.length > 0 &&
                start <= fileLength - item.length;
    }

    private static boolean isUsableMetaItem(int fileLength, ContainerItem item, int start) {
        return looksLikeMetaItem(item) &&
                item.length > 0 &&
                start >= 0 &&
                start <= fileLength - item.length;
    }

    private static boolean looksLikeMetaItem(ContainerItem item) {
        return item != null && "meta/protobuf".equalsIgnoreCase(item.mime);
    }

    public static String extractOriginalFilename(byte[] data, ContainerInfo info) {
        if (info.metaStart == 0 || info.metaLength == 0) return "";
        int metaOff = info.metaStart;
        if (info.metaLength > 2 && data[metaOff] == 0x0A) {
            int nameLen = data[metaOff + 1] & 0xFF;
            if (nameLen <= 0 || nameLen > info.metaLength - 2) return "";
            byte[] nameBytes = new byte[nameLen];
            System.arraycopy(data, metaOff + 2, nameBytes, 0, nameLen);

            int end = nameLen;
            while (end > 0 && nameBytes[end - 1] == 0) end--;
            return new String(nameBytes, 0, end);
        }
        return "";
    }

    public static String makeKey(String filename) {
        return "xiaomi" + filename + RC4_SUFFIX;
    }

    public static int[] decodeRaw10(byte[] raw, int width, int height, int stride) {
        int[] pixels = new int[width * height];
        for (int row = 0; row < height; row++) {
            int rowOff = row * stride;
            for (int g = 0; g < width / 4; g++) {
                int o = rowOff + g * 5;
                int b0 = raw[o] & 0xFF, b1 = raw[o + 1] & 0xFF, b2 = raw[o + 2] & 0xFF;
                int b3 = raw[o + 3] & 0xFF, b4 = raw[o + 4] & 0xFF;
                int c = g * 4;
                pixels[row * width + c]     = (b0 << 2) | ((b4 >> 0) & 3);
                pixels[row * width + c + 1] = (b1 << 2) | ((b4 >> 2) & 3);
                pixels[row * width + c + 2] = (b2 << 2) | ((b4 >> 4) & 3);
                pixels[row * width + c + 3] = (b3 << 2) | ((b4 >> 6) & 3);
            }
        }
        return pixels;
    }

    public static String sha256hex(byte[] data, int offset, int len) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data, offset, len);
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static float[] kelvinToRGB(int kelvin) {
        float t = kelvin / 100.0f;
        float r, g, b;

        if (t <= 66.0f)
            r = 1.0f;
        else
            r = 1.292936f * (float) Math.pow(t - 60.0f, -0.1332047f);

        if (t <= 66.0f)
            g = 0.390082f * (float) Math.log(t) - 0.631841f;
        else
            g = 1.129891f * (float) Math.pow(t - 60.0f, -0.0755148f);

        if (t >= 66.0f)
            b = 1.0f;
        else if (t <= 19.0f)
            b = 0.0f;
        else
            b = 0.543206f * (float) Math.log(t - 10.0f) - 1.196255f;

        float t65 = 65.0f;
        float r65 = 1.0f;
        float g65 = 0.390082f * (float) Math.log(t65) - 0.631841f;
        float b65 = 0.543206f * (float) Math.log(t65 - 10.0f) - 1.196255f;

        r /= r65;
        g /= g65;
        b /= b65;

        r = Math.max(r, 0.01f);
        g = Math.max(g, 0.01f);
        b = Math.max(b, 0.01f);

        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        if (lum > 0.001f) {
            r /= lum;
            g /= lum;
            b /= lum;
        }

        return new float[]{r, g, b};
    }

    public static float[] applyTint(float tint, float r, float g, float b) {
        float gStrength = (tint > 0) ? 0.8f : 0.5f;
        float oldLum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        g *= (1.0f + tint * gStrength);
        r *= (1.0f - tint * 0.15f);
        b *= (1.0f - tint * 0.15f);
        float newLum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        if (newLum > 0.001f) {
            float scale = oldLum / newLum;
            r *= scale;
            g *= scale;
            b *= scale;
        }
        return new float[]{r, g, b};
    }

    public static float sliderToEV(int val) { return (val - 500) / 500.0f; }
    public static int sliderToKelvin(int val) { return val * 10; }
    public static float sliderToTint(int val) { return (val - 500) / 500.0f; }

    public static float[] getWBMultipliers(int sliderKelvin, int sliderTint) {
        int kelvin = sliderToKelvin(sliderKelvin);
        float[] rgb = kelvinToRGB(kelvin);
        float tint = sliderToTint(sliderTint);
        return applyTint(tint, rgb[0], rgb[1], rgb[2]);
    }

    public static int extractExifOrientation(byte[] jpeg) {
        int len = jpeg.length;
        if (len < 12 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return 1;
        int pos = 2;
        while (pos + 4 < len) {
            if ((jpeg[pos] & 0xFF) != 0xFF) break;
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA) break;
            int segLen = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (segLen < 2 || pos + 2 + segLen > len) break;
            if (marker == 0xE1) {
                if (pos + 10 < len &&
                    jpeg[pos + 4] == 'E' && jpeg[pos + 5] == 'x' &&
                    jpeg[pos + 6] == 'i' && jpeg[pos + 7] == 'f' &&
                    jpeg[pos + 8] == 0 && jpeg[pos + 9] == 0) {
                    int tiffOff = pos + 10;
                    int tiffLen = segLen - 8;
                    boolean le = (jpeg[tiffOff] == 'I' && jpeg[tiffOff + 1] == 'I');
                    int ifdOff;
                    if (le) {
                        ifdOff = (jpeg[tiffOff + 4] & 0xFF) | ((jpeg[tiffOff + 5] & 0xFF) << 8) |
                                 ((jpeg[tiffOff + 6] & 0xFF) << 16) | ((jpeg[tiffOff + 7] & 0xFF) << 24);
                    } else {
                        ifdOff = ((jpeg[tiffOff + 4] & 0xFF) << 24) | ((jpeg[tiffOff + 5] & 0xFF) << 16) |
                                 ((jpeg[tiffOff + 6] & 0xFF) << 8) | (jpeg[tiffOff + 7] & 0xFF);
                    }
                    if (ifdOff + 2 < tiffLen) {
                        int numEntries;
                        if (le)
                            numEntries = (jpeg[tiffOff + ifdOff] & 0xFF) | ((jpeg[tiffOff + ifdOff + 1] & 0xFF) << 8);
                        else
                            numEntries = ((jpeg[tiffOff + ifdOff] & 0xFF) << 8) | (jpeg[tiffOff + ifdOff + 1] & 0xFF);
                        for (int i = 0; i < numEntries && ifdOff + 2 + i * 12 + 12 <= tiffLen; i++) {
                            int e = ifdOff + 2 + i * 12;
                            int tag;
                            if (le)
                                tag = (jpeg[tiffOff + e] & 0xFF) | ((jpeg[tiffOff + e + 1] & 0xFF) << 8);
                            else
                                tag = ((jpeg[tiffOff + e] & 0xFF) << 8) | (jpeg[tiffOff + e + 1] & 0xFF);
                            if (tag == 0x0112) {
                                int val;
                                if (le)
                                    val = (jpeg[tiffOff + e + 8] & 0xFF) | ((jpeg[tiffOff + e + 9] & 0xFF) << 8);
                                else
                                    val = ((jpeg[tiffOff + e + 8] & 0xFF) << 8) | (jpeg[tiffOff + e + 9] & 0xFF);
                                return val;
                            }
                        }
                    }
                }
            }
            pos += 2 + segLen;
        }
        return 1;
    }

    public static Bitmap applyExifOrientation(Bitmap bmp, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 3: matrix.postRotate(180); break;
            case 6: matrix.postRotate(90); break;
            case 8: matrix.postRotate(270); break;
            case 2: matrix.postScale(-1, 1); break;
            case 4: matrix.postScale(1, -1); break;
            case 5: matrix.postRotate(90); matrix.postScale(-1, 1); break;
            case 7: matrix.postRotate(270); matrix.postScale(-1, 1); break;
            default: return bmp;
        }
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }

    public static int[][] buildPreviewLUTs(float wbR, float wbG, float wbB, float ev,
                                            int origColorTemp, int sliderBias) {

        if (origColorTemp > 0) {
            int biasTemp = sliderBias * 1000;
            float deviation = (float) (biasTemp - origColorTemp) / 10000.0f;
            wbR *= (1.0f - deviation * 0.15f);
            wbB *= (1.0f + deviation * 0.15f);
        }

        float expMul = (ev < 0) ? (float) Math.pow(2.0, ev * 1.5) : (float) Math.pow(2.0, ev);
        float ampR = 4.0f;
        float ampB = (wbB > 1.0f) ? 5.5f : 4.0f;
        float aR = (float) Math.pow(wbR, ampR) * expMul;
        float aG = (float) Math.pow(wbG, 4.0f) * expMul;
        float aB = (float) Math.pow(wbB, ampB) * expMul;

        int[] lutR = new int[256];
        int[] lutG = new int[256];
        int[] lutB = new int[256];

        for (int i = 0; i < 256; i++) {
            float v = i / 255.0f;
            float lin = (v <= 0.04045f) ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
            float rr = softClipLin(lin * aR);
            float gg = softClipLin(lin * aG);
            float bb = softClipLin(lin * aB);

            lutR[i] = (int) (toSrgb(rr) * 255.0f);
            lutG[i] = (int) (toSrgb(gg) * 255.0f);
            lutB[i] = (int) (toSrgb(bb) * 255.0f);
        }

        return new int[][]{lutR, lutG, lutB};
    }

    private static float toSrgb(float x) {
        x = Math.max(0, Math.min(1, x));
        return (x <= 0.0031308f) ? 12.92f * x : 1.055f * (float) Math.pow(x, 1.0f / 2.4f) - 0.055f;
    }

    private static float softClipLin(float v) {
        if (v <= 0.6f) return v;
        float over = (v - 0.6f) / 0.4f;
        return 0.6f + 0.4f * (1.0f - (float) Math.exp(-over));
    }

    private static float softClip10(float v) {
        if (v <= 614.0f) return v;
        float over = (v - 614.0f) / 409.0f;
        return 614.0f + 409.0f * (1.0f - (float) Math.exp(-over));
    }

    public static Bitmap applyLutToPreview(int[] sourcePixels, int srcW, int srcH,
                                            int[][] luts) {
        int[] lutR = luts[0], lutG = luts[1], lutB = luts[2];
        int[] outPixels = new int[srcW * srcH];

        for (int i = 0; i < srcW * srcH; i++) {
            int pixel = sourcePixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            int a = (pixel >> 24) & 0xFF;
            outPixels[i] = (a << 24) | (lutR[r] << 16) | (lutG[g] << 8) | lutB[b];
        }

        return Bitmap.createBitmap(outPixels, srcW, srcH, Bitmap.Config.ARGB_8888);
    }

    public static FileState loadFile(byte[] data) {
        FileState state = new FileState();
        state.jpegData = data;

        state.statusMsg = "Parsing container...";
        state.containerInfo = parseContainer(data);
        if (!state.containerInfo.valid) {
            state.statusMsg = "ERROR: Not a Leica MiContainer JPEG!";
            return state;
        }

        state.originalFilename = extractOriginalFilename(data, state.containerInfo);
        if (state.originalFilename == null || state.originalFilename.isEmpty()) {
            state.statusMsg = "ERROR: Cannot extract filename from metadata!";
            return state;
        }

        state.statusMsg = "Detecting sensor...";
        float zoom = extractZoomMultiple(data);

        if (zoom >= 0.9f && zoom <= 1.1f) {

            state.cfa = new int[]{2, 1, 1, 0};
            state.cfaColOffset = 0;
            state.isTele = false;
        } else if (zoom < 0.9f) {

            state.cfa = new int[]{1, 0, 2, 1};
            state.cfaColOffset = 0;
            state.isTele = false;
        } else {

            state.cfa = new int[]{2, 1, 1, 0};
            state.cfaColOffset = 0;
            state.isTele = false;
        }

        state.rawSize = state.containerInfo.rawLength;
        state.rawWidth = state.containerInfo.rawWidth;
        state.rawStride = state.containerInfo.rawStride;
        state.rawHeight = state.rawStride > 0 ? state.rawSize / state.rawStride : 0;

        if (!validateRawGeometry(state.rawSize, state.rawWidth, state.rawHeight,
                state.rawStride, state.containerInfo.rawHeight)) {
            state.statusMsg = "ERROR: Unsupported Leica RAW dimensions!";
            return state;
        }
        state.rawStride = effectivePackedRawStride(state.rawWidth, state.rawStride, state.rawSize);
        state.rawHeight = state.rawSize / state.rawStride;

        readProtobufFields(data, state);

        state.statusMsg = "Generating keystream...";
        String key = makeKey(state.originalFilename);
        state.keystream = rc4Keystream(key.getBytes(), state.rawSize);

        state.statusMsg = "Decrypting RAW...";
        byte[] decrypted = new byte[state.rawSize];
        int rawOff = state.containerInfo.rawStart;
        for (int i = 0; i < state.rawSize; i++)
            decrypted[i] = (byte) ((data[rawOff + i] & 0xFF) ^ (state.keystream[i] & 0xFF));

        state.statusMsg = "Decoding MIPI RAW10...";
        state.pixels = decodeRaw10(decrypted, state.rawWidth, state.rawHeight, state.rawStride);
        state.rawEditWidth = detectRawEditWidth(decrypted, state.rawWidth, state.rawHeight, state.rawStride);

        state.statusMsg = "Decoding preview...";
        int orientation = extractExifOrientation(data);

        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 2;
        Bitmap preview = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (preview != null) {
            preview = applyExifOrientation(preview, orientation);
            state.previewW = preview.getWidth();
            state.previewH = preview.getHeight();
            state.previewSourcePixels = new int[state.previewW * state.previewH];
            preview.getPixels(state.previewSourcePixels, 0, state.previewW, 0, 0,
                    state.previewW, state.previewH);
            preview.recycle();
        }

        Bitmap fullRes = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        if (fullRes != null) {
            state.sourceBitmap = applyExifOrientation(fullRes, orientation);
        }

        state.loaded = true;
        state.statusMsg = String.format("RAW: %dx%d stride=%d edit=%d zoom=%.1f temp=%dK",
                state.rawWidth, state.rawHeight, state.rawStride, state.rawEditWidth,
                zoom, state.origColorTemp);
        return state;
    }

    private static boolean validateRawGeometry(int rawSize, int width, int height,
                                               int stride, int declaredHeight) {
        if (rawSize <= 0 || width <= 0 || height <= 0 || stride <= 0) return false;
        if (width < 16 || width > 20000 || height > 20000) return false;
        if (width % 4 != 0) return false;
        if (stride % 5 != 0) return false;
        int activeRowBytes = width * 5 / 4;
        if (stride < activeRowBytes) return false;
        if (rawSize % stride != 0) return false;
        if (declaredHeight > 0 && declaredHeight != height) return false;
        return true;
    }

    public static int effectivePackedRawStride(int width, int declaredStride, int rawSize) {
        if (width <= 0 || width % 4 != 0) return declaredStride;
        int packedStride = width * 5 / 4;
        if (packedStride <= 0 || packedStride > declaredStride || rawSize < packedStride) {
            return declaredStride;
        }
        return packedStride;
    }

    private static int detectRawEditWidth(byte[] raw, int width, int height, int stride) {
        if (raw == null || width <= 0 || height <= 0 || stride <= 0 || stride % 5 != 0) {
            return width;
        }

        return (stride / 5) * 4;
    }

    private static float extractZoomMultiple(byte[] data) {
        int pos = findPattern(data, 0, "zoomMultiple\":");
        if (pos < 0) return 1.0f;
        pos += "zoomMultiple\":".length();

        StringBuilder sb = new StringBuilder();
        while (pos < data.length) {
            byte b = data[pos++];
            if ((b >= '0' && b <= '9') || b == '.') {
                sb.append((char) b);
            } else if (sb.length() > 0) {
                break;
            }
        }

        if (sb.length() == 0) return 1.0f;
        try {
            return Float.parseFloat(sb.toString());
        } catch (NumberFormatException ignored) {
            return 1.0f;
        }
    }

    private static void readProtobufFields(byte[] data, FileState state) {
        if (state.containerInfo.metaStart <= 0 || state.containerInfo.metaLength <= 0) return;
        int metaOff = state.containerInfo.metaStart;
        int metaLen = state.containerInfo.metaLength;
        int[] pos = {0};

        state.origColorTemp = 0;
        state.protoOrientation = 0;

        while (pos[0] < metaLen) {
            long tag = readVarint(data, metaOff, metaLen, pos);
            int fn = (int) (tag >> 3);
            int wt = (int) (tag & 7);

            if (wt == 0) {
                long val = readVarint(data, metaOff, metaLen, pos);
                if (fn == 11) state.protoOrientation = (int) val;
            } else if (wt == 1) {
                pos[0] += 8;
            } else if (wt == 2) {
                long l = readVarint(data, metaOff, metaLen, pos);
                pos[0] += (int) l;
            } else if (wt == 5) {
                if (fn == 19 && pos[0] + 4 <= metaLen) {
                    int bits = (data[metaOff + pos[0]] & 0xFF) |
                               ((data[metaOff + pos[0] + 1] & 0xFF) << 8) |
                               ((data[metaOff + pos[0] + 2] & 0xFF) << 16) |
                               ((data[metaOff + pos[0] + 3] & 0xFF) << 24);
                    state.origColorTemp = (int) Float.intBitsToFloat(bits);
                }
                pos[0] += 4;
            } else {
                break;
            }
        }
    }

    private static long readVarint(byte[] data, int base, int len, int[] pos) {
        long result = 0;
        int shift = 0;
        while (pos[0] < len) {
            int b = data[base + pos[0]++] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    public static byte[] doExport(FileState state, int sliderKelvin, int sliderTint,
                                   int sliderExp, int sliderBias) {
        float[] wb = getWBMultipliers(sliderKelvin, sliderTint);
        float wbR = wb[0], wbG = wb[1], wbB = wb[2];
        float expMul = (float) Math.pow(2.0, sliderToEV(sliderExp));

        float[] chanMul = {wbR, wbG, wbB};
        float[] cfaMul = new float[4];
        for (int i = 0; i < 4; i++) cfaMul[i] = chanMul[state.cfa[i]];

        int rawSize = state.rawSize;
        byte[] rawBytes = new byte[rawSize];
        int rawOff = state.containerInfo.rawStart;
        for (int i = 0; i < rawSize; i++)
            rawBytes[i] = (byte) ((state.jpegData[rawOff + i] & 0xFF) ^ (state.keystream[i] & 0xFF));

        int editWidth = state.rawEditWidth > 0 ? state.rawEditWidth : state.rawWidth;
        int packedWidth = (state.rawStride / 5) * 4;
        editWidth = Math.min(editWidth, packedWidth);
        int activeGroups = editWidth / 4;
        int actualStride = state.rawStride;
        int actualHeight = state.rawHeight;

        for (int row = 0; row < actualHeight; row++) {
            int rowOff2 = row * actualStride;
            int rowIdx = (row & 1) * 2;

            for (int grp = 0; grp < activeGroups; grp++) {
                int o = rowOff2 + grp * 5;
                int col = grp * 4;

                int b0 = rawBytes[o] & 0xFF, b1 = rawBytes[o + 1] & 0xFF;
                int b2 = rawBytes[o + 2] & 0xFF, b3 = rawBytes[o + 3] & 0xFF;
                int b4 = rawBytes[o + 4] & 0xFF;

                int p0 = (b0 << 2) | ((b4 >> 0) & 3);
                int p1 = (b1 << 2) | ((b4 >> 2) & 3);
                int p2 = (b2 << 2) | ((b4 >> 4) & 3);
                int p3 = (b3 << 2) | ((b4 >> 6) & 3);

                float m0 = cfaMul[rowIdx + ((col) & 1)] * expMul;
                float m1 = cfaMul[rowIdx + ((col + 1) & 1)] * expMul;
                float m2 = cfaMul[rowIdx + ((col + 2) & 1)] * expMul;
                float m3 = cfaMul[rowIdx + ((col + 3) & 1)] * expMul;

                p0 = (int) Math.max(softClip10(p0 * m0), 0.0f);
                p1 = (int) Math.max(softClip10(p1 * m1), 0.0f);
                p2 = (int) Math.max(softClip10(p2 * m2), 0.0f);
                p3 = (int) Math.max(softClip10(p3 * m3), 0.0f);

                rawBytes[o]     = (byte) ((p0 >> 2) & 0xFF);
                rawBytes[o + 1] = (byte) ((p1 >> 2) & 0xFF);
                rawBytes[o + 2] = (byte) ((p2 >> 2) & 0xFF);
                rawBytes[o + 3] = (byte) ((p3 >> 2) & 0xFF);
                rawBytes[o + 4] = (byte) ((p0 & 3) | ((p1 & 3) << 2) | ((p2 & 3) << 4) | ((p3 & 3) << 6));
            }
        }

        for (int i = 0; i < rawSize; i++)
            rawBytes[i] = (byte) ((rawBytes[i] & 0xFF) ^ (state.keystream[i] & 0xFF));

        byte[] result = Arrays.copyOf(state.jpegData, state.jpegData.length);

        if (state.sourceBitmap != null) {
            replaceJpegPreview(result, state, sliderKelvin, sliderTint, sliderExp, sliderBias, wbR, wbG, wbB);
        }

        System.arraycopy(rawBytes, 0, result, state.containerInfo.rawStart, rawSize);

        int rawShaLength = declaredShaLength(state.containerInfo.rawShaLength, rawSize);
        String newSha = sha256hex(result, state.containerInfo.rawStart, rawShaLength);
        replaceShaInXmp(result, itemNameMarker(state.containerInfo.rawItemName), newSha);

        updatePrimarySha(result, state.containerInfo.primaryShaLength);

        int biasTemp = sliderBias * 1000;
        if (biasTemp != state.origColorTemp && state.origColorTemp > 0 && state.containerInfo.metaStart > 0) {
            patchProtobufField19(result, state.containerInfo, biasTemp);

            int metaShaLength = declaredShaLength(state.containerInfo.metaShaLength,
                    state.containerInfo.metaLength);
            String metaSha = sha256hex(result, state.containerInfo.metaStart, metaShaLength);
            replaceShaInXmp(result, itemNameMarker(state.containerInfo.metaItemName), metaSha);
        }

        updateExifDates(result);

        return result;
    }

    private static void replaceJpegPreview(byte[] result, FileState state,
                                            int sliderKelvin, int sliderTint, int sliderExp, int sliderBias,
                                            float wbR, float wbG, float wbB) {
        byte[] orig = state.jpegData;

        int imgStart = findPrimaryImageDataStart(orig);
        int primaryEnd = findPrimaryJpegEnd(orig);
        if (imgStart <= 0 || primaryEnd <= imgStart) return;

        Bitmap src = state.sourceBitmap;
        int w = src.getWidth(), h = src.getHeight();
        int[] srcPixels = new int[w * h];
        src.getPixels(srcPixels, 0, w, 0, 0, w, h);

        float eEv = sliderToEV(sliderExp);
        float eExp = (eEv < 0) ? (float) Math.pow(2.0, eEv * 1.5) : (float) Math.pow(2.0, eEv);
        float eAR = (float) Math.pow(wbR, 4.0f) * eExp;
        float eAG = (float) Math.pow(wbG, 4.0f) * eExp;
        float eAB = (float) Math.pow(wbB, (wbB > 1.0f) ? 5.5f : 4.0f) * eExp;

        int[] eLR = new int[256], eLG = new int[256], eLB = new int[256];
        for (int i = 0; i < 256; i++) {
            float v = i / 255.0f;
            float lin = (v <= 0.04045f) ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
            eLR[i] = (int) (toSrgb(softClipLin(lin * eAR)) * 255.0f);
            eLG[i] = (int) (toSrgb(softClipLin(lin * eAG)) * 255.0f);
            eLB[i] = (int) (toSrgb(softClipLin(lin * eAB)) * 255.0f);
        }

        int[] outPixels = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int pixel = srcPixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            outPixels[i] = 0xFF000000 | (eLR[r] << 16) | (eLG[g] << 8) | eLB[b];
        }

        Bitmap editedBmp = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        editedBmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        editedBmp.recycle();
        byte[] newJpegData = baos.toByteArray();

        int newImgStart = findPrimaryImageDataStart(newJpegData);
        int newEoi = findPrimaryJpegEnd(newJpegData);
        if (newImgStart <= 0 || newEoi <= newImgStart) return;

        int newDataLen = newEoi - newImgStart;
        int origDataLen = primaryEnd - imgStart;

        if (newDataLen <= origDataLen) {
            System.arraycopy(newJpegData, newImgStart, result, imgStart, newDataLen);
            if (newDataLen < origDataLen) {
                int fillStart = imgStart + newDataLen - 2;
                int fillEnd = imgStart + origDataLen - 2;
                for (int i = fillStart; i < fillEnd; i++)
                    result[i] = (byte) 0xFF;
                result[fillEnd] = (byte) 0xFF;
                result[fillEnd + 1] = (byte) 0xD9;
            }
        }
    }

    private static String itemNameMarker(String itemName) {
        return itemName == null || itemName.isEmpty() ? "" : "name=\"" + itemName + "\"";
    }

    private static void replaceShaInXmp(byte[] data, String marker, String newSha) {
        if (marker == null || marker.isEmpty()) return;
        int mpos = findPattern(data, 0, marker);
        if (mpos < 0) return;
        int itemEnd = findByte(data, mpos, (byte) '>');
        if (itemEnd < 0) return;
        int shaPos = findPattern(data, mpos, "SHA=\"");
        if (shaPos < 0) {
            shaPos = findPattern(data, mpos, "Sha=\"");
        }
        if (shaPos >= 0 && shaPos < itemEnd) {

            int quoteStart = shaPos;
            while (quoteStart < data.length && data[quoteStart] != '"') quoteStart++;
            quoteStart++;
            byte[] shaBytes = newSha.getBytes();
            System.arraycopy(shaBytes, 0, data, quoteStart, 64);
        }
    }

    private static int findByte(byte[] data, int from, byte value) {
        for (int i = Math.max(0, from); i < data.length; i++) {
            if (data[i] == value) return i;
        }
        return -1;
    }

    private static int findPrimaryImageDataStart(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return -1;
        }

        int pos = 2;
        while (pos + 3 < jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) return -1;
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDB || marker == 0xC0 || marker == 0xC2 ||
                    marker == 0xC4 || marker == 0xDA) {
                return pos;
            }
            if (marker == 0xD9) return -1;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            int segLen = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (segLen < 2 || pos + 2 + segLen > jpeg.length) return -1;
            pos += 2 + segLen;
        }
        return -1;
    }

    private static int findStartOfScan(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return -1;
        }

        int pos = 2;
        while (pos + 3 < jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) return -1;
            int marker = jpeg[pos + 1] & 0xFF;
            if (marker == 0xDA) return pos;
            if (marker == 0xD9) return -1;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            int segLen = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (segLen < 2 || pos + 2 + segLen > jpeg.length) return -1;
            pos += 2 + segLen;
        }
        return -1;
    }

    private static int findPrimaryJpegEnd(byte[] jpeg) {
        int sos = findStartOfScan(jpeg);
        if (sos < 0 || sos + 4 > jpeg.length) return -1;
        int sosLen = ((jpeg[sos + 2] & 0xFF) << 8) | (jpeg[sos + 3] & 0xFF);
        int pos = sos + 2 + sosLen;
        if (sosLen < 2 || pos > jpeg.length) return -1;

        while (pos + 1 < jpeg.length) {
            if ((jpeg[pos] & 0xFF) == 0xFF) {
                int marker = jpeg[pos + 1] & 0xFF;
                if (marker == 0x00) {
                    pos += 2;
                    continue;
                }
                if (marker >= 0xD0 && marker <= 0xD7) {
                    pos += 2;
                    continue;
                }
                if (marker == 0xD9) return pos + 2;
            }
            pos++;
        }
        return -1;
    }

    private static int declaredShaLength(int declared, int payloadLength) {
        int fallback = Math.min(1000, payloadLength);
        if (declared <= 0) return fallback;
        return Math.min(declared, payloadLength);
    }

    private static void updatePrimarySha(byte[] result, int declaredShaLength) {
        int sosPos = findStartOfScan(result);
        if (sosPos > 0) {
            int primaryEnd = findPrimaryJpegEnd(result);
            int defaultShaLen = primaryEnd > sosPos
                    ? primaryEnd - sosPos
                    : result.length - sosPos;
            int shaLen = declaredShaLength > 0
                    ? Math.min(declaredShaLength, result.length - sosPos)
                    : defaultShaLen;
            String primarySha = sha256hex(result, sosPos, shaLen);

            int priPos = findPattern(result, 0, "name=\"Primary\"");
            if (priPos >= 0) {

                int itemEnd = findByte(result, priPos, (byte) '>');
                if (itemEnd < 0) return;
                int sp = findPattern(result, priPos, "SHA=\"");
                if (sp < 0) sp = findPattern(result, priPos, "Sha=\"");
                if (sp >= 0 && sp < itemEnd) {
                    while (sp < result.length && result[sp] != '"') sp++;
                    sp++;
                    System.arraycopy(primarySha.getBytes(), 0, result, sp, 64);
                }
            }
        }
    }

    private static void patchProtobufField19(byte[] result, ContainerInfo info, int newTemp) {
        int metaOff = info.metaStart;
        int metaLen = info.metaLength;
        int[] pos = {0};

        while (pos[0] < metaLen) {
            long tag = readVarint(result, metaOff, metaLen, pos);
            int fn = (int) (tag >> 3);
            int wt = (int) (tag & 7);

            if (wt == 0) { readVarint(result, metaOff, metaLen, pos); }
            else if (wt == 1) { pos[0] += 8; }
            else if (wt == 2) { long l = readVarint(result, metaOff, metaLen, pos); pos[0] += (int) l; }
            else if (wt == 5) {
                if (fn == 19) {
                    int bits = Float.floatToIntBits((float) newTemp);
                    result[metaOff + pos[0]]     = (byte) (bits & 0xFF);
                    result[metaOff + pos[0] + 1] = (byte) ((bits >> 8) & 0xFF);
                    result[metaOff + pos[0] + 2] = (byte) ((bits >> 16) & 0xFF);
                    result[metaOff + pos[0] + 3] = (byte) ((bits >> 24) & 0xFF);
                }
                pos[0] += 4;
            } else break;
        }
    }

    private static void updateExifDates(byte[] result) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String newDate = String.format("%04d:%02d:%02d %02d:%02d:%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
        byte[] dateBytes = newDate.getBytes(StandardCharsets.ISO_8859_1);

        int sos = findStartOfScan(result);
        int limit = (sos > 0 ? sos : result.length) - 19;
        for (int i = 0; i < limit; i++) {
            if (result[i] == '2' && result[i + 1] == '0' &&
                result[i + 4] == ':' && result[i + 7] == ':' &&
                result[i + 10] == ' ' && result[i + 13] == ':' &&
                result[i + 16] == ':' && result[i + 19] == 0) {
                System.arraycopy(dateBytes, 0, result, i, 19);
            }
        }
    }

    public static String buildParamSuffix(int sliderKelvin, int sliderTint, int sliderExp,
                                           int sliderBias, int origColorTemp) {
        StringBuilder sb = new StringBuilder();
        int kelvin = sliderToKelvin(sliderKelvin);
        float tint = sliderToTint(sliderTint);
        float ev = sliderToEV(sliderExp);
        int bias = sliderBias * 1000;

        if (kelvin != 6500) sb.append("_wb").append(kelvin);
        if (tint < -0.01f || tint > 0.01f) sb.append(String.format("_t%+.0f", tint * 100));
        if (ev < -0.01f || ev > 0.01f) sb.append(String.format("_ev%+.0f", ev * 100));
        int defaultBias = ((origColorTemp + 500) / 1000) * 1000;
        if (defaultBias < 1000) defaultBias = 5000;
        if (bias != defaultBias) sb.append("_bias").append(bias);
        if (sb.length() == 0) sb.append("_edited");

        return sb.toString();
    }
}
