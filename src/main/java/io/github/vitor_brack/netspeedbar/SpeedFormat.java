package io.github.vitor_brack.netspeedbar;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Locale;

/**
 * Formata velocidades sempre com duas casas decimais ("0.00", "12.34", "123.45").
 * Bytes usam base 1024 (como o Windows); bits usam base 1000 (como os provedores de internet).
 */
final class SpeedFormat {

    /** Unidades do menu. As automáticas trocam sozinhas entre K, M e G; as outras ficam fixas. */
    enum Unit {
        AUTO_BYTES(false, -1, "Automático em bytes\tKB/s, MB/s, GB/s"),
        AUTO_BITS(true, -1, "Automático em bits\tKbps, Mbps, Gbps"),
        BYTES(false, 0, "B/s\tbytes por segundo"),
        KILOBYTES(false, 1, "KB/s\tkilobytes por segundo"),
        MEGABYTES(false, 2, "MB/s\tmegabytes por segundo"),
        GIGABYTES(false, 3, "GB/s\tgigabytes por segundo"),
        BITS(true, 0, "bps\tbits por segundo"),
        KILOBITS(true, 1, "Kbps\tkilobits por segundo"),
        MEGABITS(true, 2, "Mbps\tmegabits por segundo"),
        GIGABITS(true, 3, "Gbps\tgigabits por segundo");

        final boolean bits;
        final int power;    // potência fixa de 1024 (bytes) ou 1000 (bits); -1 = automática
        final String label; // texto do menu; o "\t" alinha a descrição à direita

        Unit(boolean bits, int power, String label) {
            this.bits = bits;
            this.power = power;
            this.label = label;
        }

        boolean isAuto() {
            return power < 0;
        }
    }

    private static final String[] BYTE_SYMBOLS = {"B/s", "KB/s", "MB/s", "GB/s"};
    private static final String[] BIT_SYMBOLS = {"bps", "Kbps", "Mbps", "Gbps"};
    private static final String[] SIZE_SYMBOLS = {"B", "KB", "MB", "GB", "TB"};

    /** "999.99" é o maior número antes de trocar para a próxima unidade (nunca aparece "1000.00 Kbps"). */
    private static final double NEXT_UNIT = 999.995;

    private final DecimalFormat number;

    /** Sempre ponto decimal e vírgula de milhar (os do Locale.ROOT), qualquer que seja o idioma do Windows. */
    SpeedFormat() {
        number = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)); // separador de milhar só aparece nas unidades fixas
        number.setRoundingMode(RoundingMode.HALF_UP);
    }

    /** Devolve {número, unidade}, ex.: {"12.34", "Mbps"}. */
    String[] speed(double bytesPerSecond, Unit unit) {
        double value = Math.max(0, bytesPerSecond) * (unit.bits ? 8 : 1);
        double base = unit.bits ? 1000 : 1024;
        String[] symbols = unit.bits ? BIT_SYMBOLS : BYTE_SYMBOLS;
        if (unit.isAuto()) {
            return scale(value, base, symbols);
        }
        return new String[]{number.format(value / Math.pow(base, unit.power)), symbols[unit.power]};
    }

    /** Quantidade total de dados, ex.: "1.25 GB". */
    String size(long bytes) {
        String[] parts = scale(Math.max(0, bytes), 1024, SIZE_SYMBOLS);
        return parts[0] + " " + parts[1];
    }

    /**
     * Escala automática que começa no "K": pouco ou nenhum tráfego aparece como "0.00 Kbps", em vez de trocar
     * para a unidade básica ("0 bps") toda vez que a conexão fica parada.
     */
    private String[] scale(double value, double base, String[] symbols) {
        int power = 1;
        value /= base;
        while (value >= NEXT_UNIT && power < symbols.length - 1) {
            value /= base;
            power++;
        }
        return new String[]{number.format(value), symbols[power]};
    }

    /** O número mais largo do modo automático, para reservar um espaço fixo na barra. */
    String[] widestNumbers() {
        return new String[]{"999.99"};
    }

    /** Unidades que podem aparecer escritas no widget com esta escolha (para reservar a largura da coluna). */
    static String[] symbols(Unit unit) {
        String[] all = unit.bits ? BIT_SYMBOLS : BYTE_SYMBOLS;
        return unit.isAuto() ? Arrays.copyOfRange(all, 1, all.length) : new String[]{all[unit.power]};
    }
}
