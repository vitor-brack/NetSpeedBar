package io.github.vitor_brack.netspeedbar;

/**
 * Estima a velocidade "útil" (o conteúdo que chega ou sai) a partir dos contadores da placa de rede.
 *
 * O contador da placa conta tudo que passa por ela: o conteúdo mais os cabeçalhos de cada pacote (Ethernet, IP,
 * TCP) e o que é reenviado por perda. Os sites de teste de velocidade e a Steam contam só o conteúdo, então o
 * número bruto fica uns 5 a 7% acima do que eles mostram.
 *
 * O desconto é por pacote, e não um percentual fixo, para se ajustar sozinho a cada PC: com o Large Send Offload
 * ligado o contador de envio vê blocos de 8 a 10 KB (poucos pacotes, pouco a descontar), e sem ele vê pacotes
 * de ~1500 bytes.
 */
final class PayloadEstimate {

    /**
     * Sobrecarga empírica por pacote, em bytes. Não é só o tamanho do cabeçalho (54 a 86 bytes, de IPv4 a IPv6):
     * embute também o que é reenviado, que cresce junto com o número de pacotes. Calibrada para que o widget
     * fique a ~1% do Speedtest (Ookla) e da Steam em download cheio. Medido com curl (4 conexões, ~390 Mbps),
     * erra -2% no IPv4 e +0,4% no IPv6, porque o cabeçalho do IPv6 é maior. Com muita perda (retransmissões)
     * o valor estimado passa do real, até ~4% nos piores casos medidos.
     * É uma estimativa: a diferença entre os próprios sites de teste já chega a 4%.
     */
    static final int OVERHEAD_PER_PACKET = 90;

    private PayloadEstimate() {
    }

    /** Bytes de conteúdo estimados; nunca negativo (confirmações TCP sozinhas somam zero). */
    static long useful(long bytes, long packets) {
        return Math.max(0, bytes - packets * OVERHEAD_PER_PACKET);
    }
}
