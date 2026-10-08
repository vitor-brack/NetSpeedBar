package io.github.vitor_brack.netspeedbar;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * O widget que aparece DENTRO da barra de tarefas, colado à esquerda da bandeja do sistema.
 *
 * O truque: o Windows 11 não carrega mais "DeskBands" (as barras de ferramentas que o NetSpeedMonitor usava),
 * então criamos uma janela nativa como FILHA da própria janela da barra de tarefas (Shell_TrayWnd, do
 * explorer.exe). Sendo filha dela, a nossa janela:
 * - é desenhada por cima do conteúdo XAML da barra do Windows 11;
 * - acompanha a barra sozinha: se move junto, some com o "ocultar automaticamente" e com jogos/vídeos em tela cheia.
 *
 * A janela é "layered" (WS_EX_LAYERED) e é pintada com UpdateLayeredWindow usando alfa por pixel: o fundo fica
 * transparente e só o texto aparece, como se fizesse parte da barra. O texto é desenhado com Java2D numa imagem
 * que tem o mesmo formato de pixel de um bitmap do Windows (DIB), então basta copiar os bytes para ele.
 */
final class TaskbarWidget {

    interface Listener {

        /** Clique com o botão direito (coordenadas de tela). */
        void onContextMenu(int screenX, int screenY);

        /** O usuário terminou de arrastar o widget para outra posição. */
        void onMoved(int offsetDip);
    }

    private static final String CLASS_NAME = "NetSpeedBar.Widget";
    private static final String TASKBAR_CLASS = "Shell_TrayWnd";
    private static final String TRAY_CLASS = "TrayNotifyWnd";

    private static final Font BASE_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    /** Alfa 1/255: invisível, mas faz a área inteira receber o mouse (pixels com alfa 0 deixam o clique passar). */
    private static final Color HIT_AREA = new Color(0, 0, 0, 1);
    // Opacidade do texto na camada de texto; a cor vem do tema (branco na barra escura, preto na clara)
    private static final Color TEXT = Color.WHITE;
    private static final Color TEXT_DIM = new Color(255, 255, 255, 165);
    private static final int[] CONTRAST_DARK = contrastTable(1.45);
    private static final int[] CONTRAST_LIGHT = contrastTable(1.0);
    private static final Color HOVER_DARK = new Color(255, 255, 255, 22);
    private static final Color HOVER_LIGHT = new Color(0, 0, 0, 14);

    private final User32 user32 = User32.INSTANCE;
    private final Win32.User32Ex user32x = Win32.User32Ex.INSTANCE;
    private final WinUser.WindowProc windowProc = this::windowProc; // referência forte para o GC não coletar o callback
    private final HINSTANCE hInstance;
    private final Listener listener;
    private final TaskbarIcons icons;
    private final SpeedFormat format = SpeedFormat.ofWindowsSettings();
    private final WinUser.BLENDFUNCTION blend = new WinUser.BLENDFUNCTION();

    private HWND hwnd;    // a nossa janela, filha da barra de tarefas
    private HWND taskbar; // Shell_TrayWnd
    private HWND tray;    // TrayNotifyWnd: a área da bandeja do sistema (ícones + relógio)
    private boolean attachErrorLogged;
    private boolean paintErrorLogged;

    // O que está sendo mostrado
    private double downloadRate;
    private double uploadRate;
    private SpeedFormat.Unit unit;
    private boolean lightTheme = Win32.isTaskbarLightTheme();
    private boolean hover;

    // Posição
    private int offsetDip;
    private boolean dragging;
    private int dragStartScreenX;
    private int dragStartOffsetPx;
    private int placedX;
    private int placedY;
    private int placedWidth;
    private int placedHeight;
    private boolean placed;

    // Medidas, que dependem do DPI e da altura da barra
    private int metricsDpi;
    private int metricsHeight;
    private SpeedFormat.Unit metricsUnit;
    private float scale = 1;
    private Font font;
    private float arrowHeight;
    private float padding;
    private float gap;
    private float arrowWidth;
    private float numberWidth; // só cresce (unidades fixas podem passar de 999,99), para não ficar "pulando"
    private float unitWidth;
    private float arrowX;
    private float numberRight;
    private float unitX;
    private int firstBaseline;
    private int secondBaseline;
    private int width;
    private int height;

    // Superfície de desenho: BufferedImage (Java2D) + DIB do Windows com o mesmo formato BGRA pré-multiplicado
    private BufferedImage image;
    private int[] pixels;
    private BufferedImage textLayer;
    private int[] textPixels;
    private HBITMAP dib;
    private Pointer dibBits;
    private HDC memoryDC;
    private HANDLE previousBitmap;

    TaskbarWidget(HINSTANCE hInstance, TaskbarIcons icons, SpeedFormat.Unit unit, int offsetDip, Listener listener) {
        this.hInstance = hInstance;
        this.icons = icons;
        this.unit = unit;
        this.offsetDip = offsetDip;
        this.listener = listener;
        blend.BlendOp = Win32.AC_SRC_OVER;
        blend.SourceConstantAlpha = (byte) 255;
        blend.AlphaFormat = Win32.AC_SRC_ALPHA;
        Win32.registerWindowClass(CLASS_NAME, windowProc, hInstance);
    }

    void setRates(double downloadRate, double uploadRate) {
        this.downloadRate = downloadRate;
        this.uploadRate = uploadRate;
        if (fitNumbers() && hwnd != null) {
            RECT bar = windowRect(taskbar);
            if (bar != null) {
                reposition(bar); // ficou mais largo: continua colado à bandeja, crescendo para a esquerda
            }
        }
        paint();
    }

    void setUnit(SpeedFormat.Unit unit) {
        this.unit = unit;
        refresh();
    }

    void setOffsetDip(int offsetDip) {
        this.offsetDip = offsetDip;
        refresh();
    }

    void refreshTheme() {
        boolean light = Win32.isTaskbarLightTheme();
        if (light != lightTheme) {
            lightTheme = light;
            paint();
        }
    }

    /**
     * Garante que o widget existe, está preso na barra de tarefas atual e no lugar certo.
     * Chamado várias vezes por segundo: é barato quando nada mudou.
     */
    void refresh() {
        if (!attach()) {
            return;
        }
        RECT bar = windowRect(taskbar);
        if (bar == null) {
            return;
        }
        if (updateMetrics(bar.bottom - bar.top)) {
            paint();
        }
        reposition(bar);
    }

    void dispose() {
        destroyWindow();
        freeSurface();
    }

    // ------------------------------------------------------------------------------------------------
    // Janela

    /** Cria a janela como filha da barra de tarefas (de novo, se o Explorer reiniciou e a barra mudou). */
    private boolean attach() {
        if (hwnd != null && taskbar != null && user32.IsWindow(taskbar) && taskbar.equals(user32.GetParent(hwnd))) {
            return true; // nada mudou
        }
        HWND bar = user32.FindWindow(TASKBAR_CLASS, null);
        if (bar == null) {
            return false; // Explorer ainda não abriu (logon) ou está reiniciando
        }
        destroyWindow();
        taskbar = bar;
        tray = null;
        hwnd = user32.CreateWindowEx(Win32.WS_EX_LAYERED, CLASS_NAME, NetSpeedBar.APP_NAME,
                Win32.WS_CHILD | Win32.WS_CLIPSIBLINGS, 0, 0, 0, 0, taskbar, null, hInstance, null);
        if (hwnd == null) {
            if (!attachErrorLogged) {
                Log.error("Não foi possível criar a janela dentro da barra de tarefas, erro " + Native.getLastError(), null);
                attachErrorLogged = true;
            }
            return false;
        }
        attachErrorLogged = false;
        placed = false;
        metricsDpi = 0; // força recalcular as medidas e repintar
        Log.info("Widget preso na barra de tarefas");
        return true;
    }

    private void destroyWindow() {
        HWND old = hwnd;
        hwnd = null;
        if (old != null && user32.IsWindow(old)) {
            user32.DestroyWindow(old);
        }
    }

    /**
     * Põe o widget no vão livre da barra e por cima do conteúdo dela (ordem Z). Posição padrão: colado à esquerda da
     * bandeja. Arrastando, ele vai para a esquerda até a "parede": o último ícone dos aplicativos. Se abrirem mais
     * aplicativos e os ícones chegarem nele, ele é empurrado; quando fecham, volta para onde o usuário deixou.
     */
    private void reposition(RECT bar) {
        int margin = Math.round(2 * scale);
        int wall = bar.left;
        int right = trayLeft(bar);
        TaskbarIcons.Layout layout = icons.layout();
        if (layout.known()) {
            wall = Math.max(wall, layout.appsRight());
            int[] buttons = layout.buttons();
            for (int i = 0; i < buttons.length; i += 2) {
                if (buttons[i] >= layout.appsRight() && buttons[i] < right) {
                    right = buttons[i]; // botão avulso entre os ícones e a bandeja (ex.: Widgets com a barra à esquerda)
                }
            }
        }
        int defaultX = right - margin - width;
        int minOffset = Math.min(0, wall + margin - defaultX);
        int offsetPx = Math.clamp(Math.round(offsetDip * scale), minOffset, 0);
        if (dragging) {
            offsetDip = Math.round(offsetPx / scale); // não deixa o arraste passar dos limites da barra
        }

        // Filhas usam coordenadas relativas à janela-mãe
        POINT p = new POINT(defaultX + offsetPx, bar.top);
        user32x.ScreenToClient(taskbar, p);

        boolean onTop = hwnd.equals(user32.GetWindow(taskbar, new DWORD(Win32.GW_CHILD)));
        if (placed && onTop && p.x == placedX && p.y == placedY && width == placedWidth && height == placedHeight) {
            return;
        }
        // hWndInsertAfter = null (HWND_TOP): acima da janela XAML que desenha a barra do Windows 11
        user32.SetWindowPos(hwnd, null, p.x, p.y, width, height, Win32.SWP_NOACTIVATE | Win32.SWP_SHOWWINDOW);
        placed = true;
        placedX = p.x;
        placedY = p.y;
        placedWidth = width;
        placedHeight = height;
    }

    /** Borda esquerda da bandeja do sistema (TrayNotifyWnd acompanha a área dos ícones + relógio). */
    private int trayLeft(RECT bar) {
        if (tray == null || !user32.IsWindow(tray)) {
            tray = user32.FindWindowEx(taskbar, null, TRAY_CLASS, null);
        }
        RECT r = tray == null ? null : windowRect(tray);
        if (r != null && r.right > r.left && r.left > bar.left && r.left <= bar.right) {
            return r.left;
        }
        return bar.right - Math.round(220 * scale); // estimativa, caso a bandeja não seja encontrada
    }

    private static RECT windowRect(HWND window) {
        RECT r = new RECT();
        return User32.INSTANCE.GetWindowRect(window, r) ? r : null;
    }

    private LRESULT windowProc(HWND window, int message, WPARAM wParam, LPARAM lParam) {
        try {
            switch (message) {
                case Win32.WM_MOUSEACTIVATE:
                    return new LRESULT(Win32.MA_NOACTIVATE); // clicar no widget não tira o foco do app atual
                case Win32.WM_MOUSEMOVE:
                    onMouseMove(window, lParam);
                    return new LRESULT(0);
                case Win32.WM_MOUSELEAVE:
                    hover = false;
                    paint();
                    return new LRESULT(0);
                case Win32.WM_LBUTTONDOWN:
                    icons.refreshSoon(); // posição dos ícones bem atualizada durante o arraste
                    dragging = true;
                    dragStartScreenX = screenX(window, lParam);
                    dragStartOffsetPx = Math.round(offsetDip * scale);
                    user32x.SetCapture(window);
                    paint();
                    return new LRESULT(0);
                case Win32.WM_LBUTTONUP:
                    finishDrag();
                    user32x.ReleaseCapture();
                    return new LRESULT(0);
                case Win32.WM_CAPTURECHANGED:
                    finishDrag();
                    return new LRESULT(0);
                case Win32.WM_RBUTTONUP: {
                    POINT cursor = new POINT();
                    user32.GetCursorPos(cursor);
                    listener.onContextMenu(cursor.x, cursor.y);
                    return new LRESULT(0);
                }
                case Win32.WM_DESTROY:
                    if (window.equals(hwnd)) {
                        hwnd = null; // a barra foi destruída (Explorer reiniciou): refresh() cria outra janela
                    }
                    hover = false;
                    dragging = false;
                    break;
                default:
                    break;
            }
        } catch (RuntimeException | Error e) {
            Log.error("Erro tratando a mensagem 0x" + Integer.toHexString(message) + " do widget", e);
        }
        return user32.DefWindowProc(window, message, wParam, lParam);
    }

    private void onMouseMove(HWND window, LPARAM lParam) {
        if (!hover) {
            hover = true;
            Win32.TRACKMOUSEEVENT track = new Win32.TRACKMOUSEEVENT();
            track.dwFlags = Win32.TME_LEAVE; // pede um WM_MOUSELEAVE quando o mouse sair
            track.hwndTrack = window;
            user32x.TrackMouseEvent(track);
            paint();
        }
        if (dragging) {
            int offsetPx = dragStartOffsetPx + screenX(window, lParam) - dragStartScreenX;
            offsetDip = Math.round(offsetPx / scale);
            refresh();
        }
    }

    private void finishDrag() {
        if (!dragging) {
            return;
        }
        dragging = false;
        paint();
        if (Math.round(offsetDip * scale) != dragStartOffsetPx) {
            listener.onMoved(offsetDip);
        }
    }

    /** Converte o X do mouse (relativo à janela, com sinal) para coordenadas de tela. */
    private static int screenX(HWND window, LPARAM lParam) {
        RECT r = windowRect(window);
        return (r == null ? 0 : r.left) + (short) (lParam.longValue() & 0xFFFF);
    }

    // ------------------------------------------------------------------------------------------------
    // Desenho

    /** Recalcula fonte e tamanho quando o DPI, a altura da barra ou a unidade mudam. */
    private boolean updateMetrics(int barHeight) {
        int dpi = user32x.GetDpiForWindow(taskbar);
        if (dpi <= 0) {
            dpi = 96;
        }
        if (font != null && dpi == metricsDpi && barHeight == metricsHeight && unit == metricsUnit) {
            return false;
        }
        metricsDpi = dpi;
        metricsHeight = barHeight;
        metricsUnit = unit;
        scale = dpi / 96f;

        // 12 px a 100% (mesmo tamanho do relógio), menor se a barra for compacta
        font = BASE_FONT.deriveFont(Math.min(12 * scale, (barHeight - 2 * scale) / 2.75f));
        FontRenderContext frc = new FontRenderContext(null, true, false);
        arrowHeight = (float) font.createGlyphVector(frc, "↑").getVisualBounds().getHeight(); // altura da seta da fonte

        // Colunas de largura fixa: seta | número alinhado à direita | unidade
        arrowWidth = 3 * arrowStroke();
        numberWidth = maxWidth(format.widestNumbers(), frc);
        unitWidth = maxWidth(SpeedFormat.symbols(unit), frc);
        padding = 6 * scale;
        gap = 3 * scale;
        height = barHeight;
        layoutColumns();
        fitNumbers();

        // Duas linhas centralizadas pela altura dos dígitos, como a hora e a data do relógio
        LineMetrics lm = font.getLineMetrics("0", frc);
        float lineSpacing = lm.getAscent() + lm.getDescent();
        float digitHeight = (float) font.createGlyphVector(frc, "0").getVisualBounds().getHeight();
        firstBaseline = Math.round((height + digitHeight - lineSpacing) / 2);
        secondBaseline = Math.round(firstBaseline + lineSpacing);
        return true;
    }

    private void layoutColumns() {
        arrowX = padding;
        numberRight = arrowX + arrowWidth + gap + numberWidth;
        unitX = numberRight + gap;
        width = (int) Math.ceil(unitX + unitWidth + padding);
    }

    /** Alarga a coluna dos números se o valor atual não couber (ex.: "12.345,67 KB/s"). Devolve true se alargou. */
    private boolean fitNumbers() {
        if (font == null) {
            return false;
        }
        FontRenderContext frc = new FontRenderContext(null, true, false);
        float needed = Math.max(textWidth(format.speed(uploadRate, unit)[0], frc),
                textWidth(format.speed(downloadRate, unit)[0], frc));
        if (needed <= numberWidth) {
            return false;
        }
        numberWidth = needed;
        layoutColumns();
        return true;
    }

    private void paint() {
        if (hwnd == null || font == null) {
            return;
        }
        ensureSurface(width, height);

        // 1) Fundo: alfa mínimo (para receber o mouse) ou o destaque arredondado com o mouse em cima
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setColor(HIT_AREA);
        g.fillRect(0, 0, width, height);
        if (hover || dragging) {
            float inset = 4 * scale;
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(lightTheme ? HOVER_LIGHT : HOVER_DARK);
            g.fill(new RoundRectangle2D.Float(0, inset, width, height - 2 * inset, 8 * scale, 8 * scale));
        }
        g.dispose();

        // 2) Texto numa camada separada, em branco: o alfa de cada pixel é quanto a letra cobre aquele pixel
        Arrays.fill(textPixels, 0);
        g = textLayer.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE); // setas no pixel exato
        g.setFont(font);
        drawRow(g, true, uploadRate, firstBaseline);
        drawRow(g, false, downloadRate, secondBaseline);
        g.dispose();

        // 3) Junta as camadas na cor do tema, reforçando o contraste (como o Windows faz com texto claro no escuro)
        int[] contrast = lightTheme ? CONTRAST_LIGHT : CONTRAST_DARK;
        int color = lightTheme ? 0 : 255;
        for (int i = 0; i < pixels.length; i++) {
            int alpha = contrast[textPixels[i] >>> 24];
            if (alpha == 0) {
                continue;
            }
            int below = pixels[i];
            int keep = 255 - alpha;
            pixels[i] = (alpha + div255((below >>> 24) * keep)) << 24
                    | div255(color * alpha + ((below >>> 16) & 0xFF) * keep) << 16
                    | div255(color * alpha + ((below >>> 8) & 0xFF) * keep) << 8
                    | div255(color * alpha + (below & 0xFF) * keep);
        }

        dibBits.write(0, pixels, 0, pixels.length);
        boolean ok = user32.UpdateLayeredWindow(hwnd, null, null, new WinUser.SIZE(width, height), memoryDC,
                new POINT(0, 0), 0, blend, Win32.ULW_ALPHA);
        if (!ok && !paintErrorLogged) {
            Log.error("UpdateLayeredWindow falhou, erro " + Native.getLastError(), null);
            paintErrorLogged = true;
        }
    }

    private void drawRow(Graphics2D g, boolean upload, double bytesPerSecond, int baseline) {
        String[] text = format.speed(bytesPerSecond, unit);
        g.setColor(TEXT);
        g.fill(arrow(baseline, upload));
        g.drawString(text[0], numberRight - textWidth(text[0], g.getFontRenderContext()), baseline);
        g.setColor(TEXT_DIM);
        g.drawString(text[1], unitX, baseline);
    }

    /** Espessura da haste da seta: 2 px a 100% de escala, acompanhando o DPI. */
    private float arrowStroke() {
        return Math.max(2, Math.round(arrowHeight * 0.24f));
    }

    /**
     * Seta desenhada (haste + ponta cheia) com a mesma altura da seta da fonte, só que mais "gorda": o caractere ↑
     * do Segoe UI tem traço de 1 px e quase some na barra. As bordas caem em pixels inteiros, para ficar nítida.
     */
    private Path2D arrow(int baseline, boolean up) {
        float stroke = arrowStroke();
        float head = 3 * stroke;
        float headHeight = Math.min(arrowHeight * 0.5f, head * 0.75f);
        float center = Math.round(arrowX) + head / 2;
        float top = Math.round(baseline - arrowHeight);
        float tip = up ? top : baseline;
        float tail = up ? baseline : top;
        float neck = up ? tip + headHeight : tip - headHeight;
        Path2D.Float path = new Path2D.Float();
        path.moveTo(center, tip);
        path.lineTo(center + head / 2, neck);
        path.lineTo(center + stroke / 2, neck);
        path.lineTo(center + stroke / 2, tail);
        path.lineTo(center - stroke / 2, tail);
        path.lineTo(center - stroke / 2, neck);
        path.lineTo(center - head / 2, neck);
        path.closePath();
        return path;
    }

    private static int div255(int value) {
        return (value + 127) / 255;
    }

    /** Curva de gama aplicada à cobertura do texto: valores acima de 1 deixam as letras mais "cheias". */
    private static int[] contrastTable(double gamma) {
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            table[i] = (int) Math.round(255 * Math.pow(i / 255.0, 1 / gamma));
        }
        return table;
    }

    private float textWidth(String text, FontRenderContext frc) {
        return (float) font.getStringBounds(text, frc).getWidth();
    }

    private float maxWidth(String[] texts, FontRenderContext frc) {
        float max = 0;
        for (String text : texts) {
            max = Math.max(max, textWidth(text, frc));
        }
        return max;
    }

    /** (Re)cria o DIB de 32 bits e a BufferedImage do mesmo tamanho. */
    private void ensureSurface(int w, int h) {
        if (image != null && image.getWidth() == w && image.getHeight() == h) {
            return;
        }
        freeSurface();
        WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
        info.bmiHeader.biSize = info.bmiHeader.size();
        info.bmiHeader.biWidth = w;
        info.bmiHeader.biHeight = -h; // negativo: linhas de cima para baixo, igual à BufferedImage
        info.bmiHeader.biPlanes = 1;
        info.bmiHeader.biBitCount = 32;
        info.bmiHeader.biCompression = WinGDI.BI_RGB;
        PointerByReference bits = new PointerByReference();
        dib = GDI32.INSTANCE.CreateDIBSection(null, info, WinGDI.DIB_RGB_COLORS, bits, null, 0);
        if (dib == null) {
            throw new IllegalStateException("CreateDIBSection falhou, erro " + Native.getLastError());
        }
        dibBits = bits.getValue();
        memoryDC = GDI32.INSTANCE.CreateCompatibleDC(null);
        previousBitmap = GDI32.INSTANCE.SelectObject(memoryDC, dib);
        // ARGB pré-multiplicado em int = bytes B,G,R,A na memória (little-endian): exatamente o que o DIB espera
        image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        textLayer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
        textPixels = ((DataBufferInt) textLayer.getRaster().getDataBuffer()).getData();
    }

    private void freeSurface() {
        if (memoryDC != null) {
            GDI32.INSTANCE.SelectObject(memoryDC, previousBitmap);
            GDI32.INSTANCE.DeleteDC(memoryDC);
            memoryDC = null;
        }
        if (dib != null) {
            GDI32.INSTANCE.DeleteObject(dib);
            dib = null;
        }
        image = null;
        pixels = null;
        textLayer = null;
        textPixels = null;
        dibBits = null;
    }
}
