package app.libretv.android;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Opt-in live smoke test: connectedDebugAndroidTest requires a networked emulator/device. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackSmokeTest {
    @Test public void actualHlsPlaybackAndResume() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Store store = new Store(context);
        List<Library.Source> sources = store.sources(true);
        assertEquals(13, sources.size());
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String sourceName = args.getString("sourceName", "360资源");
        String query = args.getString("query", "大闹天宫");
        String sampleTitle = args.getString("sampleTitle", "");
        Library.Source source = null;
        for (Library.Source candidate : sources) if (candidate.name.equals(sourceName)) source = candidate;
        assertNotNull("Built-in source missing: " + sourceName, source);
        Library.Page result = Library.search(source, query, 1);
        Library.Video chosen = null;
        for (Library.Video video : result.videos) if (sampleTitle.isEmpty()
            ? !video.title.contains("解说") && !video.title.contains("预告") && video.type.contains("动画")
            : video.title.equals(sampleTitle)) { chosen = video; break; }
        assertNotNull("No playable sample from source", chosen);
        Library.Detail detail = Library.detail(chosen);
        assertFalse(detail.lines.isEmpty());
        Intent intent = new Intent(context, PlayerActivity.class).putExtra("video", chosen.json().toString());
        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(intent)) {
            awaitPlaying(scenario, 2000);
            scenario.onActivity(activity -> {
                ExoPlayer player = player(activity);
                assertTrue(player.getVideoSize().width > 0);
                Log.i("LibreTVSmoke", "PLAYED " + player.getVideoSize().width + "x" + player.getVideoSize().height + " position=" + player.getCurrentPosition());
                player.seekTo(45000);
            });
            awaitPlaying(scenario, 45000);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            JSONObject saved = store.progress(chosen);
            assertTrue("Position was not persisted", saved.optLong("position") >= 45000);
            Log.i("LibreTVSmoke", "SAVED position=" + saved.optLong("position"));
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            awaitPlaying(scenario, 45000);
            scenario.recreate();
            awaitPlaying(scenario, 45000);
            scenario.onActivity(activity -> Log.i("LibreTVSmoke", "RESUMED position=" + player(activity).getCurrentPosition()));
        }
    }
    private static ExoPlayer player(PlayerActivity activity) {
        try { Field field = PlayerActivity.class.getDeclaredField("player"); field.setAccessible(true); return (ExoPlayer) field.get(activity); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void awaitPlaying(ActivityScenario<PlayerActivity> scenario, long minimumPosition) throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        AtomicBoolean playing = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> lastState = new java.util.concurrent.atomic.AtomicReference<>("no player");
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                ExoPlayer p = player(activity);
                if (p != null && p.getPlayerError() != null) throw new AssertionError(p.getPlayerError());
                if (p != null) lastState.set("state=" + p.getPlaybackState() + " position=" + p.getCurrentPosition() + " buffered=" + p.getBufferedPosition() + " ready=" + p.getPlayWhenReady() + " suppression=" + p.getPlaybackSuppressionReason());
                playing.set(p != null && p.isPlaying() && p.getCurrentPosition() >= minimumPosition);
            });
            if (playing.get()) return;
            Thread.sleep(500);
        }
        fail("Playback did not advance within 60 seconds: " + lastState.get());
    }
}
