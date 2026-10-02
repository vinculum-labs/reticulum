package io.github.leadassimilator.reticulum.client.webview;

import javax.annotation.Nullable;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.PointerByReference;

/** Calling COM objects through JNA by vtable slot, and the small COM objects WebView2 calls back. */
final class Com {
    static final int S_OK = 0;

    private Com() {
    }

    /** Calls a COM method by its vtable slot; returns its HRESULT (-1 for a null object). */
    static int call(@Nullable Pointer self, int slot, Object... args) {
        if (self == null) {
            return -1;
        }
        Pointer function = self.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
        Object[] all = new Object[args.length + 1];
        all[0] = self;
        System.arraycopy(args, 0, all, 1, args.length);
        return Function.getFunction(function).invokeInt(all);
    }

    static void addRef(Pointer self) {
        call(self, 1);
    }

    static void release(@Nullable Pointer self) {
        if (self != null) {
            call(self, 2);
        }
    }

    static Guid.GUID iid(String iid) {
        return new Guid.GUID(iid);
    }

    /** QueryInterface; null when the object doesn't have that interface. */
    @Nullable
    static Pointer query(@Nullable Pointer self, String iid) {
        PointerByReference out = new PointerByReference();
        if (call(self, 0, iid(iid), out) < 0) {
            return null;
        }
        return out.getValue();
    }

    // ---------------------------------------------------------------- callback objects

    /** The Invoke of a WebView2 "completed" handler: (this, HRESULT, the created object). */
    interface Completed extends Callback {
        int invoke(Pointer self, int result, Pointer created);
    }

    /** The Invoke of a WebView2 event handler: (this, sender, event args). */
    interface Event extends Callback {
        int invoke(Pointer self, Pointer sender, Pointer args);
    }

    interface QueryInterface extends Callback {
        int invoke(Pointer self, Pointer iid, Pointer out);
    }

    interface RefCount extends Callback {
        int invoke(Pointer self);
    }

    /**
     * A minimal COM object for a handler: a vtable of QueryInterface, AddRef, Release and Invoke. Its lifetime is
     * the caller's (kept in a static list), so the reference count is ignored.
     */
    static final class Handler {
        private final Memory object = new Memory(Native.POINTER_SIZE);
        private final Memory vtable = new Memory(4L * Native.POINTER_SIZE);
        private final QueryInterface queryInterface = (self, iid, out) -> {
            out.setPointer(0, self);
            return S_OK;
        };
        private final RefCount addRef = self -> 1;
        private final RefCount release = self -> 1;
        private final Callback invoke;

        Handler(Callback invoke) {
            this.invoke = invoke;
            vtable.setPointer(0, CallbackReference.getFunctionPointer(queryInterface));
            vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release));
            vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(invoke));
            object.setPointer(0, vtable);
        }

        Pointer pointer() {
            return object;
        }
    }
}
