package com.drivemate.app;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Renders the real MainActivity home screen (no mocks of the layout) to a PNG
 * so the current design can be inspected. Output: app/build/screenshots/home.png
 *
 * Map tiles, GPS and network are not available under Robolectric, so the map
 * area is blank; the header, search bar, destination panel and buttons are
 * drawn from the same code the phone runs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w412dp-h915dp-xxhdpi")
public class HomeScreenScreenshotTest {

    @Test
    public void rendersHomeScreenToPng() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class)
                .setup()
                .get();

        View root = activity.getWindow().getDecorView();
        int width = root.getResources().getDisplayMetrics().widthPixels;
        int height = root.getResources().getDisplayMetrics().heightPixels;
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));

        File out = new File("build/screenshots/home.png");
        out.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        assertTrue("screenshot written", out.length() > 0);
    }
}
