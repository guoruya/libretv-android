package app.libretv.android;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppUpdatesTest {
    @Test public void acceptsHttpsAndExplicitLocalServersOnly() {
        assertTrue(AppUpdates.validUrl("https://github.com/guoruya/libretv-android/releases/latest/download/update.json"));
        assertTrue(AppUpdates.validUrl("http://192.168.1.2:4175/update.json"));
        assertTrue(AppUpdates.validUrl("http://172.16.1.2/update.json"));
        assertTrue(AppUpdates.validUrl("http://127.0.0.1:4175/update.json"));
        for (String url : new String[]{"http://example.com/update.json", "http://172.32.1.1/a", "http://192.168.0.999/a", "file:///etc/passwd", "https://user:password@example.com/a", "https://example.com/a#fragment", "javascript:alert(1)", "https://"})
            assertFalse(url, AppUpdates.validUrl(url));
    }
    @Test public void requiresRealVersionNumbersAndAValidPackageDownload() throws Exception {
        JSONObject data = new JSONObject().put("versionCode", 5).put("versionName", "1.3.0").put("applicationId", "app.libretv.android").put("minSdk", 26).put("apkUrl", "https://example.com/LibreTV.apk").put("notes", "播放时隐藏控件");
        AppUpdates.Release release = new AppUpdates.Release(data);
        assertEquals(5, release.code); assertEquals("播放时隐藏控件", new AppUpdates.Release(release.json()).notes);
        for (Object value : new Object[]{0, -1, 1.5, "5", 4294967296L}) {
            data.put("versionCode", value);
            assertThrows(Exception.class, () -> new AppUpdates.Release(data));
        }
        data.put("versionCode", 5).put("apkUrl", "content://downloads/1");
        assertThrows(Exception.class, () -> new AppUpdates.Release(data));
    }
}
