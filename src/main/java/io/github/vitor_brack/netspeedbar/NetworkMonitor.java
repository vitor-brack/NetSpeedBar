package io.github.vitor_brack.netspeedbar;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.ptr.PointerByReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mede a velocidade de download/upload lendo os contadores de bytes das placas de rede
 * (GetIfTable2 da iphlpapi.dll, os mesmos números que o Gerenciador de Tarefas mostra).
 * A velocidade é a diferença entre duas leituras dividida pelo tempo entre elas.
 *
 * Os contadores incluem os cabeçalhos dos pacotes, então o número bruto fica uns 5 a 7% acima do que os sites de
 * teste e a Steam mostram. Por padrão o valor é a estimativa útil (PayloadEstimate); o bruto continua disponível.
 */
final class NetworkMonitor {

    /** Seleção automática: soma as placas físicas conectadas (Ethernet, Wi-Fi...). */
    static final long AUTO = 0;

    record Adapter(long luid, String alias, String description, boolean physical, boolean up) {

        String label() {
            String text = alias;
            if (!description.isBlank() && !description.equalsIgnoreCase(alias)) {
                String desc = description.length() > 45 ? description.substring(0, 44) + "…" : description;
                text += "  (" + desc + ")";
            }
            if (!up) {
                text += "  [desconectada]";
            }
            return text.replace("&", "&&"); // '&' tem significado especial em menus
        }
    }

    /** Usa a struct MIB_IF_ROW2 do JNA só para descobrir os offsets dos campos, sem números mágicos. */
    private static final class RowLayout extends IPHlpAPI.MIB_IF_ROW2 {

        int offset(String field) {
            return fieldOffset(field);
        }
    }

    private static final int ROW_SIZE;
    private static final int OFF_LUID;
    private static final int OFF_ALIAS;
    private static final int OFF_DESCRIPTION;
    private static final int OFF_TYPE;
    private static final int OFF_FLAGS;
    private static final int OFF_OPER_STATUS;
    private static final int OFF_IN_OCTETS;
    private static final int OFF_OUT_OCTETS;
    private static final int OFF_IN_UCAST_PKTS;
    private static final int OFF_IN_NUCAST_PKTS;
    private static final int OFF_OUT_UCAST_PKTS;
    private static final int OFF_OUT_NUCAST_PKTS;

    static {
        RowLayout row = new RowLayout();
        ROW_SIZE = row.size();
        OFF_LUID = row.offset("InterfaceLuid");
        OFF_ALIAS = row.offset("Alias");
        OFF_DESCRIPTION = row.offset("Description");
        OFF_TYPE = row.offset("Type");
        OFF_FLAGS = row.offset("InterfaceAndOperStatusFlags");
        OFF_OPER_STATUS = row.offset("OperStatus");
        OFF_IN_OCTETS = row.offset("InOctets");
        OFF_OUT_OCTETS = row.offset("OutOctets");
        OFF_IN_UCAST_PKTS = row.offset("InUcastPkts");
        OFF_IN_NUCAST_PKTS = row.offset("InNUcastPkts"); // broadcast + multicast
        OFF_OUT_UCAST_PKTS = row.offset("OutUcastPkts");
        OFF_OUT_NUCAST_PKTS = row.offset("OutNUcastPkts");
    }

    // Posições dos valores de cada placa nas leituras e nas diferenças entre duas leituras
    private static final int IN_BYTES = 0;
    private static final int OUT_BYTES = 1;
    private static final int IN_PACKETS = 2;
    private static final int OUT_PACKETS = 3;
    private static final int VALUES = 4;

    /** MIB_IF_TABLE2 = { ULONG NumEntries; MIB_IF_ROW2 Table[]; } e Table fica alinhada em 8 bytes. */
    private static final int TABLE_HEADER = 8;
    private static final int IF_TYPE_SOFTWARE_LOOPBACK = 24;
    private static final int IF_OPER_STATUS_UP = 1;
    private static final int FLAG_HARDWARE_INTERFACE = 0x01;
    private static final int FLAG_FILTER_INTERFACE = 0x02;

    @FunctionalInterface
    private interface RowVisitor {

        void visit(Pointer table, long row);
    }

    private long selected = AUTO;
    private boolean useful = true;
    private Map<Long, long[]> previous = new HashMap<>();
    private long previousNanos;
    // Os dois valores ficam guardados, então trocar o modo vale na hora e os totais não misturam as bases
    private double rawDownloadRate;
    private double rawUploadRate;
    private double usefulDownloadRate;
    private double usefulUploadRate;
    private long rawDownloaded;
    private long rawUploaded;
    private long usefulDownloaded;
    private long usefulUploaded;

    void select(long luid) {
        selected = luid;
    }

    /**
     * true (padrão): velocidade útil estimada, sem os cabeçalhos dos pacotes, como os sites de teste e a Steam.
     * false: tráfego bruto da placa, com cabeçalhos.
     */
    void setUseful(boolean useful) {
        this.useful = useful;
    }

    /** Bytes por segundo recebidos na última medição. */
    double downloadRate() {
        return useful ? usefulDownloadRate : rawDownloadRate;
    }

    /** Bytes por segundo enviados na última medição. */
    double uploadRate() {
        return useful ? usefulUploadRate : rawUploadRate;
    }

    long totalDownloaded() {
        return useful ? usefulDownloaded : rawDownloaded;
    }

    long totalUploaded() {
        return useful ? usefulUploaded : rawUploaded;
    }

    void sample() {
        long now = System.nanoTime();
        Map<Long, long[]> current = new HashMap<>();
        long[] physical = new long[VALUES];
        long[] virtual = new long[VALUES];
        long[] chosen = new long[VALUES];
        boolean[] hasPhysical = {false};

        boolean ok = forEachRow((table, row) -> {
            long luid = table.getLong(row + OFF_LUID);
            long[] reading = new long[VALUES];
            reading[IN_BYTES] = table.getLong(row + OFF_IN_OCTETS);
            reading[OUT_BYTES] = table.getLong(row + OFF_OUT_OCTETS);
            reading[IN_PACKETS] = table.getLong(row + OFF_IN_UCAST_PKTS) + table.getLong(row + OFF_IN_NUCAST_PKTS);
            reading[OUT_PACKETS] = table.getLong(row + OFF_OUT_UCAST_PKTS) + table.getLong(row + OFF_OUT_NUCAST_PKTS);
            current.put(luid, reading);

            long[] last = previous.get(luid);
            long[] delta = new long[VALUES];
            for (int i = 0; i < VALUES; i++) {
                // Contador menor que antes = placa reiniciada; ignora em vez de mostrar um valor absurdo
                delta[i] = last == null ? 0 : Math.max(0, reading[i] - last[i]);
            }
            if (luid == selected) {
                add(chosen, delta);
            }

            int flags = table.getByte(row + OFF_FLAGS) & 0xFF;
            if (table.getInt(row + OFF_OPER_STATUS) != IF_OPER_STATUS_UP
                    || (flags & FLAG_FILTER_INTERFACE) != 0 // filtros (QoS, WFP...) repetem o tráfego da placa real
                    || table.getInt(row + OFF_TYPE) == IF_TYPE_SOFTWARE_LOOPBACK) {
                return;
            }
            long[] sum = virtual;
            if ((flags & FLAG_HARDWARE_INTERFACE) != 0) {
                sum = physical;
                hasPhysical[0] = true;
            }
            add(sum, delta);
        });
        if (!ok) {
            return;
        }

        double seconds = previousNanos == 0 ? 0 : (now - previousNanos) / 1e9;
        previous = current;
        previousNanos = now;
        if (seconds <= 0) {
            return;
        }
        long[] used = selected != AUTO ? chosen : hasPhysical[0] ? physical : virtual;
        // Cada sentido é descontado separado: no download o envio é só de confirmações e dá ~0
        long usefulIn = PayloadEstimate.useful(used[IN_BYTES], used[IN_PACKETS]);
        long usefulOut = PayloadEstimate.useful(used[OUT_BYTES], used[OUT_PACKETS]);
        rawDownloadRate = used[IN_BYTES] / seconds;
        rawUploadRate = used[OUT_BYTES] / seconds;
        usefulDownloadRate = usefulIn / seconds;
        usefulUploadRate = usefulOut / seconds;
        rawDownloaded += used[IN_BYTES];
        rawUploaded += used[OUT_BYTES];
        usefulDownloaded += usefulIn;
        usefulUploaded += usefulOut;
    }

    private static void add(long[] total, long[] delta) {
        for (int i = 0; i < VALUES; i++) {
            total[i] += delta[i];
        }
    }

    /**
     * Placas que fazem sentido escolher no menu: conectadas e que já trafegaram algo (ou a selecionada),
     * sem filtros nem loopback. Isso esconde os "WAN Miniport" e afins, que nunca têm tráfego.
     */
    List<Adapter> adapters() {
        List<Adapter> list = new ArrayList<>();
        forEachRow((table, row) -> {
            int flags = table.getByte(row + OFF_FLAGS) & 0xFF;
            if ((flags & FLAG_FILTER_INTERFACE) != 0 || table.getInt(row + OFF_TYPE) == IF_TYPE_SOFTWARE_LOOPBACK) {
                return;
            }
            long luid = table.getLong(row + OFF_LUID);
            boolean physical = (flags & FLAG_HARDWARE_INTERFACE) != 0;
            boolean up = table.getInt(row + OFF_OPER_STATUS) == IF_OPER_STATUS_UP;
            boolean used = table.getLong(row + OFF_IN_OCTETS) != 0 || table.getLong(row + OFF_OUT_OCTETS) != 0;
            if (luid == selected || (up && (physical || used))) {
                list.add(new Adapter(luid, table.getWideString(row + OFF_ALIAS),
                        table.getWideString(row + OFF_DESCRIPTION), physical, up));
            }
        });
        list.sort(Comparator.comparing((Adapter a) -> !a.physical())
                .thenComparing(Adapter::alias, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    /** Diagnóstico: todas as interfaces que o Windows informa (java -jar NetSpeedBar.jar --interfaces). */
    static String describeAll() {
        StringBuilder sb = new StringBuilder("MIB_IF_ROW2: " + ROW_SIZE + " bytes\n");
        forEachRow((table, row) -> sb.append(String.format(
                "luid=%016x tipo=%3d flags=%02x status=%d rx=%,d tx=%,d rxpkt=%,d txpkt=%,d  %s | %s%n",
                table.getLong(row + OFF_LUID), table.getInt(row + OFF_TYPE), table.getByte(row + OFF_FLAGS) & 0xFF,
                table.getInt(row + OFF_OPER_STATUS), table.getLong(row + OFF_IN_OCTETS), table.getLong(row + OFF_OUT_OCTETS),
                table.getLong(row + OFF_IN_UCAST_PKTS) + table.getLong(row + OFF_IN_NUCAST_PKTS),
                table.getLong(row + OFF_OUT_UCAST_PKTS) + table.getLong(row + OFF_OUT_NUCAST_PKTS),
                table.getWideString(row + OFF_ALIAS), table.getWideString(row + OFF_DESCRIPTION))));
        return sb.toString();
    }

    private static boolean forEachRow(RowVisitor visitor) {
        PointerByReference ref = new PointerByReference();
        int error = Win32.IpHlpApi.INSTANCE.GetIfTable2(ref);
        if (error != 0) {
            Log.error("GetIfTable2 falhou, erro " + error, null);
            return false;
        }
        Pointer table = ref.getValue();
        try {
            int count = table.getInt(0);
            for (int i = 0; i < count; i++) {
                visitor.visit(table, TABLE_HEADER + (long) i * ROW_SIZE);
            }
        } finally {
            Win32.IpHlpApi.INSTANCE.FreeMibTable(table);
        }
        return true;
    }
}
