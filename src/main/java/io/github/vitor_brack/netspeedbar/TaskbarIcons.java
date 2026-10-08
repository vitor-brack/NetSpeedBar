package io.github.vitor_brack.netspeedbar;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.OleAuto;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.ptr.PointerByReference;
import java.util.Arrays;

/**
 * Descobre onde ficam os botões da barra do Windows 11 (Iniciar, ícones dos aplicativos, Widgets...), para o widget
 * não passar por cima deles. No Windows 11 esses botões são desenhados em XAML, sem janelas próprias: a única fonte
 * confiável é a UI Automation, a API de acessibilidade do Windows (a mesma que os leitores de tela usam).
 *
 * Cada consulta vai até o explorer.exe e leva alguns milissegundos, então roda numa thread própria (como a Microsoft
 * recomenda para clientes de UI Automation): a cada 2 segundos, ou na hora quando pedida (no começo de um arraste).
 */
final class TaskbarIcons {

    /** Onde terminam os ícones dos aplicativos e onde estão os botões avulsos (pares esquerda/direita), na tela. */
    record Layout(int appsRight, int[] buttons) {

        static final Layout UNKNOWN = new Layout(Integer.MIN_VALUE, new int[0]);

        boolean known() {
            return appsRight != Integer.MIN_VALUE;
        }
    }

    private static final GUID CLSID_CUI_AUTOMATION = new GUID("{ff48dba4-60ef-4201-aa87-54103eef594e}");
    private static final GUID IID_IUI_AUTOMATION = new GUID("{30cbe57d-d9d0-452a-ab13-7ac5ac4825ee}");
    private static final int UIA_AUTOMATION_ID_PROPERTY = 30011;
    private static final int TREE_SCOPE_DESCENDANTS = 4;
    private static final short VT_BSTR = 8;
    private static final long REFRESH_MS = 2000;

    // Posição de cada método na tabela de funções (vtable) das interfaces COM, na ordem de UIAutomationClient.h
    private static final int RELEASE = 2;
    private static final int AUTOMATION_ELEMENT_FROM_HANDLE = 6;
    private static final int AUTOMATION_RAW_VIEW_WALKER = 16;
    private static final int AUTOMATION_CREATE_PROPERTY_CONDITION = 23;
    private static final int ELEMENT_FIND_FIRST = 5;
    private static final int ELEMENT_AUTOMATION_ID = 29;
    private static final int ELEMENT_BOUNDING_RECTANGLE = 43;
    private static final int WALKER_FIRST_CHILD = 4;
    private static final int WALKER_NEXT_SIBLING = 6;

    private final Object signal = new Object();
    private boolean refreshRequested;
    private volatile Layout layout = Layout.UNKNOWN;
    private Pointer automation;
    private Pointer rawWalker;      // percorre a árvore "crua", que mostra também os grupos (não só os controles)
    private Pointer frameCondition; // AutomationId == "TaskbarFrame"
    private Memory frameIdVariant;  // o VARIANT usado pela condição fica vivo junto com ela

    /** A leitura mais recente (UNKNOWN enquanto não deu para ler: aí o widget só evita a bandeja). */
    Layout layout() {
        return layout;
    }

    void start() {
        if (Native.POINTER_SIZE != 8) {
            Log.info("Detecção dos ícones da barra disponível só em 64 bits");
            return;
        }
        Thread thread = new Thread(this::run, "TaskbarIcons");
        thread.setDaemon(true);
        thread.start();
    }

    /** Pede uma leitura imediata, sem esperar os 2 segundos. */
    void refreshSoon() {
        synchronized (signal) {
            refreshRequested = true;
            signal.notifyAll();
        }
    }

    private void run() {
        Win32.User32Ex.INSTANCE.SetThreadDpiAwarenessContext(Win32.DPI_PER_MONITOR_AWARE_V2); // coordenadas iguais às do widget
        try {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
            createAutomation();
        } catch (RuntimeException e) {
            Log.error("UI Automation indisponível: o widget não vai detectar os ícones da barra", e);
            return;
        }
        boolean failing = false;
        do {
            try {
                layout = read();
                failing = false;
            } catch (RuntimeException e) {
                layout = Layout.UNKNOWN;
                if (!failing) {
                    Log.error("Não foi possível ler os ícones da barra", e);
                    failing = true;
                }
            }
        } while (awaitRefresh());
    }

    private void createAutomation() {
        PointerByReference result = new PointerByReference();
        check(Ole32.INSTANCE.CoCreateInstance(CLSID_CUI_AUTOMATION, null, WTypes.CLSCTX_INPROC_SERVER,
                IID_IUI_AUTOMATION, result).intValue());
        automation = result.getValue();
        rawWalker = out(automation, AUTOMATION_RAW_VIEW_WALKER);
        // VARIANT com o texto "TaskbarFrame". Em 64 bits, um VARIANT "por valor" é passado como ponteiro para uma cópia.
        frameIdVariant = new Memory(24);
        frameIdVariant.clear();
        frameIdVariant.setShort(0, VT_BSTR);
        frameIdVariant.setPointer(8, OleAuto.INSTANCE.SysAllocString("TaskbarFrame").getPointer());
        frameCondition = out(automation, AUTOMATION_CREATE_PROPERTY_CONDITION, UIA_AUTOMATION_ID_PROPERTY, frameIdVariant);
    }

    /**
     * Lê os filhos do "TaskbarFrame": o grupo "TaskbarFrameRepeater" (Iniciar + ícones dos aplicativos) e os botões
     * avulsos, como o de Widgets. A bandeja do sistema fica fora dele.
     */
    private Layout read() {
        HWND bar = User32.INSTANCE.FindWindow("Shell_TrayWnd", null);
        HWND xaml = bar == null ? null
                : User32.INSTANCE.FindWindowEx(bar, null, "Windows.UI.Composition.DesktopWindowContentBridge", null);
        if (xaml == null) {
            return Layout.UNKNOWN; // Explorer reiniciando, ou barra no estilo do Windows 10
        }
        Pointer root = null;
        Pointer frame = null;
        try {
            // Começa pela área XAML da barra, que não inclui a janela do próprio widget
            root = out(automation, AUTOMATION_ELEMENT_FROM_HANDLE, xaml.getPointer());
            frame = out(root, ELEMENT_FIND_FIRST, TREE_SCOPE_DESCENDANTS, frameCondition);
            if (frame == null) {
                return Layout.UNKNOWN;
            }
            int appsRight = Integer.MIN_VALUE;
            int lastRight = Integer.MIN_VALUE;
            int[] buttons = new int[8];
            int n = 0;
            Pointer child = out(rawWalker, WALKER_FIRST_CHILD, frame);
            while (child != null) {
                Pointer next;
                try {
                    RECT r = new RECT();
                    check(call(child, ELEMENT_BOUNDING_RECTANGLE, r));
                    if (r.right > r.left) {
                        lastRight = Math.max(lastRight, r.right);
                        if ("TaskbarFrameRepeater".equals(automationId(child))) {
                            appsRight = r.right;
                        } else {
                            if (n + 2 > buttons.length) {
                                buttons = Arrays.copyOf(buttons, buttons.length * 2);
                            }
                            buttons[n++] = r.left;
                            buttons[n++] = r.right;
                        }
                    }
                    next = out(rawWalker, WALKER_NEXT_SIBLING, child);
                } finally {
                    release(child);
                }
                child = next;
            }
            if (appsRight != Integer.MIN_VALUE) {
                return new Layout(appsRight, Arrays.copyOf(buttons, n));
            }
            // Outra versão do Windows, sem o grupo: considera tudo como ícones
            return lastRight == Integer.MIN_VALUE ? Layout.UNKNOWN : new Layout(lastRight, new int[0]);
        } finally {
            release(frame);
            release(root);
        }
    }

    private static String automationId(Pointer element) {
        PointerByReference result = new PointerByReference();
        check(call(element, ELEMENT_AUTOMATION_ID, result));
        if (result.getValue() == null) {
            return "";
        }
        WTypes.BSTR id = new WTypes.BSTR(result.getValue());
        try {
            return id.getValue();
        } finally {
            OleAuto.INSTANCE.SysFreeString(id);
        }
    }

    private boolean awaitRefresh() {
        synchronized (signal) {
            long deadline = System.nanoTime() + REFRESH_MS * 1_000_000;
            try {
                while (!refreshRequested) {
                    long left = (deadline - System.nanoTime()) / 1_000_000;
                    if (left <= 0) {
                        break;
                    }
                    signal.wait(left);
                }
            } catch (InterruptedException e) {
                return false;
            }
            refreshRequested = false;
            return true;
        }
    }

    // ----- Chamadas COM "na mão": o JNA não traz as interfaces da UI Automation prontas -----

    /** Chama o método número {@code index} da vtable de um objeto COM e devolve o HRESULT. */
    private static int call(Pointer object, int index, Object... args) {
        Pointer method = object.getPointer(0).getPointer((long) index * Native.POINTER_SIZE);
        Object[] withThis = new Object[args.length + 1];
        withThis[0] = object;
        System.arraycopy(args, 0, withThis, 1, args.length);
        return Function.getFunction(method, Function.ALT_CONVENTION).invokeInt(withThis);
    }

    /** Chama um método cujo último parâmetro é um ponteiro de saída e devolve esse ponteiro (pode ser null). */
    private static Pointer out(Pointer object, int index, Object... args) {
        PointerByReference result = new PointerByReference();
        Object[] withResult = Arrays.copyOf(args, args.length + 1);
        withResult[args.length] = result;
        check(call(object, index, withResult));
        return result.getValue();
    }

    private static void release(Pointer object) {
        if (object != null) {
            call(object, RELEASE);
        }
    }

    private static void check(int hresult) {
        if (hresult < 0) {
            throw new IllegalStateException(String.format("HRESULT 0x%08X", hresult));
        }
    }
}
