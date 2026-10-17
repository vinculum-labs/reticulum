package io.github.leadassimilator.telispex.client.view;

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
    static final int E_NOINTERFACE = 0x80004002;
    static final String IID_UNKNOWN = "{00000000-0000-0000-C000-000000000046}";
    static final String IID_DROP_TARGET = "{00000122-0000-0000-C000-000000000046}";

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

    /** IDropTarget's methods; a POINTL by value is one 64-bit argument (x low, y high) on 64-bit Windows. */
    interface DragEnter extends Callback {
        int invoke(Pointer self, Pointer data, int keys, long point, Pointer effect);
    }

    interface DragOver extends Callback {
        int invoke(Pointer self, int keys, long point, Pointer effect);
    }

    interface DragLeave extends Callback {
        int invoke(Pointer self);
    }

    /** IDropTarget's Drop: the same arguments as DragEnter. */
    interface Drop extends Callback {
        int invoke(Pointer self, Pointer data, int keys, long point, Pointer effect);
    }

    /** A WebView2 handler (a "completed" or event handler): an object of one method, Invoke, answering any interface. */
    static Table handler(Callback invoke) {
        return new Table(null, invoke);
    }

    /**
     * A COM object: QueryInterface, AddRef and Release, then {@code methods} in vtable order. Kept for the session by
     * its caller, so the reference count is ignored. {@code interfaces} are the IIDs it answers to (asked for any
     * other, it says it has none; null answers all, enough for WebView2's own handlers). One that crosses processes
     * (a drop target, which OLE marshals) must name its own: asked for IMarshal, saying yes would have OLE call its
     * methods as IMarshal's.
     */
    static final class Table {
        private final Memory object = new Memory(Native.POINTER_SIZE);
        private final Memory vtable;
        private final Callback[] keep;

        Table(@Nullable String[] interfaces, Callback... methods) {
            vtable = new Memory((3L + methods.length) * Native.POINTER_SIZE);
            Guid.GUID[] accepted = interfaces == null ? null : java.util.Arrays.stream(interfaces).map(Com::iid).toArray(Guid.GUID[]::new);
            QueryInterface queryInterface = (self, iid, out) -> {
                if (accepted != null) {
                    Guid.GUID asked = new Guid.GUID(iid);
                    if (java.util.Arrays.stream(accepted).noneMatch(asked::equals)) {
                        out.setPointer(0, Pointer.NULL);
                        return E_NOINTERFACE;
                    }
                }
                out.setPointer(0, self);
                return S_OK;
            };
            RefCount count = self -> 1;
            keep = new Callback[methods.length + 2];
            keep[0] = queryInterface;
            keep[1] = count;
            System.arraycopy(methods, 0, keep, 2, methods.length);
            vtable.setPointer(0, CallbackReference.getFunctionPointer(queryInterface));
            vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(count));
            vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(count));
            for (int i = 0; i < methods.length; i++) {
                vtable.setPointer((3L + i) * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(methods[i]));
            }
            object.setPointer(0, vtable);
        }

        Pointer pointer() {
            return object;
        }
    }

}
