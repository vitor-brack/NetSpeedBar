package io.github.vitor_brack.netspeedbar;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Preferências do usuário, salvas em %LOCALAPPDATA%\NetSpeedBar\config.properties.
 */
final class Settings {

    private static final Path FILE = NetSpeedBar.DATA_DIR.resolve("config.properties");
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private final Properties props = new Properties();

    Settings() {
        if (Files.isRegularFile(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                Log.error("Não foi possível ler " + FILE, e);
            }
        }
    }

    long adapterLuid() {
        return parseLong("adapter", NetworkMonitor.AUTO);
    }

    void setAdapterLuid(long luid) {
        set("adapter", Long.toString(luid));
    }

    SpeedFormat.Unit unit() {
        String value = props.getProperty("unit", "");
        if (value.equals("bits")) {
            return SpeedFormat.Unit.AUTO_BITS; // como a primeira versão gravava
        }
        try {
            return SpeedFormat.Unit.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SpeedFormat.Unit.AUTO_BYTES;
        }
    }

    void setUnit(SpeedFormat.Unit unit) {
        set("unit", unit.name());
    }

    int intervalMs() {
        return Math.clamp(parseLong("intervalMs", 1000), 250, 10_000);
    }

    void setIntervalMs(int ms) {
        set("intervalMs", Integer.toString(ms));
    }

    /** Deslocamento horizontal escolhido arrastando o widget, em pixels lógicos (100% de escala). */
    int offsetDip() {
        return Math.clamp(parseLong("offset", 0), -10_000, 10_000);
    }

    void setOffsetDip(int dip) {
        set("offset", Integer.toString(dip));
    }

    private long parseLong(String key, long fallback) {
        try {
            return Long.parseLong(props.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void set(String key, String value) {
        props.setProperty(key, value);
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                props.store(writer, NetSpeedBar.APP_NAME);
            }
        } catch (IOException e) {
            Log.error("Não foi possível salvar " + FILE, e);
        }
    }

    // ----- Iniciar com o Windows: valor na chave Run do usuário (não precisa de administrador) -----

    /**
     * O NetSpeedBar.exe da versão instalada (o launcher gerado pelo jpackage informa o próprio caminho nesta
     * propriedade), ou null quando o programa roda pelo NetBeans ou por "java -jar".
     */
    static Path installedExe() {
        String path = System.getProperty("jpackage.app-path");
        return path == null || path.isBlank() ? null : Path.of(path);
    }

    boolean isAutostartEnabled() {
        return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, NetSpeedBar.APP_NAME);
    }

    /** O instalador já liga isto; pelo menu dá para desligar e religar (religar só na versão instalada). */
    void setAutostart(boolean enabled) {
        Path exe = installedExe();
        if (enabled && exe != null) {
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NetSpeedBar.APP_NAME,
                    "\"" + exe + "\"");
        } else if (!enabled && isAutostartEnabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, NetSpeedBar.APP_NAME);
        }
    }
}
