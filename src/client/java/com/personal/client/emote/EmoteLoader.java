package com.personal.client.emote;

import com.mojang.blaze3d.platform.NativeImage;
import com.personal.ChatMod;
import com.personal.emote.Emote;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class EmoteLoader {

    /** Emote textures are normalized to this height; width follows the source aspect ratio. */
    public static final int EMOTE_HEIGHT = 16;
    /** Widest accepted texture (4:1) so extreme 7TV wide emotes can't blow up chat lines. */
    private static final int MAX_EMOTE_WIDTH = EMOTE_HEIGHT * 4;
    private static final int FRAME_SAMPLE_LIMIT = 64;   // cap GIF frames to avoid OOM
    /** Maximum bytes accepted from any single emote download (4 MB). */
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;
    /** Maximum pixel dimension accepted before refusing to decode (avoids decompression bombs). */
    private static final int MAX_IMAGE_DIMENSION = 4096;

    /** Decoded emote: frames plus the delay between them (0 for static emotes). */
    public record Frames(NativeImage[] images, long frameDurationMs) {}

    private interface PixelGetter {
        /** Returns the ARGB color at (x, y). */
        int argb(int x, int y);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Path cacheDir;

    public EmoteLoader(Path cacheDir) {
        this.cacheDir = cacheDir;
        try { Files.createDirectories(cacheDir); } catch (IOException ignored) {}
    }

    /**
     * Downloads (if needed) and decodes an emote into one or more frames.
     * Animated GIFs return multiple frames; everything else returns a single-frame result.
     * Returns null on failure.
     */
    public Frames loadFrames(Emote emote) {
        Path cached = cacheDir.resolve(emote.source().name() + "_" + sanitize(emote.id()) + ".bin");
        try {
            byte[] bytes;
            if (Files.exists(cached)) {
                bytes = Files.readAllBytes(cached);
                try {
                    return decodeFrames(bytes);
                } catch (Exception e) {
                    // Cached file is corrupt — delete it and re-download once.
                    Files.deleteIfExists(cached);
                    bytes = downloadAndCache(emote.imageUrl(), cached);
                }
            } else {
                bytes = downloadAndCache(emote.imageUrl(), cached);
            }
            return decodeFrames(bytes);
        } catch (Exception e) {
            ChatMod.LOGGER.warn("[ChatMod] Failed to load emote {}: {}", emote.code(), e.getMessage());
            return null;
        }
    }

    private byte[] downloadAndCache(String url, Path dest) throws Exception {
        HttpResponse<byte[]> resp = http.send(
                HttpRequest.newBuilder().uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "chatmod/1.0")
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        byte[] bytes = resp.body();
        if (bytes.length > MAX_DOWNLOAD_BYTES) {
            throw new IOException("Response too large: " + bytes.length + " bytes from " + url);
        }
        Files.write(dest, bytes);
        return bytes;
    }

    private Frames decodeFrames(byte[] bytes) throws IOException {
        if (isGif(bytes)) {
            Frames gif = readGifFrames(bytes);
            if (gif != null && gif.images().length > 0) return gif;
        }
        // PNG or WebP (static)
        NativeImage single = decodeSingle(bytes);
        return single != null ? new Frames(new NativeImage[]{single}, 0) : null;
    }

    private NativeImage decodeSingle(byte[] bytes) throws IOException {
        if (isWebP(bytes)) {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi == null) throw new IOException("ImageIO could not decode WebP image");
            if (bi.getWidth() > MAX_IMAGE_DIMENSION || bi.getHeight() > MAX_IMAGE_DIMENSION) {
                throw new IOException("WebP dimensions exceed limit");
            }
            return resample(bi.getWidth(), bi.getHeight(), bi::getRGB,
                    targetWidth(bi.getWidth(), bi.getHeight()), EMOTE_HEIGHT);
        }
        // PNG: NativeImage decodes natively; getPixel returns ARGB.
        NativeImage raw = NativeImage.read(new ByteArrayInputStream(bytes));
        if (raw.getWidth() > MAX_IMAGE_DIMENSION || raw.getHeight() > MAX_IMAGE_DIMENSION) {
            raw.close();
            throw new IOException("PNG dimensions exceed limit");
        }
        NativeImage out = resample(raw.getWidth(), raw.getHeight(), raw::getPixel,
                targetWidth(raw.getWidth(), raw.getHeight()), EMOTE_HEIGHT);
        raw.close();
        return out;
    }

    /**
     * Decodes an animated GIF, compositing delta frames onto a persistent canvas and
     * honoring per-frame offsets and disposal methods, so optimized GIFs animate correctly.
     */
    private Frames readGifFrames(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType("image/gif");
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            reader.setInput(iis, false, false);
            try {
                int count = reader.getNumImages(true);
                if (count <= 0) return null;

                int canvasW = reader.getWidth(0);
                int canvasH = reader.getHeight(0);
                try {
                    Node root = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                    for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                        if (n.getNodeName().equals("LogicalScreenDescriptor")) {
                            canvasW = Math.max(canvasW, attrInt(n, "logicalScreenWidth", canvasW));
                            canvasH = Math.max(canvasH, attrInt(n, "logicalScreenHeight", canvasH));
                        }
                    }
                } catch (Exception ignored) {}
                if (canvasW > MAX_IMAGE_DIMENSION || canvasH > MAX_IMAGE_DIMENSION) {
                    throw new IOException("GIF dimensions exceed limit");
                }

                int dstW = targetWidth(canvasW, canvasH);
                BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = canvas.createGraphics();
                int step = Math.max(1, (count + FRAME_SAMPLE_LIMIT - 1) / FRAME_SAMPLE_LIMIT);
                List<NativeImage> frames = new ArrayList<>();
                long totalDelayMs = 0;
                BufferedImage previous = null;

                for (int i = 0; i < count; i++) {
                    BufferedImage frame = reader.read(i);
                    int fx = 0, fy = 0, delayCs = 10;
                    String disposal = "none";
                    try {
                        Node root = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                            String name = n.getNodeName();
                            if (name.equals("ImageDescriptor")) {
                                fx = attrInt(n, "imageLeftPosition", 0);
                                fy = attrInt(n, "imageTopPosition", 0);
                            } else if (name.equals("GraphicControlExtension")) {
                                delayCs = attrInt(n, "delayTime", 10);
                                Node d = n.getAttributes().getNamedItem("disposalMethod");
                                if (d != null) disposal = d.getNodeValue();
                            }
                        }
                    } catch (Exception ignored) {}
                    // Browsers treat near-zero delays as 100 ms; do the same.
                    if (delayCs < 2) delayCs = 10;
                    totalDelayMs += delayCs * 10L;

                    if (disposal.equals("restoreToPrevious")) previous = copy(canvas);
                    g.drawImage(frame, fx, fy, null);
                    if (i % step == 0) {
                        frames.add(resample(canvasW, canvasH, canvas::getRGB, dstW, EMOTE_HEIGHT));
                    }

                    if (disposal.equals("restoreToBackgroundColor")) {
                        Composite old = g.getComposite();
                        g.setComposite(AlphaComposite.Clear);
                        g.fillRect(fx, fy, frame.getWidth(), frame.getHeight());
                        g.setComposite(old);
                    } else if (disposal.equals("restoreToPrevious") && previous != null) {
                        Composite old = g.getComposite();
                        g.setComposite(AlphaComposite.Src);
                        g.drawImage(previous, 0, 0, null);
                        g.setComposite(old);
                    }
                }
                g.dispose();

                long frameMs = frames.size() > 1
                        ? Math.max(20, totalDelayMs * step / count)
                        : 0;
                return new Frames(frames.toArray(NativeImage[]::new), frameMs);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            ChatMod.LOGGER.warn("[ChatMod] GIF decode failed: {}", e.getMessage());
            return null;
        }
    }

    /** Target texture width for a source image, preserving aspect ratio at EMOTE_HEIGHT. */
    private static int targetWidth(int srcW, int srcH) {
        return Math.clamp(Math.round(EMOTE_HEIGHT * (float) srcW / srcH), 1, MAX_EMOTE_WIDTH);
    }

    /**
     * Box-filter resample with alpha-weighted color averaging, so downscaled emotes keep
     * clean edges instead of picking up dark fringes from transparent neighbor pixels.
     */
    private static NativeImage resample(int srcW, int srcH, PixelGetter src, int dstW, int dstH) {
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, dstW, dstH, false);
        double sx = (double) srcW / dstW;
        double sy = (double) srcH / dstH;
        for (int y = 0; y < dstH; y++) {
            int y0 = (int) (y * sy);
            int y1 = Math.max(y0 + 1, Math.min((int) Math.ceil((y + 1) * sy), srcH));
            for (int x = 0; x < dstW; x++) {
                int x0 = (int) (x * sx);
                int x1 = Math.max(x0 + 1, Math.min((int) Math.ceil((x + 1) * sx), srcW));
                long aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int argb = src.argb(xx, yy);
                        long a = argb >>> 24;
                        aSum += a;
                        rSum += ((argb >> 16) & 0xFF) * a;
                        gSum += ((argb >> 8) & 0xFF) * a;
                        bSum += (argb & 0xFF) * a;
                        n++;
                    }
                }
                int a = (int) (aSum / n);
                int r = 0, gr = 0, b = 0;
                if (aSum > 0) {
                    r = (int) (rSum / aSum);
                    gr = (int) (gSum / aSum);
                    b = (int) (bSum / aSum);
                }
                out.setPixel(x, y, (a << 24) | (r << 16) | (gr << 8) | b);
            }
        }
        return out;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static int attrInt(Node node, String name, int def) {
        Node attr = node.getAttributes().getNamedItem(name);
        if (attr == null) return def;
        try {
            return Integer.parseInt(attr.getNodeValue());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean isGif(byte[] bytes) {
        return bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
    }

    static boolean isWebP(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
