package io.github.leadassimilator.telispex.client.view;

import javax.annotation.Nullable;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.WGLNVDXInterop;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import io.github.leadassimilator.telispex.Telispex;
import net.minecraft.resources.ResourceLocation;

/**
 * WebView2 drawn into the game's own frame, so the editor shows in exclusive fullscreen too: the page renders into a
 * Windows composition visual that isn't on any window, Windows Graphics Capture hands each frame over as a Direct3D 11
 * texture, and WGL_NV_DX_interop lets OpenGL draw that texture in the view screen. Everything runs on the render
 * thread, whose message loop (pumped by GLFW) also drives the composition.
 *
 * <p>Method numbers are vtable slots from the Windows SDK headers (IUnknown takes 0-2, IInspectable 0-5).
 */
final class CompositionView {
    private static final ResourceLocation FRAME = ResourceLocation.fromNamespaceAndPath(Telispex.ID, "view");

    // ICompositor
    private static final String IID_COMPOSITOR = "{b403ca50-7f8c-4e83-985f-cc45060036d8}";
    private static final int COMPOSITOR_CREATE_CONTAINER_VISUAL = 9;
    // IVisual
    private static final String IID_VISUAL = "{117e202d-a859-4c89-873b-c2aa566788e3}";
    private static final int VISUAL_PUT_SIZE = 36;
    // IGraphicsCaptureItemStatics
    private static final String IID_CAPTURE_ITEM_STATICS = "{a87ebea5-457c-5788-ab47-0cf1d3637e74}";
    private static final int ITEM_CREATE_FROM_VISUAL = 6;
    // IDirect3D11CaptureFramePoolStatics2
    private static final String IID_FRAME_POOL_STATICS2 = "{589b103f-6bbc-5df5-a991-02e28b3b66d5}";
    private static final int POOL_CREATE_FREE_THREADED = 6;
    // IDirect3D11CaptureFramePool
    private static final int POOL_RECREATE = 6;
    private static final int POOL_TRY_GET_NEXT_FRAME = 7;
    private static final int POOL_CREATE_CAPTURE_SESSION = 10;
    // IGraphicsCaptureSession(2, 3)
    private static final int SESSION_START_CAPTURE = 6;
    private static final String IID_SESSION2 = "{2c39ae40-7d2e-5044-804e-8b6799d4cf9e}";
    private static final int SESSION2_PUT_CURSOR = 7;
    private static final String IID_SESSION3 = "{f2cdd966-22ae-5ea1-9596-3a289344c3be}";
    private static final int SESSION3_PUT_BORDER = 7;
    // IDirect3D11CaptureFrame
    private static final int FRAME_GET_SURFACE = 6;
    // IDirect3DDevice, IDirect3DDxgiInterfaceAccess
    private static final String IID_WINRT_DEVICE = "{a37624ab-8d5f-4650-9d3e-9eae3d9bc670}";
    private static final String IID_DXGI_ACCESS = "{a9b3d012-3df2-4ee3-b8d1-8695f457d3c1}";
    private static final int DXGI_ACCESS_GET_INTERFACE = 3;
    // Direct3D 11
    private static final String IID_DXGI_DEVICE = "{54ec77fa-1377-44e6-8c32-88fd5f44c84c}";
    private static final String IID_TEXTURE2D = "{6f15aaf2-d208-4e89-9ab4-489535d34f9c}";
    private static final int DEVICE_CREATE_TEXTURE2D = 5;
    private static final int CONTEXT_COPY_RESOURCE = 47;
    private static final int TEXTURE_GET_DESC = 10;

    /** DXGI_FORMAT_B8G8R8A8_UNORM, and the same as a WinRT DirectXPixelFormat. */
    private static final int BGRA = 87;

    @Nullable
    private static Pointer dispatcher;
    @Nullable
    private static Pointer compositor;
    @Nullable
    private static Pointer container;
    @Nullable
    private static Pointer visual;
    @Nullable
    private static Pointer device;
    @Nullable
    private static Pointer context;
    @Nullable
    private static Pointer winrtDevice;
    @Nullable
    private static Pointer item;
    @Nullable
    private static Pointer pool;
    @Nullable
    private static Pointer session;
    /** The texture OpenGL shares; each captured frame is copied into it. */
    @Nullable
    private static Pointer shared;
    private static long interopDevice;
    private static long interopObject;
    private static int glTexture;
    private static int width;
    private static int height;
    private static int poolWidth;
    private static int poolHeight;
    private static boolean framed;
    /** The size of the frames actually captured (logged when it changes, to compare with the size asked for). */
    private static int capturedWidth;
    private static int capturedHeight;

    private CompositionView() {
    }

    /**
     * Makes the visual WebView2 renders into, sized {@code w} x {@code h}; null (logged) when this Windows or this
     * graphics driver can't do it, so the caller uses a window instead.
     */
    @Nullable
    static Pointer start(int w, int h) {
        try {
            if (!GL.getCapabilitiesWGL().WGL_NV_DX_interop2) {
                Telispex.LOGGER.warn("In-game view: the graphics driver can't share Direct3D textures with OpenGL (WGL_NV_DX_interop2)");
                return null;
            }
            // Windows.UI.Composition needs a dispatcher queue on this thread; GLFW's message loop then runs it.
            DispatcherOptions options = new DispatcherOptions();
            options.size = options.size();
            options.threadType = 2; // DQTYPE_THREAD_CURRENT
            options.apartmentType = 2; // DQTAT_COM_STA
            PointerByReference out = new PointerByReference();
            check(library("CoreMessaging").getFunction("CreateDispatcherQueueController")
                    .invokeInt(new Object[] {options, out}), "creating a dispatcher queue");
            dispatcher = out.getValue();
            compositor = query(activate("Windows.UI.Composition.Compositor"), IID_COMPOSITOR, true);
            check(Com.call(compositor, COMPOSITOR_CREATE_CONTAINER_VISUAL, out), "creating a visual");
            container = out.getValue();
            visual = query(container, IID_VISUAL, false);
            size(w, h);
            createDevice();
            return container;
        } catch (RuntimeException e) {
            Telispex.LOGGER.warn("In-game view: drawing it into the game frame isn't available", e);
            close();
            return null;
        }
    }

    /** Starts capturing the visual, once WebView2 renders into it; false (logged) when capture can't start. */
    static boolean capture() {
        try {
            Pointer statics = factory("Windows.Graphics.Capture.GraphicsCaptureItem", IID_CAPTURE_ITEM_STATICS);
            PointerByReference out = new PointerByReference();
            int hr = Com.call(statics, ITEM_CREATE_FROM_VISUAL, visual, out);
            Com.release(statics);
            check(hr, "capturing the visual");
            item = out.getValue();
            Pointer poolStatics = factory("Windows.Graphics.Capture.Direct3D11CaptureFramePool", IID_FRAME_POOL_STATICS2);
            hr = Com.call(poolStatics, POOL_CREATE_FREE_THREADED, winrtDevice, BGRA, 2, sizeOf(width, height), out);
            Com.release(poolStatics);
            check(hr, "creating the frame pool");
            pool = out.getValue();
            poolWidth = width;
            poolHeight = height;
            check(Com.call(pool, POOL_CREATE_CAPTURE_SESSION, item, out), "creating the capture session");
            session = out.getValue();
            // No cursor in the frames, and no yellow border round it (Windows 11; older ones have neither call).
            Pointer session2 = Com.query(session, IID_SESSION2);
            Com.call(session2, SESSION2_PUT_CURSOR, 0);
            Com.release(session2);
            Pointer session3 = Com.query(session, IID_SESSION3);
            Com.call(session3, SESSION3_PUT_BORDER, 0);
            Com.release(session3);
            check(Com.call(session, SESSION_START_CAPTURE), "starting the capture");
            return true;
        } catch (RuntimeException e) {
            Telispex.LOGGER.warn("In-game view: capturing the web view failed", e);
            close();
            return false;
        }
    }

    /** The view's new size in pixels: the visual and the frames follow. */
    static void size(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        Vector2 size = new Vector2();
        size.x = width;
        size.y = height;
        Com.call(visual, VISUAL_PUT_SIZE, size);
        if (pool != null && (width != poolWidth || height != poolHeight)) {
            Com.call(pool, POOL_RECREATE, winrtDevice, BGRA, 2, sizeOf(width, height));
            poolWidth = width;
            poolHeight = height;
        }
    }

    /**
     * Takes the newest captured frame, if one arrived, into the shared texture. Returns the texture to draw, or null
     * before the first frame.
     */
    @Nullable
    static ResourceLocation frame() {
        if (pool == null) {
            return null;
        }
        Pointer latest = null;
        PointerByReference out = new PointerByReference();
        // Only the newest matters: older frames still queued are dropped.
        while (Com.call(pool, POOL_TRY_GET_NEXT_FRAME, out) >= 0 && out.getValue() != null) {
            Com.release(latest);
            latest = out.getValue();
            out.setValue(null);
        }
        if (latest != null) {
            copy(latest);
            Com.release(latest);
        }
        return framed ? FRAME : null;
    }

    /** The frame texture's size in pixels. */
    static int frameWidth() {
        return capturedWidth;
    }

    static int frameHeight() {
        return capturedHeight;
    }

    private static void copy(Pointer frame) {
        PointerByReference out = new PointerByReference();
        if (Com.call(frame, FRAME_GET_SURFACE, out) < 0) {
            return;
        }
        Pointer surface = out.getValue();
        Pointer access = Com.query(surface, IID_DXGI_ACCESS);
        Com.release(surface);
        if (access == null) {
            return;
        }
        int hr = Com.call(access, DXGI_ACCESS_GET_INTERFACE, Com.iid(IID_TEXTURE2D), out);
        Com.release(access);
        if (hr < 0) {
            return;
        }
        Pointer source = out.getValue();
        TextureDesc desc = new TextureDesc();
        Com.call(source, TEXTURE_GET_DESC, desc);
        desc.read();
        if (desc.width != capturedWidth || desc.height != capturedHeight) {
            capturedWidth = desc.width;
            capturedHeight = desc.height;
            Telispex.LOGGER.debug("In-game view: view {}x{}, captured frames {}x{}", width, height, capturedWidth, capturedHeight);
        }
        if (ensureShared(desc.width, desc.height)) {
            PointerBuffer objects = PointerBuffer.allocateDirect(1).put(0, interopObject);
            // OpenGL holds the texture between frames; Direct3D may write it only while it is unlocked.
            WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, objects);
            Com.call(context, CONTEXT_COPY_RESOURCE, shared, source);
            WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, objects);
            framed = true;
        }
        Com.release(source);
    }

    /** The shared texture at this size, made (or remade) as needed; false when it can't be. */
    private static boolean ensureShared(int w, int h) {
        TextureDesc current = new TextureDesc();
        if (shared != null) {
            Com.call(shared, TEXTURE_GET_DESC, current);
            current.read();
            if (current.width == w && current.height == h) {
                return true;
            }
            releaseShared();
        }
        TextureDesc desc = new TextureDesc();
        desc.width = w;
        desc.height = h;
        desc.mipLevels = 1;
        desc.arraySize = 1;
        desc.format = BGRA;
        desc.sampleCount = 1;
        desc.bindFlags = 0x8 | 0x20; // shader resource, render target
        PointerByReference out = new PointerByReference();
        if (Com.call(device, DEVICE_CREATE_TEXTURE2D, desc, null, out) < 0) {
            return false;
        }
        shared = out.getValue();
        if (interopDevice == 0) {
            interopDevice = WGLNVDXInterop.wglDXOpenDeviceNV(Pointer.nativeValue(device));
            if (interopDevice == 0) {
                Telispex.LOGGER.warn("In-game view: OpenGL can't open the Direct3D device (a different graphics card?)");
                return false;
            }
        }
        if (glTexture == 0) {
            glTexture = GlStateManager._genTexture();
        }
        interopObject = WGLNVDXInterop.wglDXRegisterObjectNV(interopDevice, Pointer.nativeValue(shared), glTexture,
                GL11.GL_TEXTURE_2D, WGLNVDXInterop.WGL_ACCESS_READ_ONLY_NV);
        if (interopObject == 0) {
            Telispex.LOGGER.warn("In-game view: OpenGL can't share the Direct3D texture");
            return false;
        }
        // Through the game's state tracking, which remembers the bound texture.
        GlStateManager._bindTexture(glTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        WGLNVDXInterop.wglDXLockObjectsNV(interopDevice, PointerBuffer.allocateDirect(1).put(0, interopObject));
        FrameTexture.register(FRAME, glTexture, w, h);
        return true;
    }

    private static void releaseShared() {
        if (interopObject != 0) {
            WGLNVDXInterop.wglDXUnlockObjectsNV(interopDevice, PointerBuffer.allocateDirect(1).put(0, interopObject));
            WGLNVDXInterop.wglDXUnregisterObjectNV(interopDevice, interopObject);
            interopObject = 0;
        }
        Com.release(shared);
        shared = null;
    }

    /** Lets go of everything: capture, Direct3D, the shared texture and the composition. */
    static void close() {
        releaseShared();
        if (interopDevice != 0) {
            WGLNVDXInterop.wglDXCloseDeviceNV(interopDevice);
            interopDevice = 0;
        }
        if (glTexture != 0) {
            FrameTexture.unregister(FRAME);
            GlStateManager._deleteTexture(glTexture);
            glTexture = 0;
        }
        for (Pointer p : new Pointer[] {session, pool, item, winrtDevice, context, device, visual, container, compositor, dispatcher}) {
            Com.release(p);
        }
        session = pool = item = winrtDevice = context = device = visual = container = compositor = dispatcher = null;
        framed = false;
        capturedWidth = capturedHeight = 0;
    }

    // ---------------------------------------------------------------- Direct3D and WinRT plumbing

    private static void createDevice() {
        PointerByReference made = new PointerByReference();
        PointerByReference madeContext = new PointerByReference();
        Function create = library("d3d11").getFunction("D3D11CreateDevice");
        // D3D_DRIVER_TYPE_HARDWARE, D3D11_CREATE_DEVICE_BGRA_SUPPORT (capture needs it), D3D11_SDK_VERSION
        check(create.invokeInt(new Object[] {null, 1, null, 0x20, null, 0, 7, made, new IntByReference(), madeContext}),
                "creating a Direct3D 11 device");
        device = made.getValue();
        context = madeContext.getValue();
        Pointer dxgi = query(device, IID_DXGI_DEVICE, false);
        PointerByReference inspectable = new PointerByReference();
        int hr = library("d3d11").getFunction("CreateDirect3D11DeviceFromDXGIDevice")
                .invokeInt(new Object[] {dxgi, inspectable});
        Com.release(dxgi);
        check(hr, "wrapping the Direct3D device for capture");
        winrtDevice = query(inspectable.getValue(), IID_WINRT_DEVICE, true);
    }

    /** A new instance of a WinRT class (RoActivateInstance). */
    private static Pointer activate(String className) {
        Pointer name = hstring(className);
        PointerByReference out = new PointerByReference();
        try {
            check(combase("RoActivateInstance").invokeInt(new Object[] {name, out}), "activating " + className);
        } finally {
            combase("WindowsDeleteString").invokeInt(new Object[] {name});
        }
        return out.getValue();
    }

    /** A WinRT class's statics (RoGetActivationFactory). */
    private static Pointer factory(String className, String iid) {
        Pointer name = hstring(className);
        PointerByReference out = new PointerByReference();
        try {
            check(combase("RoGetActivationFactory").invokeInt(new Object[] {name, Com.iid(iid), out}), "reaching " + className);
        } finally {
            combase("WindowsDeleteString").invokeInt(new Object[] {name});
        }
        return out.getValue();
    }

    private static Pointer hstring(String text) {
        PointerByReference out = new PointerByReference();
        check(combase("WindowsCreateString").invokeInt(new Object[] {new WString(text), text.length(), out}), "making a string");
        return out.getValue();
    }

    private static Function combase(String name) {
        return library("combase").getFunction(name);
    }

    /** Libraries held for the session: JNA caches them weakly and unloads one nothing refers to. */
    private static final java.util.Map<String, NativeLibrary> LIBRARIES = new java.util.HashMap<>();

    private static NativeLibrary library(String name) {
        return LIBRARIES.computeIfAbsent(name, NativeLibrary::getInstance);
    }

    /** QueryInterface that must succeed; {@code releaseSource} lets go of the object asked. */
    private static Pointer query(Pointer source, String iid, boolean releaseSource) {
        Pointer result = Com.query(source, iid);
        if (releaseSource) {
            Com.release(source);
        }
        if (result == null) {
            throw new IllegalStateException("interface " + iid + " isn't available");
        }
        return result;
    }

    private static void check(int hresult, String what) {
        if (hresult < 0) {
            throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hresult));
        }
    }

    private static SizeInt32 sizeOf(int w, int h) {
        SizeInt32 size = new SizeInt32();
        size.width = w;
        size.height = h;
        return size;
    }

    /** DispatcherQueueOptions. */
    @Structure.FieldOrder({"size", "threadType", "apartmentType"})
    public static class DispatcherOptions extends Structure {
        public int size;
        public int threadType;
        public int apartmentType;
    }

    /** Windows.Foundation.Numerics.Vector2, by value. */
    @Structure.FieldOrder({"x", "y"})
    public static class Vector2 extends Structure implements Structure.ByValue {
        public float x;
        public float y;
    }

    /** Windows.Graphics.SizeInt32, by value. */
    @Structure.FieldOrder({"width", "height"})
    public static class SizeInt32 extends Structure implements Structure.ByValue {
        public int width;
        public int height;
    }

    /** D3D11_TEXTURE2D_DESC (its DXGI_SAMPLE_DESC inlined). */
    @Structure.FieldOrder({"width", "height", "mipLevels", "arraySize", "format", "sampleCount", "sampleQuality", "usage",
            "bindFlags", "cpuAccessFlags", "miscFlags"})
    public static class TextureDesc extends Structure {
        public int width;
        public int height;
        public int mipLevels;
        public int arraySize;
        public int format;
        public int sampleCount;
        public int sampleQuality;
        public int usage;
        public int bindFlags;
        public int cpuAccessFlags;
        public int miscFlags;
    }
}
