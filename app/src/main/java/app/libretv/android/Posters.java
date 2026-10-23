package app.libretv.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Posters {
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    void load(ImageView view, String url) {
        view.setTag(url); view.setImageDrawable(null);
        if (!Library.validUrl(url)) return;
        Bitmap cached = cache.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        workers.execute(() -> {
            try {
                byte[] data = Library.download(url, 5 * 1024 * 1024);
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 450 || bounds.outHeight / options.inSampleSize > 700) options.inSampleSize *= 2;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap == null) return;
                cache.put(url, bitmap);
                main.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bitmap); });
            } catch (Exception ignored) { /* A missing cover never blocks search or playback. */ }
        });
    }
    void close() { workers.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll(); }
}
