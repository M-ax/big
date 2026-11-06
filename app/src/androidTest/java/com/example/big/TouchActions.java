package com.example.big;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.platform.app.InstrumentationRegistry;

import org.hamcrest.Matcher;

import static androidx.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertTrue;

public final class TouchActions {
    private TouchActions() {
    }

    public static ViewAction click() {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return allOf(isDisplayingAtLeast(90), isEnabled());
            }

            @Override
            public String getDescription() {
                return "short touchscreen tap without waiting between down and up";
            }

            @Override
            public void perform(UiController uiController, View view) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                float x = location[0] + view.getWidth() / 2f;
                float y = location[1] + view.getHeight() / 2f;
                long time = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(time, time + 1, MotionEvent.ACTION_UP, x, y, 0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
                try {
                    // A slow emulator's intervening idle wait can turn Espresso's tap into a tooltip long press.
                    assertTrue("Touch down must be injected", automation.injectInputEvent(down, false));
                    assertTrue("Touch up must be injected", automation.injectInputEvent(up, false));
                } finally {
                    down.recycle();
                    up.recycle();
                }
                uiController.loopMainThreadForAtLeast(100);
                uiController.loopMainThreadUntilIdle();
            }
        };
    }
}