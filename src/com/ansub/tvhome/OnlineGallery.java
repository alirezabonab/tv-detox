package com.ansub.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

final class OnlineGallery {
    private static final String BASE =
            "https://raw.githubusercontent.com/alirezabonab/tv-detox/astro-home/gallery/";
    private static final long REFRESH_MS = 6 * 60 * 60_000L;
    private static final long RETRY_MS = 2 * 60_000L;
    private static final int IMAGE_LIMIT = 2 * 1024 * 1024;

    interface Result {
        void accept(Bitmap image);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, byte[]> images = new LruCache<String, byte[]>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String name, byte[] bytes) {
            return bytes.length;
        }
    };
    private List<String> photos = Collections.emptyList();
    private long refreshAt;
    private boolean closed;

    void load(long slide, Result result) {
        worker.execute(() -> {
            Bitmap image = null;
            try {
                refreshCatalog();
                if (!photos.isEmpty()) image = loadPhoto(photos.get((int) (slide % photos.size())));
            } catch (IOException ignored) {
                // The launcher uses a built-in wallpaper when a download fails.
            }
            Bitmap loaded = image;
            main.post(() -> {
                if (!closed) result.accept(loaded);
                else if (loaded != null) loaded.recycle();
            });
        });
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    private void refreshCatalog() {
        long now = SystemClock.elapsedRealtime();
        if (now < refreshAt) return;
        try {
            String catalog = new String(download("photos.txt", 16 * 1024), StandardCharsets.UTF_8);
            Set<String> names = new LinkedHashSet<>();
            for (String line : catalog.split("\n")) {
                String name = line.trim();
                if (name.matches("[a-z0-9-]+\\.jpg")) names.add(name);
                if (names.size() == 64) break;
            }
            if (names.isEmpty()) throw new IOException("The photo list is empty");
            photos = new ArrayList<>(names);
            images.evictAll();
            refreshAt = now + REFRESH_MS;
        } catch (IOException ignored) {
            refreshAt = now + RETRY_MS;
        }
    }

    private Bitmap loadPhoto(String name) throws IOException {
        byte[] bytes = images.get(name);
        if (bytes == null) bytes = download(name, IMAGE_LIMIT);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth != 1920 || bounds.outHeight != 1080) return null;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (image != null) images.put(name, bytes);
        return image;
    }

    private static byte[] download(String name, int limit) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(BASE + name).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (InputStream input = connection.getInputStream()) {
            if (connection.getResponseCode() != 200) throw new IOException("Download failed");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int length; (length = input.read(buffer)) != -1; ) {
                if (output.size() + length > limit) throw new IOException("Download is too large");
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }
}
