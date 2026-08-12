package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.thatsme4now.depot.BaseIntegrationTest;
import com.thatsme4now.depot.TestFixtures;
import com.thatsme4now.depot.dto.FlowGraphDTO;
import com.thatsme4now.depot.dto.FlowLinkDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.PositionType;

class FlowServiceTest extends BaseIntegrationTest {

    @Autowired
    private FlowService flowService;

    @Autowired
    private DepotService depotService;

    @Test
    void buildFlowGraph_withNoTransactions_returnsEmptyGraph() {
        FlowGraphDTO graph = flowService.buildFlowGraph(null, null, null);
        assertThat(graph.getNodes()).isEmpty();
        assertThat(graph.getLinks()).isEmpty();
    }

    @Test
    void buildFlowGraph_buyCreatesEdgeFromVirtualBuyNode() {
        Position binance = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(binance, LocalDateTime.now(),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        FlowGraphDTO graph = flowService.buildFlowGraph(null, null, null);

        assertThat(graph.getLinks()).hasSize(1);
        FlowLinkDTO link = graph.getLinks().get(0);
        assertThat(link.getSource()).isEqualTo("buy-" + binance.getId());
        assertThat(link.getTarget()).isEqualTo("pos-" + binance.getId());
        assertThat(link.getValue()).isEqualByComparingTo("0.01");
    }

    @Test
    void buildFlowGraph_pairedTransferCreatesEdgeBetweenPositions() {
        Position binance = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        Position wallet = depotService.save(TestFixtures.position("Bitbox", PositionType.WALLET));
        String transferId = UUID.randomUUID().toString();
        depotService.saveTransaction(TestFixtures.transferOut(binance, LocalDateTime.now(),
                new BigDecimal("0.005"), transferId));
        depotService.saveTransaction(TestFixtures.transferIn(wallet, LocalDateTime.now().plusMinutes(5),
                new BigDecimal("0.0049"), transferId));

        FlowGraphDTO graph = flowService.buildFlowGraph(null, null, null);

        assertThat(graph.getLinks()).hasSize(1);
        FlowLinkDTO link = graph.getLinks().get(0);
        assertThat(link.getSource()).isEqualTo("pos-" + binance.getId());
        assertThat(link.getTarget()).isEqualTo("pos-" + wallet.getId());
    }

    @Test
    void buildFlowGraph_dateRangeFiltersEdgesByDate() {
        Position binance = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        depotService.saveTransaction(TestFixtures.buy(binance, LocalDateTime.now().minusDays(30),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        FlowGraphDTO graph = flowService.buildFlowGraph(
                java.time.LocalDate.now().minusDays(1), java.time.LocalDate.now(), null);

        assertThat(graph.getLinks()).isEmpty();
    }

    @Test
    void buildFlowGraph_anchoredOnDownstreamPosition_tracesUpstreamOrigin() {
        Position binance = depotService.save(TestFixtures.position("Binance", PositionType.EXCHANGE));
        Position wallet = depotService.save(TestFixtures.position("Bitbox", PositionType.WALLET));

        depotService.saveTransaction(TestFixtures.buy(binance, LocalDateTime.now().minusDays(2),
                new BigDecimal("0.01"), new BigDecimal("50000"), "EUR"));

        String transferId = UUID.randomUUID().toString();
        depotService.saveTransaction(TestFixtures.transferOut(binance, LocalDateTime.now().minusDays(1),
                new BigDecimal("0.005"), transferId));
        depotService.saveTransaction(TestFixtures.transferIn(wallet, LocalDateTime.now().minusDays(1).plusMinutes(5),
                new BigDecimal("0.005"), transferId));

        FlowGraphDTO graph = flowService.buildFlowGraph(null, null, wallet.getId());

        // anchored on the wallet: both the buy->binance and the binance->wallet transfer
        // should appear (tracing the origin of the funds that ended up in the wallet)
        assertThat(graph.getLinks()).hasSize(2);
        assertThat(graph.getLinks()).extracting(FlowLinkDTO::getTarget)
                .contains("pos-" + wallet.getId());
    }
}
