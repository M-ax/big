package com.example.big.playback;

import android.animation.ValueAnimator;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.big.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PlaybackPresentationTest {
    @Test
    public void seekFeedbackShowsGrayAbsoluteThenWhiteSignedDelta() {
        Spanned text = (Spanned) SeekFeedback.format(90000, 75000);
        assertEquals("1:30  +0:15", text.toString());
        ForegroundColorSpan[] spans = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        assertEquals(2, spans.length);
        assertEquals(Color.LTGRAY, spans[0].getForegroundColor());
        assertEquals(0, text.getSpanStart(spans[0]));
        assertEquals(4, text.getSpanEnd(spans[0]));
        assertEquals(Color.WHITE, spans[1].getForegroundColor());
        assertEquals(6, text.getSpanStart(spans[1]));
        assertEquals(text.length(), text.getSpanEnd(spans[1]));
        assertEquals("0:00  −0:03", SeekFeedback.format(0, 3000).toString());
        assertEquals("0:30  +0:02", SeekFeedback.format(30000, 28000).toString());
        assertEquals("0:00  +0:00", SeekFeedback.format(-1, -1).toString());
        assertEquals("1:00:00  +0:01", SeekFeedback.format(3600000, 3599000).toString());
    }

    @Test
    public void preciseSeekFeedbackDistinguishesAdjacentFramesAndRetainsColors() {
        Spanned text = (Spanned) SeekFeedback.format(5042, 5000, true);
        assertEquals("0:05.042  +0:00.042", text.toString());
        ForegroundColorSpan[] spans = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        assertEquals(2, spans.length);
        assertEquals(Color.LTGRAY, spans[0].getForegroundColor());
        assertEquals(8, text.getSpanEnd(spans[0]));
        assertEquals(Color.WHITE, spans[1].getForegroundColor());
        assertEquals(10, text.getSpanStart(spans[1]));
        assertEquals("0:04.958  −0:00.042", SeekFeedback.format(4958, 5000, true).toString());
    }

    @Test
    public void filenameDecodesOnceWithoutExposingUrlTokens() {
        assertEquals("Clip #1 + 100% café.mp4", MediaTitle.fallback(Uri.parse(
                "smb://nas/Media/Clip%20%231%20%2B%20100%25%20caf%C3%A9.mp4")));
        assertEquals("video%20name.mp4", MediaTitle.fallback(Uri.parse(
                "https://user:password@server/video%2520name.mp4?secret=token#fragment")));
        assertEquals("server", MediaTitle.fallback(Uri.parse("rtsp://server?secret=token")));
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("missing.mp4", MediaTitle.resolve(context.getContentResolver(),
                Uri.parse("content://not.a.real.provider/missing.mp4")));
    }

    @Test
    public void longTitlesScrollBothWaysAndStopWhenHiddenOrShort() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        String previous = Settings.Global.getString(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE);
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
        try {
            Settings.Global.putString(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, "1");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicReference<ScrollingTitleView> title = new AtomicReference<>();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    FrameLayout root = new FrameLayout(activity);
                    ScrollingTitleView view = new ScrollingTitleView(activity, null);
                    view.setText("A very long media filename that cannot possibly fit in this small header.mp4");
                    root.addView(view, new FrameLayout.LayoutParams(200, 80));
                    activity.setContentView(root);
                    title.set(view);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    ScrollingTitleView view = title.get();
                    ValueAnimator animation = animation(view);
                    assertNotNull("Overflow must start an animation", animation);
                    assertEquals(ValueAnimator.INFINITE, animation.getRepeatCount());
                    animation.setCurrentPlayTime(1800);
                    int end = view.getScrollX();
                    assertTrue(end > 0);
                    animation.setCurrentPlayTime(3150);
                    assertTrue(view.getScrollX() > 0 && view.getScrollX() < end);
                    animation.setCurrentPlayTime(3600);
                    assertEquals(0, view.getScrollX());
                    view.setVisibility(View.GONE);
                    assertFalse(animation.isStarted());
                    assertNull(animation(view));
                    view.setText("short.mp4");
                    view.setVisibility(View.VISIBLE);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertNull(animation(title.get()));
                    assertEquals(0, title.get().getScrollX());
                });
            }
        } finally {
            Settings.Global.putString(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, previous);
            automation.dropShellPermissionIdentity();
        }
    }

    private static ValueAnimator animation(ScrollingTitleView view) {
        try {
            Field field = ScrollingTitleView.class.getDeclaredField("animator");
            field.setAccessible(true);
            return (ValueAnimator) field.get(view);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}