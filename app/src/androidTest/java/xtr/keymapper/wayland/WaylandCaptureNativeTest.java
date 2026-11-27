package xtr.keymapper.wayland;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class WaylandCaptureNativeTest {
    @Test
    public void resolvesOnlyExplicitValidSocketPaths() {
        assertEquals("/run/xdg/wayland-0", WaylandLauncher.resolveEndpoint("/run/xdg", "wayland-0"));
        assertEquals("/socket/wayland", WaylandLauncher.resolveEndpoint("", "/socket/wayland"));
        assertNull(WaylandLauncher.resolveEndpoint("/run/xdg", "None"));
        assertNull(WaylandLauncher.resolveEndpoint("/run/xdg", "../wayland-0"));
        assertNull(WaylandLauncher.resolveEndpoint("/run/xdg/..", "wayland-0"));
        assertNull(WaylandLauncher.resolveEndpoint("", "/socket/../wayland"));
    }

    @Test
    public void nativeDispatchDeliversInputAndChangesPointerLockWithoutRemapping() throws Exception {
        String address = "xtmapper-wayland-test-" + UUID.randomUUID();
        try (LocalServerSocket server = new LocalServerSocket(address);
             LocalSocket client = new LocalSocket()) {
            client.connect(new LocalSocketAddress(address));
            try (LocalSocket compositor = server.accept()) {
                compositor.setSoTimeout(5000);
                FakeCompositor protocol = new FakeCompositor(compositor);
                Observer observer = new Observer();
                int descriptor = ParcelFileDescriptor.dup(client.getFileDescriptor()).detachFd();
                try (WaylandCaptureNative capture = new WaylandCaptureNative(descriptor, observer)) {
                    protocol.setup();
                    int connected = WaylandCaptureNative.CONNECTED
                            | WaylandCaptureNative.RELATIVE_POINTER_AVAILABLE;
                    int active = connected | WaylandCaptureNative.MAPPED
                            | WaylandCaptureNative.KEYBOARD_FOCUS | WaylandCaptureNative.POINTER_FOCUS;
                    observer.awaitState(0, connected);
                    protocol.sendKeymapAndVerifyPing();

                    capture.setVisible(true, 7, false, 640, 480);
                    protocol.show();
                    observer.awaitState(7, active);

                    protocol.write(protocol.keyboard, 3, 2, 100, 16, 1);
                    assertEquals("7:16:1", observer.keys.poll(5, TimeUnit.SECONDS));

                    capture.setVisible(true, 7, true, 640, 480);
                    protocol.lockPointer();
                    observer.awaitState(7, active | WaylandCaptureNative.POINTER_LOCKED);
                    protocol.write(protocol.relativePointer, 0, 0, 100, 128, -128, 128, -128);
                    protocol.write(protocol.relativePointer, 0, 0, 101, 128, -128, 128, -128);
                    assertEquals("7:1:-1", observer.relative.poll(5, TimeUnit.SECONDS));
                    assertNull(observer.relative.poll());

                    capture.setVisible(true, 7, false, 640, 480);
                    protocol.unlockPointer();
                    observer.awaitState(7, active);

                    capture.setVisible(false, 8, false, 0, 0);
                    protocol.hide();
                    observer.awaitState(8, connected);

                    protocol.write(protocol.keyboard, 3, 3, 100, 16, 0);
                    compositor.shutdownOutput();
                    observer.awaitState(8, 0);
                    assertNull(observer.keys.poll());
                }
            }
        }
    }

    private static final class Observer implements WaylandCaptureNative.Callback {
        final BlockingQueue<String> states = new LinkedBlockingQueue<>();
        final BlockingQueue<String> keys = new LinkedBlockingQueue<>();
        final BlockingQueue<String> relative = new LinkedBlockingQueue<>();

        @Override
        public void onState(long generation, int state) {
            states.add(generation + ":" + state);
        }

        @Override
        public void onEvent(long generation, int type, int code, double x, double y) {
            if (type == WaylandCaptureNative.KEY) keys.add(generation + ":" + code + ":" + (int) x);
            if (type == WaylandCaptureNative.RELATIVE) {
                relative.add(generation + ":" + (int) x + ":" + (int) y);
            }
        }

        void awaitState(long generation, int flags) throws InterruptedException {
            String expected = generation + ":" + flags;
            for (int i = 0; i < 8; ++i) {
                String state = states.poll(5, TimeUnit.SECONDS);
                assertNotNull("Awaited state " + expected, state);
                if (expected.equals(state)) return;
            }
            throw new AssertionError("Awaited state " + expected);
        }
    }

    private static final class FakeCompositor {
        private final LocalSocket socket;
        private final DataInputStream input;
        private int registry;
        private int compositor;
        private int shm;
        private int shell;
        private int seat;
        private int relativeManager;
        private int constraints;
        private int pointer;
        private int keyboard;
        private int relativePointer;
        private int pointerLock;
        private int surface;
        private int xdgSurface;
        private int toplevel;

        FakeCompositor(LocalSocket socket) throws IOException {
            this.socket = socket;
            this.input = new DataInputStream(socket.getInputStream());
        }

        void setup() throws IOException {
            registry = request(1, 1).getInt();
            int initialSync = request(1, 0).getInt();
            global(1, "wl_compositor", 1);
            global(2, "wl_shm", 1);
            global(3, "xdg_wm_base", 1);
            global(4, "wl_seat", 5);
            global(5, "zwp_relative_pointer_manager_v1", 1);
            global(6, "zwp_pointer_constraints_v1", 1);
            write(initialSync, 0, 1);
            write(1, 1, initialSync);
            compositor = boundObject();
            shm = boundObject();
            shell = boundObject();
            seat = boundObject();
            relativeManager = boundObject();
            constraints = boundObject();
            int capabilitySync = request(1, 0).getInt();
            write(shm, 0, 0);
            write(seat, 0, 3);
            write(capabilitySync, 0, 1);
            write(1, 1, capabilitySync);
            pointer = request(seat, 0).getInt();
            keyboard = request(seat, 1).getInt();
            relativePointer = request(relativeManager, 1).getInt();
        }

        void sendKeymapAndVerifyPing() throws IOException {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor keymap = pipe[0]; ParcelFileDescriptor writer = pipe[1]) {
                socket.setFileDescriptorsForSend(new FileDescriptor[]{keymap.getFileDescriptor()});
                try {
                    // The keymap descriptor travels out of band. Physical codes do not use it.
                    write(keyboard, 0, 0, 0);
                } finally {
                    socket.setFileDescriptorsForSend(null);
                }
            }
            write(shell, 0, 42);
            assertEquals(42, request(shell, 3).getInt());
        }

        void show() throws IOException {
            surface = request(compositor, 0).getInt();
            xdgSurface = request(shell, 2).getInt();
            toplevel = request(xdgSurface, 1).getInt();
            request(toplevel, 2);
            request(toplevel, 3);
            assertEquals(640, request(toplevel, 7).getInt());
            assertEquals(640, request(toplevel, 8).getInt());
            request(surface, 6);
            write(toplevel, 0, 640, 480, 0);
            write(xdgSurface, 0, 1);
            request(xdgSurface, 4);
            int pool = request(shm, 0).getInt();
            request(pool, 0);
            request(pool, 1);
            request(xdgSurface, 3);
            request(surface, 1);
            request(surface, 2);
            request(surface, 6);
            write(keyboard, 1, 1, surface, 0);
            write(pointer, 0, 1, surface, 320 * 256, 240 * 256);
        }

        void hide() throws IOException {
            request(toplevel, 0);
            request(xdgSurface, 0);
            request(surface, 0);
        }

        void lockPointer() throws IOException {
            ByteBuffer args = request(constraints, 1);
            pointerLock = args.getInt();
            assertEquals(surface, args.getInt());
            assertEquals(pointer, args.getInt());
            write(pointerLock, 0);
        }

        void unlockPointer() throws IOException {
            request(pointerLock, 0);
        }

        private int boundObject() throws IOException {
            ByteBuffer args = request(registry, 0);
            return args.getInt(args.limit() - 4);
        }

        private void global(int name, String interfaceName, int version) throws IOException {
            byte[] text = interfaceName.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer args = buffer(12 + ((text.length + 4) / 4) * 4);
            args.putInt(name).putInt(text.length + 1).put(text).put((byte) 0);
            args.putInt(args.limit() - 4, version);
            write(registry, 0, args);
        }

        private ByteBuffer request(int expectedObject, int expectedOpcode) throws IOException {
            byte[] header = new byte[8];
            input.readFully(header);
            ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder());
            assertEquals(expectedObject, fields.getInt());
            int packed = fields.getInt();
            assertEquals(expectedOpcode, packed & 0xffff);
            byte[] body = new byte[(packed >>> 16) - 8];
            input.readFully(body);
            return ByteBuffer.wrap(body).order(ByteOrder.nativeOrder());
        }

        void write(int object, int opcode, int... args) throws IOException {
            ByteBuffer body = buffer(args.length * 4);
            for (int arg : args) body.putInt(arg);
            write(object, opcode, body);
        }

        private void write(int object, int opcode, ByteBuffer body) throws IOException {
            int size = 8 + body.capacity();
            ByteBuffer message = buffer(size).putInt(object).putInt((size << 16) | opcode);
            message.put(body.array());
            socket.getOutputStream().write(message.array());
        }

        private static ByteBuffer buffer(int size) {
            return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
        }
    }
}
