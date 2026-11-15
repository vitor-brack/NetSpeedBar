package io.github.vitor_brack.netspeedbar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PayloadEstimateTest {

    @Test
    void subtractsTheOverheadOfEveryPacket() {
        long bytes = 1_500_000;
        long packets = 1_000;

        assertEquals(bytes - packets * PayloadEstimate.OVERHEAD_PER_PACKET, PayloadEstimate.useful(bytes, packets));
    }

    @Test
    void neverGoesBelowZero() {
        // 10 confirmações (ACKs) de 60 bytes: o desconto é maior que o próprio tráfego
        assertEquals(0, PayloadEstimate.useful(600, 10));
    }

    @Test
    void keepsTheBytesWhenNoPacketWasCounted() {
        assertEquals(12_345, PayloadEstimate.useful(12_345, 0));
    }

    @Test
    void idleLinkStaysAtZero() {
        assertEquals(0, PayloadEstimate.useful(0, 0));
    }

    /**
     * Calibração: o que o widget mostrava (tráfego bruto da placa) contra o que o site ou a Steam mostravam
     * no mesmo instante. Pacotes cheios de ~1499 bytes, como nos testes de download.
     */
    @ParameterizedTest(name = "{0}: widget {1} Mbps deve virar ~{2} Mbps")
    @CsvSource({
        "Speedtest (Ookla), 571.26, 540.74",
        "Steam,             568.05, 530.70"
    })
    void matchesWhatSitesAndSteamShow(String reference, double widgetMbps, double referenceMbps) {
        long bytesPerSecond = Math.round(widgetMbps * 1_000_000 / 8);
        long packetsPerSecond = Math.round(bytesPerSecond / 1499.0);

        double estimatedMbps = PayloadEstimate.useful(bytesPerSecond, packetsPerSecond) * 8 / 1_000_000.0;

        assertEquals(referenceMbps, estimatedMbps, referenceMbps * 0.01, reference); // até 1% de diferença
    }

    /**
     * Medições com payload conhecido (curl para o speed.cloudflare.com, rede sem outro tráfego):
     * bytes que a placa contou, pacotes que ela contou e bytes realmente transferidos.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "download IPv4, 214725050, 143239, 200000000, 2.5",
        "download IPv6, 218320555, 152612, 200000000, 2.5",
        "upload IPv4,    61212939,   5975,  60000000, 1.5", // sem desconto já daria 2,0%: tolerância menor para o teste valer
        "upload IPv6,    52793397,   6413,  51091968, 2.5"
    })
    void staysCloseToTheRealPayload(String run, long wireBytes, long packets, long payload, double tolerancePercent) {
        long estimated = PayloadEstimate.useful(wireBytes, packets);

        assertEquals(payload, estimated, payload * tolerancePercent / 100, run);
    }
}
