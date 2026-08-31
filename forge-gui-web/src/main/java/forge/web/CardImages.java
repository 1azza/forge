package forge.web;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.imageio.ImageIO;

import forge.ImageKeys;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;

/**
 * Resolves card art for the browser, in two sizes.
 *
 * <p>Board and hand thumbnails ask for {@code small} — Scryfall's ~146px printing, or the
 * user's own download scaled down — which is several times lighter than the full art; the
 * hover inspector asks for {@code normal}. Each size is cached to disk under its own
 * name and served with a long {@code Cache-Control}, so a given printing is pulled at
 * most once per install and the browser never asks for it twice in a session.
 *
 * <p>Order of preference: an image the user already downloaded through Forge, then our own
 * on-disk cache, then Scryfall. A small request will happily shrink a normal-sized image
 * already on disk rather than reach for the network.
 */
public final class CardImages {

    private static final String SCRYFALL_NAMED = "https://api.scryfall.com/cards/named";
    /** Scryfall asks for 50-100ms between requests; be generous. */
    private static final long MIN_REQUEST_GAP_MS = 120;

    /** Width of a thumbnail; matches Scryfall's "small" version. */
    private static final int SMALL_WIDTH = 146;

    private static final Path CACHE_DIR = Path.of(ForgeConstants.CACHE_DIR, "webcards");

    /** Printings we've already failed on, so a missing card doesn't hammer the API. */
    private static final ConcurrentMap<String, Boolean> unavailable = new ConcurrentHashMap<>();

    private static final Object fetchLock = new Object();
    private static long lastRequestAt;

    private CardImages() { }

    /** A card image as bytes, or {@code null} if it can't be found anywhere. */
    public static byte[] get(final String imageKey, final String name, final String set, final boolean small) {
        final File local = localFile(imageKey);
        if (local != null) {
            final byte[] data = read(local.toPath());
            if (data != null) {
                if (!small) {
                    return data;
                }
                // The user's download is full size; shrink it once and remember the copy.
                final Path cached = cachePath(name, set, true);
                if (name != null && !name.isEmpty()) {
                    final byte[] cachedSmall = read(cached);
                    if (cachedSmall != null) {
                        return cachedSmall;
                    }
                }
                final byte[] scaled = downscale(data);
                if (scaled != null) {
                    if (name != null && !name.isEmpty()) {
                        writeCache(cached, scaled);
                    }
                    return scaled;
                }
                return data;
            }
            // fall through to the network
        }
        if (name == null || name.isEmpty()) {
            return null;
        }

        final Path cached = cachePath(name, set, small);
        final byte[] cachedData = read(cached);
        if (cachedData != null) {
            return cachedData;
        }
        // A small image can be born from a normal one already on disk.
        if (small) {
            final byte[] normal = read(cachePath(name, set, false));
            if (normal != null) {
                final byte[] scaled = downscale(normal);
                if (scaled != null) {
                    writeCache(cached, scaled);
                    return scaled;
                }
            }
        }

        final String key = cacheKey(name, set);
        if (unavailable.containsKey(key)) {
            return null;
        }

        byte[] data = download(name, set, small);
        if (data == null && set != null && !set.isEmpty()) {
            // The printing Forge picked may not be on Scryfall under that set code;
            // any printing of the card is better than a blank frame.
            data = download(name, null, small);
        }
        if (data == null && small) {
            // Last resort: full art from the network, shrunk here. Keeping the scaled
            // copy means the heavy bytes need never come down a second time.
            data = download(name, set, false);
            if (data == null && set != null && !set.isEmpty()) {
                data = download(name, null, false);
            }
            if (data != null) {
                final byte[] scaled = downscale(data);
                if (scaled != null) {
                    writeCache(cached, scaled);
                    return scaled;
                }
            }
        }
        if (data == null) {
            unavailable.put(key, Boolean.TRUE);
            return null;
        }
        writeCache(cached, data);
        return data;
    }

    private static byte[] read(final Path path) {
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (final IOException e) {
            return null;
        }
    }

    private static void writeCache(final Path path, final byte[] data) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, data);
        } catch (final IOException e) {
            // serving without caching is fine, just slower next time
        }
    }

    /** Halves and halves again until the art is thumbnail-sized; {@code null} on any trouble. */
    private static byte[] downscale(final byte[] data) {
        try {
            final BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null || src.getWidth() <= SMALL_WIDTH) {
                return null;
            }
            final int height = Math.max(1, Math.round(src.getHeight() * (SMALL_WIDTH / (float) src.getWidth())));
            final BufferedImage out = new BufferedImage(SMALL_WIDTH, height, BufferedImage.TYPE_INT_RGB);
            final Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            g.drawImage(src, 0, 0, SMALL_WIDTH, height, null);
            g.dispose();
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(16 * 1024);
            if (!ImageIO.write(out, "jpg", buffer)) {
                return null;
            }
            return buffer.toByteArray();
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private static File localFile(final String imageKey) {
        if (imageKey == null || imageKey.isEmpty()) {
            return null;
        }
        try {
            final File file = ImageKeys.getImageFile(imageKey);
            return file != null && file.isFile() ? file : null;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static byte[] download(final String name, final String set, final boolean small) {
        final StringBuilder url = new StringBuilder(SCRYFALL_NAMED);
        url.append("?exact=").append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (set != null && !set.isEmpty()) {
            url.append("&set=").append(URLEncoder.encode(set.toLowerCase(), StandardCharsets.UTF_8));
        }
        url.append("&format=image&version=").append(small ? "small" : "normal");

        throttle();
        try {
            final HttpURLConnection connection = (HttpURLConnection) URI.create(url.toString()).toURL().openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
            connection.setRequestProperty("Accept", "image/*");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (final IOException e) {
            return null;
        }
    }

    private static void throttle() {
        synchronized (fetchLock) {
            final long wait = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestAt);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    private static String cacheKey(final String name, final String set) {
        return name + "|" + (set == null ? "" : set);
    }

    private static Path cachePath(final String name, final String set, final boolean small) {
        final String safe = cacheKey(name, set).replaceAll("[^A-Za-z0-9._|-]", "_").replace('|', '.');
        return CACHE_DIR.resolve(safe + (small ? ".sm" : "") + ".jpg");
    }
}
