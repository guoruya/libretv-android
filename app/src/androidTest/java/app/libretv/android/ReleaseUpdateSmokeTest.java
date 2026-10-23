package app.libretv.android;

import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReleaseUpdateSmokeTest {
    @Test public void installedReleaseChecksTheConfiguredUpdateChannel() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("releaseUpdate")));
        android.app.UiAutomation ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (android.os.ParcelFileDescriptor command = ui.executeShellCommand("am start -W -n app.libretv.android/.MainActivity")) {
            try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) { byte[] bytes = new byte[4096]; while (in.read(bytes) != -1) {} }
        }
        AccessibilityNodeInfo check = awaitText("检查更新", true); assertTrue(check.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        awaitText("已是最新版本", false); ui.waitForIdle(500, 3000);
        android.graphics.Bitmap image = ui.takeScreenshot();
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "v13-release-update.png");
        try(FileOutputStream out = new FileOutputStream(file)) { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); } finally { image.recycle(); }
    }
    @Test public void installedReleaseUpgradesFromPublicChannel() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("publicUpgrade")));
        String version = InstrumentationRegistry.getArguments().getString("publicVersion", "1.3.1");
        String code = InstrumentationRegistry.getArguments().getString("publicVersionCode", "6");
        shell("am start -W -n app.libretv.android/.MainActivity");
        assertTrue(awaitText("检查更新", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        assertTrue(awaitText("更新地址", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        AccessibilityNodeInfo input = findInput(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());
        assertNotNull(input);
        android.os.Bundle value = new android.os.Bundle();
        value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            "https://github.com/guoruya/libretv-android/releases/latest/download/update.json");
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value));
        assertTrue(awaitText("保存并检查", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        awaitText("发现新版本 " + version, false);
        assertTrue(awaitText("下载并安装", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        // The explicit opt-in test permits the system installer to replace the release app.
        assertTrue(awaitAnyText(new String[]{"Update", "更新"}, true, 120000).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        long end = System.currentTimeMillis() + 60000;
        while (!shell("dumpsys package app.libretv.android").contains("versionCode=" + code + " ")) {
            if (System.currentTimeMillis() >= end) fail("System installer did not update the release app");
            Thread.sleep(200);
        }
        shell("am start -W -n app.libretv.android/.MainActivity");
        assertTrue(awaitText("检查更新", true).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        awaitText("当前版本  " + version, false); awaitText("已是最新版本", false);
        android.graphics.Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "public-upgrade.png");
        try (FileOutputStream out = new FileOutputStream(file)) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        finally { screenshot.recycle(); }
    }
    private static String shell(String command) throws Exception {
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int read;
            while ((read = in.read(bytes)) != -1) out.write(bytes, 0, read);
            return out.toString("UTF-8");
        }
    }
    private AccessibilityNodeInfo findInput(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = findInput(node.getChild(i)); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo awaitText(String text, boolean clickable) throws Exception {
        return awaitAnyText(new String[]{text}, clickable, 60000);
    }
    private AccessibilityNodeInfo awaitAnyText(String[] texts, boolean clickable, long timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if(root != null) for(String text:texts) for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)) if(text.contentEquals(node.getText()==null?"":node.getText()) && node.isEnabled() && (!clickable || node.isClickable())) return node;
            Thread.sleep(100);
        }
        throw new AssertionError("Missing: " + java.util.Arrays.toString(texts));
    }
}
