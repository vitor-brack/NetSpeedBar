package io.github.vitor_brack.netspeedbar;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HCURSOR;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HMENU;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Funções e constantes da API do Windows que o jna-platform não traz prontas.
 */
final class Win32 {

    private Win32() {
    }

    // Estilos de janela
    static final int WS_POPUP = 0x80000000;
    static final int WS_CHILD = 0x40000000;
    static final int WS_CLIPSIBLINGS = 0x04000000;
    static final int WS_EX_TOOLWINDOW = 0x00000080;
    static final int WS_EX_LAYERED = 0x00080000;

    // SetWindowPos / GetWindow
    static final int SWP_NOACTIVATE = 0x0010;
    static final int SWP_SHOWWINDOW = 0x0040;
    static final int GW_CHILD = 5;

    // Mensagens
    static final int WM_NULL = 0x0000;
    static final int WM_DESTROY = 0x0002;
    static final int WM_CLOSE = 0x0010;
    static final int WM_ENDSESSION = 0x0016;
    static final int WM_SETTINGCHANGE = 0x001A;
    static final int WM_MOUSEACTIVATE = 0x0021;
    static final int WM_DISPLAYCHANGE = 0x007E;
    static final int WM_TIMER = 0x0113;
    static final int WM_MOUSEMOVE = 0x0200;
    static final int WM_LBUTTONDOWN = 0x0201;
    static final int WM_LBUTTONUP = 0x0202;
    static final int WM_RBUTTONUP = 0x0205;
    static final int WM_CAPTURECHANGED = 0x0215;
    static final int WM_MOUSELEAVE = 0x02A3;
    static final int MA_NOACTIVATE = 3;
    static final int MSGFLT_ALLOW = 1;
    static final int TME_LEAVE = 0x0002;

    // Menus
    static final int MF_STRING = 0x0000;
    static final int MF_GRAYED = 0x0001;
    static final int MF_CHECKED = 0x0008;
    static final int MF_POPUP = 0x0010;
    static final int MF_SEPARATOR = 0x0800;
    static final int MF_BYCOMMAND = 0x0000;
    static final int TPM_RIGHTBUTTON = 0x0002;
    static final int TPM_BOTTOMALIGN = 0x0020;
    static final int TPM_NONOTIFY = 0x0080;
    static final int TPM_RETURNCMD = 0x0100;

    // UpdateLayeredWindow
    static final int ULW_ALPHA = 0x0002;
    static final byte AC_SRC_OVER = 0x00;
    static final byte AC_SRC_ALPHA = 0x01;

    static final int IDC_ARROW = 32512;
    static final int MB_ICONERROR = 0x0010;

    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2: o mesmo modo de DPI que o explorer.exe usa. */
    static final Pointer DPI_PER_MONITOR_AWARE_V2 = new Pointer(-4);

    interface User32Ex extends StdCallLibrary {

        User32Ex INSTANCE = Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS);

        ULONG_PTR SetTimer(HWND hWnd, ULONG_PTR nIDEvent, int uElapse, Pointer lpTimerFunc);

        boolean KillTimer(HWND hWnd, ULONG_PTR uIDEvent);

        HMENU CreatePopupMenu();

        boolean AppendMenu(HMENU hMenu, int uFlags, ULONG_PTR uIDNewItem, String lpNewItem);

        boolean CheckMenuRadioItem(HMENU hMenu, int first, int last, int check, int flags);

        boolean DestroyMenu(HMENU hMenu);

        int TrackPopupMenuEx(HMENU hMenu, int uFlags, int x, int y, HWND hWnd, Pointer lptpm);

        boolean ScreenToClient(HWND hWnd, POINT lpPoint);

        boolean TrackMouseEvent(TRACKMOUSEEVENT lpEventTrack);

        HWND SetCapture(HWND hWnd);

        boolean ReleaseCapture();

        int GetDpiForWindow(HWND hWnd);

        boolean ChangeWindowMessageFilterEx(HWND hWnd, int message, int action, Pointer changeFilterStruct);

        Pointer SetThreadDpiAwarenessContext(Pointer dpiContext);

        HCURSOR LoadCursor(HINSTANCE hInstance, Pointer lpCursorName);

        int MessageBox(HWND hWnd, String text, String caption, int type);
    }

    interface IpHlpApi extends Library {

        IpHlpApi INSTANCE = Native.load("iphlpapi", IpHlpApi.class);

        int GetIfTable2(PointerByReference table);

        void FreeMibTable(Pointer memory);
    }

    @Structure.FieldOrder({"cbSize", "dwFlags", "hwndTrack", "dwHoverTime"})
    public static class TRACKMOUSEEVENT extends Structure {

        public int cbSize;
        public int dwFlags;
        public HWND hwndTrack;
        public int dwHoverTime;

        public TRACKMOUSEEVENT() {
            cbSize = size();
        }
    }

    static void registerWindowClass(String name, WinUser.WindowProc proc, HINSTANCE hInstance) {
        WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
        wc.cbSize = wc.size();
        wc.lpfnWndProc = proc;
        wc.hInstance = hInstance;
        wc.hCursor = User32Ex.INSTANCE.LoadCursor(null, new Pointer(IDC_ARROW));
        wc.lpszClassName = name;
        if (User32.INSTANCE.RegisterClassEx(wc).intValue() == 0) {
            throw new IllegalStateException("RegisterClassEx(" + name + ") falhou, erro " + Native.getLastError());
        }
    }

    /** true quando a barra de tarefas usa o tema claro (Configurações > Personalização > Cores). */
    static boolean isTaskbarLightTheme() {
        try {
            return Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme") != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // Funções não documentadas da uxtheme.dll (por ordinal) que o próprio Windows usa para menus no modo escuro.
    // Se não existirem nesta versão do Windows, o menu simplesmente fica claro.
    private static Function refreshImmersiveColorPolicyState; // #104
    private static Function flushMenuThemes;                  // #136

    static void enableDarkMenus(HWND owner) {
        try {
            NativeLibrary.getInstance("uxtheme");
            HMODULE uxtheme = Kernel32.INSTANCE.GetModuleHandle("uxtheme.dll");
            Function allowDarkModeForWindow = Function.getFunction(Kernel32.INSTANCE.GetProcAddress(uxtheme, 133));
            Function setPreferredAppMode = Function.getFunction(Kernel32.INSTANCE.GetProcAddress(uxtheme, 135));
            refreshImmersiveColorPolicyState = Function.getFunction(Kernel32.INSTANCE.GetProcAddress(uxtheme, 104));
            flushMenuThemes = Function.getFunction(Kernel32.INSTANCE.GetProcAddress(uxtheme, 136));
            setPreferredAppMode.invokeInt(new Object[]{1}); // 1 = AllowDark: segue o tema dos aplicativos
            allowDarkModeForWindow.invokeInt(new Object[]{owner, true});
            refreshMenuTheme();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            Log.info("Menu no modo escuro indisponível: " + e);
        }
    }

    static void refreshMenuTheme() {
        if (refreshImmersiveColorPolicyState != null) {
            refreshImmersiveColorPolicyState.invokeVoid(new Object[0]);
            flushMenuThemes.invokeVoid(new Object[0]);
        }
    }
}
