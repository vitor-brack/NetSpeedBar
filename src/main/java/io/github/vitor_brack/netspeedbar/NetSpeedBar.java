package io.github.vitor_brack.netspeedbar;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HMENU;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * NetSpeedBar: velocidade de download e upload dentro da barra de tarefas do Windows 11,
 * colada à esquerda da bandeja do sistema (canto inferior direito).
 *
 * Uma janela "controladora" invisível recebe os timers e os avisos do sistema; a cada intervalo os contadores
 * das placas de rede são lidos (NetworkMonitor) e o widget dentro da barra (TaskbarWidget) é redesenhado.
 *
 * Uso: clique direito no widget abre as opções; arrastar com o botão esquerdo muda a posição.
 */
public final class NetSpeedBar {

    static final String APP_NAME = "NetSpeedBar";
    static final Path DATA_DIR = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")), APP_NAME);

    private static final String CONTROLLER_CLASS = "NetSpeedBar.Controller";
    private static final ULONG_PTR SAMPLE_TIMER = new ULONG_PTR(1);
    private static final ULONG_PTR LAYOUT_TIMER = new ULONG_PTR(2);
    /** A bandeja muda de largura quando ícones aparecem/somem: a posição é conferida 4x por segundo. */
    private static final int LAYOUT_INTERVAL_MS = 250;
    private static final int[] INTERVALS_MS = {500, 1000, 2000};
    private static final String[] INTERVAL_LABELS = {"0,5 segundo", "1 segundo", "2 segundos"};

    // Comandos do menu
    private static final int CMD_AUTO = 100;
    private static final int CMD_ADAPTER = 101; // 101..199: placas de rede listadas
    private static final int CMD_UNIT = 200; // 200..209: SpeedFormat.Unit
    private static final int CMD_INTERVAL = 300;
    private static final int CMD_AUTOSTART = 400;
    private static final int CMD_RESET_POSITION = 401;
    private static final int CMD_EXIT = 900;

    private final User32 user32 = User32.INSTANCE;
    private final Win32.User32Ex user32x = Win32.User32Ex.INSTANCE;
    private final WinUser.WindowProc controllerProc = this::controllerProc; // referência forte para o GC não coletar
    private final Settings settings = new Settings();
    private final NetworkMonitor network = new NetworkMonitor();
    private final SpeedFormat format = SpeedFormat.ofWindowsSettings();
    private TaskbarWidget widget;
    private HWND controller;
    private int taskbarCreatedMessage;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true"); // só desenhamos em imagens: não precisa de Swing/AWT
        if (args.length > 0 && args[0].equals("--interfaces")) { // diagnóstico: java -jar NetSpeedBar.jar --interfaces
            System.out.print(NetworkMonitor.describeAll());
            System.out.println("\nNo menu \"Placa de rede\":");
            new NetworkMonitor().adapters().forEach(a -> System.out.println("  " + a.label() + (a.physical() ? "  <- física" : "")));
            return;
        }
        try {
            new NetSpeedBar().run();
        } catch (Throwable e) {
            Log.error("Erro fatal", e);
            Win32.User32Ex.INSTANCE.MessageBox(null, "O NetSpeedBar parou por causa de um erro:\n\n" + e,
                    APP_NAME, Win32.MB_ICONERROR);
            System.exit(1);
        }
        System.exit(0);
    }

    private void run() throws InterruptedException {
        // A barra de tarefas trabalha em pixels físicos (Per-Monitor V2); usamos o mesmo modo para as medidas baterem
        user32x.SetThreadDpiAwarenessContext(Win32.DPI_PER_MONITOR_AWARE_V2);
        closePreviousInstances();

        HINSTANCE hInstance = Kernel32.INSTANCE.GetModuleHandle(null);
        network.select(settings.adapterLuid());
        network.sample(); // primeira leitura: serve de referência para a próxima
        TaskbarIcons icons = new TaskbarIcons();
        icons.start();
        widget = new TaskbarWidget(hInstance, icons, settings.unit(), settings.offsetDip(), new TaskbarWidget.Listener() {
            @Override
            public void onContextMenu(int screenX, int screenY) {
                showMenu(screenX, screenY);
            }

            @Override
            public void onMoved(int offsetDip) {
                settings.setOffsetDip(offsetDip);
            }
        });

        // O Explorer manda esta mensagem para todas as janelas quando recria a barra (ex.: depois de travar)
        taskbarCreatedMessage = user32.RegisterWindowMessage("TaskbarCreated");
        Win32.registerWindowClass(CONTROLLER_CLASS, controllerProc, hInstance);
        controller = user32.CreateWindowEx(Win32.WS_EX_TOOLWINDOW, CONTROLLER_CLASS, APP_NAME, Win32.WS_POPUP,
                0, 0, 0, 0, null, null, hInstance, null);
        if (controller == null) {
            throw new IllegalStateException("CreateWindowEx falhou, erro " + Native.getLastError());
        }
        // Se esta instância rodar como administrador, o Windows bloqueia mensagens vindas de programas comuns.
        // Libera o WM_CLOSE para uma nova instância (ou o instalador) sempre conseguir fechar esta.
        user32x.ChangeWindowMessageFilterEx(controller, Win32.WM_CLOSE, Win32.MSGFLT_ALLOW, null);
        Win32.enableDarkMenus(controller);

        widget.refresh();
        user32x.SetTimer(controller, SAMPLE_TIMER, settings.intervalMs(), null);
        user32x.SetTimer(controller, LAYOUT_TIMER, LAYOUT_INTERVAL_MS, null);
        Path exe = Settings.installedExe();
        Log.info(exe == null ? "Iniciado (modo de desenvolvimento)" : "Iniciado: " + exe);

        WinUser.MSG msg = new WinUser.MSG();
        while (user32.GetMessage(msg, null, 0, 0) > 0) {
            user32.TranslateMessage(msg);
            user32.DispatchMessage(msg);
        }
        Log.info("Encerrado");
    }

    /** Só uma instância por vez: a nova fecha as anteriores (prático ao rodar de novo pelo NetBeans). */
    private void closePreviousInstances() throws InterruptedException {
        HWND previous;
        while ((previous = user32.FindWindow(CONTROLLER_CLASS, null)) != null) {
            Log.info("Fechando a instância que já estava aberta");
            IntByReference pid = new IntByReference();
            user32.GetWindowThreadProcessId(previous, pid);
            Optional<ProcessHandle> process = ProcessHandle.of(pid.getValue());
            user32.PostMessage(previous, Win32.WM_CLOSE, new WPARAM(0), new LPARAM(0));
            for (int i = 0; i < 50 && user32.IsWindow(previous); i++) {
                Thread.sleep(100);
            }
            if (user32.IsWindow(previous)) {
                Log.info("A instância anterior não respondeu; seguindo mesmo assim");
                return;
            }
            // Espera também o processo terminar (e soltar os arquivos), não só a janela sumir
            try {
                if (process.isPresent()) {
                    process.get().onExit().get(5, TimeUnit.SECONDS);
                }
            } catch (ExecutionException | TimeoutException e) {
                Log.info("A instância anterior demorou para terminar");
            }
        }
    }

    private LRESULT controllerProc(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        try {
            if (message == Win32.WM_TIMER) {
                if (wParam.longValue() == SAMPLE_TIMER.longValue()) {
                    network.sample();
                    widget.refreshTheme();
                    widget.setRates(network.downloadRate(), network.uploadRate());
                } else {
                    widget.refresh();
                }
                return new LRESULT(0);
            } else if (message == taskbarCreatedMessage || message == Win32.WM_DISPLAYCHANGE) {
                widget.refresh();
            } else if (message == Win32.WM_SETTINGCHANGE) {
                widget.refreshTheme();
                Win32.refreshMenuTheme();
            } else if (message == Win32.WM_CLOSE
                    || (message == Win32.WM_ENDSESSION && wParam.longValue() != 0)) {
                // WM_CLOSE: outra instância ou o instalador pedindo para fechar. WM_ENDSESSION: logoff/desligamento.
                user32.DestroyWindow(window);
                return new LRESULT(0);
            } else if (message == Win32.WM_DESTROY) {
                user32x.KillTimer(window, SAMPLE_TIMER);
                user32x.KillTimer(window, LAYOUT_TIMER);
                widget.dispose();
                user32.PostQuitMessage(0);
                return new LRESULT(0);
            }
        } catch (RuntimeException | Error e) {
            Log.error("Erro tratando a mensagem 0x" + Integer.toHexString(message), e);
        }
        return user32.DefWindowProc(window, message, wParam, lParam);
    }

    // ------------------------------------------------------------------------------------------------
    // Menu do clique direito (menu nativo do Windows, escuro quando o tema é escuro)

    private void showMenu(int x, int y) {
        List<NetworkMonitor.Adapter> adapters = network.adapters();
        HMENU menu = user32x.CreatePopupMenu();
        try {
            addItem(menu, Win32.MF_GRAYED, 0, "Desde que abriu:   ↓ " + format.size(network.totalDownloaded())
                    + "    ↑ " + format.size(network.totalUploaded()));
            addSeparator(menu);

            HMENU adapterMenu = user32x.CreatePopupMenu();
            addItem(adapterMenu, 0, CMD_AUTO, "Automático (todas as placas físicas)");
            int checkedAdapter = CMD_AUTO;
            if (!adapters.isEmpty()) {
                addSeparator(adapterMenu);
            }
            for (int i = 0; i < adapters.size() && i < 99; i++) {
                addItem(adapterMenu, 0, CMD_ADAPTER + i, adapters.get(i).label());
                if (adapters.get(i).luid() == settings.adapterLuid()) {
                    checkedAdapter = CMD_ADAPTER + i;
                }
            }
            check(adapterMenu, checkedAdapter);
            addSubmenu(menu, adapterMenu, "Placa de rede");

            HMENU unitMenu = user32x.CreatePopupMenu();
            for (SpeedFormat.Unit unit : SpeedFormat.Unit.values()) {
                if (unit.power == 0) {
                    addSeparator(unitMenu); // grupos: automáticas | bytes | bits
                }
                addItem(unitMenu, 0, CMD_UNIT + unit.ordinal(), unit.label);
            }
            check(unitMenu, CMD_UNIT + settings.unit().ordinal());
            addSubmenu(menu, unitMenu, "Unidade");

            HMENU intervalMenu = user32x.CreatePopupMenu();
            for (int i = 0; i < INTERVALS_MS.length; i++) {
                addItem(intervalMenu, 0, CMD_INTERVAL + i, INTERVAL_LABELS[i]);
                if (INTERVALS_MS[i] == settings.intervalMs()) {
                    check(intervalMenu, CMD_INTERVAL + i);
                }
            }
            addSubmenu(menu, intervalMenu, "Atualizar a cada");
            addSeparator(menu);

            boolean autostart = settings.isAutostartEnabled();
            boolean canToggle = autostart || Settings.installedExe() != null; // ligar exige a versão instalada
            addItem(menu, (autostart ? Win32.MF_CHECKED : 0) | (canToggle ? 0 : Win32.MF_GRAYED), CMD_AUTOSTART,
                    canToggle ? "Iniciar com o Windows" : "Iniciar com o Windows (só na versão instalada)");
            if (settings.offsetDip() != 0) {
                addItem(menu, 0, CMD_RESET_POSITION, "Voltar à posição padrão");
            }
            addSeparator(menu);
            addItem(menu, 0, CMD_EXIT, "Sair");

            Win32.refreshMenuTheme();
            user32.SetForegroundWindow(controller); // sem isto o menu não fecha ao clicar fora dele
            int command = user32x.TrackPopupMenuEx(menu,
                    Win32.TPM_RETURNCMD | Win32.TPM_NONOTIFY | Win32.TPM_RIGHTBUTTON | Win32.TPM_BOTTOMALIGN,
                    x, y, controller, null);
            user32.PostMessage(controller, Win32.WM_NULL, new WPARAM(0), new LPARAM(0));
            execute(command, adapters);
        } finally {
            user32x.DestroyMenu(menu); // destrói os submenus junto
        }
    }

    private void execute(int command, List<NetworkMonitor.Adapter> adapters) {
        if (command == CMD_AUTO || (command >= CMD_ADAPTER && command < CMD_ADAPTER + adapters.size())) {
            long luid = command == CMD_AUTO ? NetworkMonitor.AUTO : adapters.get(command - CMD_ADAPTER).luid();
            settings.setAdapterLuid(luid);
            network.select(luid);
        } else if (command >= CMD_UNIT && command < CMD_UNIT + SpeedFormat.Unit.values().length) {
            SpeedFormat.Unit unit = SpeedFormat.Unit.values()[command - CMD_UNIT];
            settings.setUnit(unit);
            widget.setUnit(unit);
        } else if (command >= CMD_INTERVAL && command < CMD_INTERVAL + INTERVALS_MS.length) {
            settings.setIntervalMs(INTERVALS_MS[command - CMD_INTERVAL]);
            user32x.SetTimer(controller, SAMPLE_TIMER, settings.intervalMs(), null); // mesmo ID substitui o timer
        } else if (command == CMD_AUTOSTART) {
            toggleAutostart();
        } else if (command == CMD_RESET_POSITION) {
            settings.setOffsetDip(0);
            widget.setOffsetDip(0);
        } else if (command == CMD_EXIT) {
            user32.DestroyWindow(controller);
        }
    }

    private void toggleAutostart() {
        try {
            settings.setAutostart(!settings.isAutostartEnabled());
        } catch (RuntimeException e) {
            Log.error("Não foi possível mudar a inicialização automática", e);
            user32x.MessageBox(null, "Não foi possível mudar a inicialização automática:\n\n" + e, APP_NAME, Win32.MB_ICONERROR);
        }
    }

    private void addItem(HMENU menu, int flags, int id, String text) {
        user32x.AppendMenu(menu, Win32.MF_STRING | flags, new ULONG_PTR(id), text);
    }

    private void addSeparator(HMENU menu) {
        user32x.AppendMenu(menu, Win32.MF_SEPARATOR, new ULONG_PTR(0), null);
    }

    private void addSubmenu(HMENU menu, HMENU submenu, String text) {
        user32x.AppendMenu(menu, Win32.MF_POPUP, new ULONG_PTR(Pointer.nativeValue(submenu.getPointer())), text);
    }

    private void check(HMENU menu, int id) {
        user32x.CheckMenuRadioItem(menu, id, id, id, Win32.MF_BYCOMMAND);
    }
}
