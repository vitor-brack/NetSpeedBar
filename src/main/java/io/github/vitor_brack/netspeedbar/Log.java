package io.github.vitor_brack.netspeedbar;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Log simples em %LOCALAPPDATA%\NetSpeedBar\netspeedbar.log. Rodando com javaw não existe console,
 * então é por aqui que dá para descobrir por que algo não apareceu.
 */
final class Log {

    private static final Path FILE = NetSpeedBar.DATA_DIR.resolve("netspeedbar.log");
    private static final long MAX_SIZE = 256 * 1024;

    private Log() {
    }

    static void info(String message) {
        write("INFO", message, null);
    }

    static void error(String message, Throwable error) {
        write("ERRO", message, error);
    }

    private static synchronized void write(String level, String message, Throwable error) {
        StringWriter text = new StringWriter();
        text.append(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
                .append(' ').append(level).append("  ").append(message).append(System.lineSeparator());
        if (error != null) {
            error.printStackTrace(new PrintWriter(text));
        }
        System.err.print(text);
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE) && Files.size(FILE) > MAX_SIZE) {
                Files.delete(FILE);
            }
            Files.writeString(FILE, text.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // sem arquivo de log; a mensagem já foi para o console
        }
    }
}
