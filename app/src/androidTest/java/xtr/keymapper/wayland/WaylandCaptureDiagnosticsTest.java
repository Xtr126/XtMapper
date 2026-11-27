package xtr.keymapper.wayland;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public final class WaylandCaptureDiagnosticsTest {
    @Test
    public void resolvesRelativeAndAbsoluteDisplayProperties() {
        assertEquals("/run/xdg/wayland-0", WaylandCaptureDiagnostics.resolveEndpoint("/run/xdg", "wayland-0"));
        assertEquals("/run/custom/wayland-1",
                WaylandCaptureDiagnostics.resolveEndpoint("", "/run/custom/wayland-1"));
    }

    @Test
    public void rejectsMissingAndInvalidDisplayProperties() {
        assertNull(WaylandCaptureDiagnostics.resolveEndpoint("/run/xdg", ""));
        assertNull(WaylandCaptureDiagnostics.resolveEndpoint("/run/xdg", "None"));
        assertNull(WaylandCaptureDiagnostics.resolveEndpoint("", "wayland-0"));
        assertNull(WaylandCaptureDiagnostics.resolveEndpoint("run/xdg", "wayland-0"));
        assertNull(WaylandCaptureDiagnostics.resolveEndpoint("/run/xdg", "../wayland-0"));
    }

    @Test
    public void nativeProbeRejectsRelativeSocketPath() {
        assertEquals(2, WaylandCaptureDiagnostics.run("wayland-0", 100));
    }

    @Test
    public void nativeProbeReportsMissingSocket() throws IOException {
        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File endpoint = File.createTempFile("wl-", ".sock", cache);
        assertTrue(endpoint.delete());
        assertEquals(3, WaylandCaptureDiagnostics.run(endpoint.getAbsolutePath(), 100));
    }
}
