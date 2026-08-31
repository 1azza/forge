package forge.web;

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

import forge.ImageKeys;
import forge.localinstance.properties.ForgeConstants;
import forge.util.BuildInfo;

/**
 * Resolves card art for the browser.
 *
 * <p>Order of preference: an image the user already downloaded through Forge, then our own
 * on-disk cache, then Scryfall. Fetches are cached to disk and served with a long
 * {@code Cache-Control}, so a given printing is pulled at most once per install and the
 * browser never asks for it twice in a session.
 */
public final class CardImages {

    private static final String SCRYFALL_NAMED = "https://api.scryfall.com/cards/named";
    /** Scryfall asks for 50-100ms between requests; be generous. */
    private static final long MIN_REQUEST_GAP_MS = 120;

    private static final Path CACHE_DIR = Path.of(ForgeConstants.CACHE_DIR, "webcards");

    /** Printings we've already failed on, so a missing card doesn't hammer the API. */
    private static final ConcurrentMap<String, Boolean> unavailable = new ConcurrentHashMap<>();

    private static final Object fetchLock = new Object();
    private static long lastRequestAt;

    private CardImages() { }

    /** A card image as bytes, or {@code null} if it can't be found anywhere. */
    public static byte[] get(final String imageKey, final String name, final String set) {
        final File local = localFile(imageKey);
        if (local != null) {
            try {
                return Files.readAllBytes(local.toPath());
            } catch (final IOException e) {
                // fall through to the network
            }
        }
        if (name == null || name.isEmpty()) {
            return null;
        }

        final Path cached = cachePath(name, set);
        if (Files.isReadable(cached)) {
            try {
                return Files.readAllBytes(cached);
            } catch (final IOException e) {
                return null;
            }
        }

        final String key = cacheKey(name, set);
        if (unavailable.containsKey(key)) {
            return null;
        }

        byte[] data = download(name, set);
        if (data == null && set != null && !set.isEmpty()) {
            // The printing Forge picked may not be on Scryfall under that set code;
            // any printing of the card is better than a blank frame.
            data = download(name, null);
        }
        if (data == null) {
            unavailable.put(key, Boolean.TRUE);
            return null;
        }
        try {
            Files.createDirectories(cached.getParent());
            Files.write(cached, data);
        } catch (final IOException e) {
            // serving without caching is fine, just slower next time
        }
        return data;
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

    private static byte[] download(final String name, final String set) {
        final StringBuilder url = new StringBuilder(SCRYFALL_NAMED);
        url.append("?exact=").append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        if (set != null && !set.isEmpty()) {
            url.append("&set=").append(URLEncoder.encode(set.toLowerCase(), StandardCharsets.UTF_8));
        }
        url.append("&format=image&version=normal");

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

    private static Path cachePath(final String name, final String set) {
        final String safe = cacheKey(name, set).replaceAll("[^A-Za-z0-9._|-]", "_").replace('|', '.');
        return CACHE_DIR.resolve(safe + ".jpg");
    }
}
