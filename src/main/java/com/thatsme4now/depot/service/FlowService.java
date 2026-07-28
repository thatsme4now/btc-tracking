package com.thatsme4now.depot.service;

import com.thatsme4now.depot.dto.FlowGraphDTO;
import com.thatsme4now.depot.dto.FlowLinkDTO;
import com.thatsme4now.depot.dto.FlowLinkDetailDTO;
import com.thatsme4now.depot.dto.FlowNodeDTO;
import com.thatsme4now.depot.entity.Position;
import com.thatsme4now.depot.entity.Transaction;
import com.thatsme4now.depot.entity.TransactionType;
import com.thatsme4now.depot.repository.PositionRepository;
import com.thatsme4now.depot.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FlowService {

    private final PositionRepository positionRepo;
    private final TransactionRepository transactionRepo;
    private final DepotService depotService;

    // ── Public entry point ───────────────────────────────────────────────

    public FlowGraphDTO buildFlowGraph(LocalDate from, LocalDate to, Long anchorPositionId) {
        List<Position> positions = positionRepo.findAll();
        List<Transaction> allTx = transactionRepo.findAll();
        allTx.sort(Comparator.comparing(Transaction::getDate));

        Map<Long, BigDecimal> balances = computeBalances(positions, allTx);

        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDt   = to   != null ? to.plusDays(1).atStartOfDay() : null; // exklusiv

        List<Transaction> filteredTx = allTx.stream()
                .filter(tx -> fromDt == null || !tx.getDate().isBefore(fromDt))
                .filter(tx -> toDt == null || tx.getDate().isBefore(toDt))
                .collect(Collectors.toList());

        // Pairing-Lookup auf ALLEN Transaktionen, damit ein Transfer nicht fälschlich als
        // Solo-Transfer erscheint, nur weil die Gegenbuchung außerhalb des Datumsfilters liegt.
        Map<String, List<Transaction>> byTransferId = allTx.stream()
                .filter(t -> t.getTransferId() != null)
                .collect(Collectors.groupingBy(Transaction::getTransferId));

        List<FlowEdge> edges = buildRawEdges(filteredTx, byTransferId);

        if (anchorPositionId == null) {
            // Kein Filter -> "relevant" = jede Kante in voller Menge (identisch zur alten Logik)
            Map<String, BigDecimal> identity = new LinkedHashMap<>();
            for (FlowEdge e : edges) identity.put(e.id, e.debitQty);
            return aggregateToGraph(positions, balances, edges, identity, null);
        }

        Map<Long, List<FlowEdge>> inflowsByPos  = new HashMap<>();
        Map<Long, List<FlowEdge>> outflowsByPos = new HashMap<>();
        for (FlowEdge e : edges) {
            if (e.targetPositionId != null) inflowsByPos.computeIfAbsent(e.targetPositionId, k -> new ArrayList<>()).add(e);
            if (e.sourcePositionId != null) outflowsByPos.computeIfAbsent(e.sourcePositionId, k -> new ArrayList<>()).add(e);
        }

        Map<String, List<Allocation>> allocByOutflow = new HashMap<>();
        Map<String, List<Allocation>> allocByInflow  = new HashMap<>();

        for (Position p : positions) {
            runFifoLedger(inflowsByPos.getOrDefault(p.getId(), List.of()),
                    outflowsByPos.getOrDefault(p.getId(), List.of()), allocByOutflow, allocByInflow);
        }

        Map<String, BigDecimal> relevantQty = new LinkedHashMap<>();

        for (FlowEdge e : inflowsByPos.getOrDefault(anchorPositionId, List.of())) {
            traceUpstream(e, e.creditQty, allocByOutflow, relevantQty, new HashSet<>());
        }
        for (FlowEdge e : outflowsByPos.getOrDefault(anchorPositionId, List.of())) {
            traceDownstream(e, e.debitQty, allocByInflow, relevantQty, new HashSet<>());
        }

        return aggregateToGraph(positions, balances, edges, relevantQty, anchorPositionId);
    }

    // ── Raw-Edge-Modell (Transaktions-Ebene, noch nicht pro Monat gruppiert) ─

    private static class FlowEdge {
        String id;
        String sourceNode;
        String targetNode;
        Long sourcePositionId; // gesetzt, wenn sourceNode = "pos-X" (Edge ist Outflow dieser Position)
        Long targetPositionId; // gesetzt, wenn targetNode = "pos-X" (Edge ist Inflow dieser Position)
        BigDecimal debitQty;   // aus sourcePositionId abgeflossene Menge
        BigDecimal creditQty;  // bei targetPositionId angekommene Menge (kann wg. Fee < debitQty sein)
        LocalDateTime date;
        String transactionId;
        Transaction tx;        // primäre Transaktion (z.B. TRANSFER_OUT-Seite bei Transfers)
        Transaction pairedTx;  // gepaarte Gegenbuchung (TRANSFER_IN), null wenn nicht anwendbar
    }

    private static class Allocation {
        FlowEdge outflowEdge;
        FlowEdge inflowEdge; // null = unbekannte Herkunft (Datenlücke, z.B. fehlender initialer Bestand)
        BigDecimal qty;
    }

    private List<FlowEdge> buildRawEdges(List<Transaction> filteredTx, Map<String, List<Transaction>> byTransferId) {
        List<FlowEdge> edges = new ArrayList<>();
        Set<Long> processed = new HashSet<>();

        for (Transaction tx : filteredTx) {
            if (processed.contains(tx.getId())) continue;

            switch (tx.getType()) {
                case BUY -> {
                    edges.add(mkEdge("e" + edges.size(), "buy-" + tx.getPosition().getId(), "pos-" + tx.getPosition().getId(),
                            null, tx.getPosition().getId(), null, tx.getQuantity(), tx, null));
                    processed.add(tx.getId());
                }
                case SELL -> {
                    edges.add(mkEdge("e" + edges.size(), "pos-" + tx.getPosition().getId(), "sell-" + tx.getPosition().getId(),
                            tx.getPosition().getId(), null, tx.getQuantity(), null, tx, null));
                    processed.add(tx.getId());
                }
                case TRANSFER_OUT -> {
                    Transaction pairIn = findPaired(tx, byTransferId, TransactionType.TRANSFER_IN);
                    if (pairIn != null) {
                        processed.add(tx.getId());
                        processed.add(pairIn.getId());
                        if (!pairIn.getPosition().getId().equals(tx.getPosition().getId())) {
                            edges.add(mkEdge("e" + edges.size(), "pos-" + tx.getPosition().getId(), "pos-" + pairIn.getPosition().getId(),
                                    tx.getPosition().getId(), pairIn.getPosition().getId(),
                                    tx.getQuantity(), pairIn.getQuantity(), tx, pairIn));
                        }
                        // sonst: Self-Transfer (SELF-Import) -> kein Edge
                    } else {
                        edges.add(mkEdge("e" + edges.size(), "pos-" + tx.getPosition().getId(), "ext-out-" + tx.getPosition().getId(),
                                tx.getPosition().getId(), null, tx.getQuantity(), null, tx, null));
                        processed.add(tx.getId());
                    }
                }
                case TRANSFER_IN -> {
                    Transaction pairOut = findPaired(tx, byTransferId, TransactionType.TRANSFER_OUT);
                    if (pairOut != null) {
                        processed.add(tx.getId()); // bereits über OUT-Seite verarbeitet
                    } else {
                        edges.add(mkEdge("e" + edges.size(), "ext-in-" + tx.getPosition().getId(), "pos-" + tx.getPosition().getId(),
                                null, tx.getPosition().getId(), null, tx.getQuantity(), tx, null));
                        processed.add(tx.getId());
                    }
                }
                default -> { } // DEPOSIT / WITHDRAW aktuell ungenutzt
            }
        }
        return edges;
    }

    private FlowEdge mkEdge(String id, String sourceNode, String targetNode, Long sourcePositionId, Long targetPositionId,
                             BigDecimal debitQty, BigDecimal creditQty, Transaction tx, Transaction pairedTx) {
        FlowEdge e = new FlowEdge();
        e.id = id;
        e.sourceNode = sourceNode;
        e.targetNode = targetNode;
        e.sourcePositionId = sourcePositionId;
        e.targetPositionId = targetPositionId;
        e.debitQty = debitQty != null ? debitQty : creditQty;
        e.creditQty = creditQty != null ? creditQty : debitQty;
        e.date = tx.getDate();
        e.transactionId = tx.getTransactionId();
        e.tx = tx;
        e.pairedTx = pairedTx;
        return e;
    }

    private Transaction findPaired(Transaction tx, Map<String, List<Transaction>> byTransferId, TransactionType wantedType) {
        if (tx.getTransferId() == null) return null;
        List<Transaction> group = byTransferId.get(tx.getTransferId());
        if (group == null) return null;
        return group.stream()
                .filter(t -> t.getType() == wantedType && !t.getId().equals(tx.getId()))
                .findFirst().orElse(null);
    }

    // ── FIFO-Ledger je Position: erzeugt Allocations (welcher Eingang deckt welchen Ausgang) ─

    private void runFifoLedger(List<FlowEdge> inflows, List<FlowEdge> outflows,
                                Map<String, List<Allocation>> allocByOutflow,
                                Map<String, List<Allocation>> allocByInflow) {

        record TimelineEntry(LocalDateTime date, boolean isInflow, FlowEdge edge) {}

        List<TimelineEntry> timeline = new ArrayList<>();
        for (FlowEdge e : inflows)  timeline.add(new TimelineEntry(e.date, true, e));
        for (FlowEdge e : outflows) timeline.add(new TimelineEntry(e.date, false, e));
        timeline.sort(Comparator.comparing(TimelineEntry::date));

        // Deque von [FlowEdge Ursprungs-Edge, BigDecimal verbleibende Menge]
        Deque<Object[]> lots = new ArrayDeque<>();

        for (TimelineEntry te : timeline) {
            if (te.isInflow()) {
                lots.addLast(new Object[]{te.edge(), te.edge().creditQty});
                continue;
            }

            BigDecimal remaining = te.edge().debitQty;
            while (remaining.compareTo(BigDecimal.ZERO) > 0 && !lots.isEmpty()) {
                Object[] lot = lots.peekFirst();
                FlowEdge lotEdge = (FlowEdge) lot[0];
                BigDecimal lotRemaining = (BigDecimal) lot[1];
                BigDecimal take = remaining.min(lotRemaining);

                Allocation alloc = new Allocation();
                alloc.outflowEdge = te.edge();
                alloc.inflowEdge = lotEdge;
                alloc.qty = take;
                allocByOutflow.computeIfAbsent(te.edge().id, k -> new ArrayList<>()).add(alloc);
                allocByInflow.computeIfAbsent(lotEdge.id, k -> new ArrayList<>()).add(alloc);

                remaining = remaining.subtract(take);
                BigDecimal lotLeft = lotRemaining.subtract(take);
                if (lotLeft.compareTo(BigDecimal.ZERO) <= 0) {
                    lots.pollFirst();
                } else {
                    lot[1] = lotLeft;
                }
            }

            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                // Datenlücke: nicht genug erfasste Eingangs-Historie (z.B. initialer, nicht importierter Bestand)
                Allocation alloc = new Allocation();
                alloc.outflowEdge = te.edge();
                alloc.inflowEdge = null;
                alloc.qty = remaining;
                allocByOutflow.computeIfAbsent(te.edge().id, k -> new ArrayList<>()).add(alloc);
            }
        }
    }

    // ── Rekursive Herkunfts-/Ziel-Verfolgung ausgehend von der Ankerposition ─

    private void traceUpstream(FlowEdge edge, BigDecimal neededQty,
                                Map<String, List<Allocation>> allocByOutflow,
                                Map<String, BigDecimal> relevantQty, Set<String> guard) {
        BigDecimal clipped = neededQty.min(edge.creditQty);
        relevantQty.merge(edge.id, clipped, BigDecimal::add);

        if (edge.sourcePositionId == null) return; // terminal: BUY oder EXTERNAL_IN
        if (!guard.add("UP:" + edge.id)) return;   // Schutz gegen theoretische Zyklen

        BigDecimal remaining = clipped;
        for (Allocation a : allocByOutflow.getOrDefault(edge.id, List.of())) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(a.qty);
            if (a.inflowEdge != null) {
                traceUpstream(a.inflowEdge, take, allocByOutflow, relevantQty, guard);
            }
            remaining = remaining.subtract(take);
        }
    }

    private void traceDownstream(FlowEdge edge, BigDecimal neededQty,
                                  Map<String, List<Allocation>> allocByInflow,
                                  Map<String, BigDecimal> relevantQty, Set<String> guard) {
        BigDecimal clipped = neededQty.min(edge.debitQty);
        relevantQty.merge(edge.id, clipped, BigDecimal::add);

        if (edge.targetPositionId == null) return; // terminal: SELL oder EXTERNAL_OUT
        if (!guard.add("DOWN:" + edge.id)) return;

        BigDecimal remaining = clipped;
        for (Allocation a : allocByInflow.getOrDefault(edge.id, List.of())) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal take = remaining.min(a.qty);
            traceDownstream(a.outflowEdge, take, allocByInflow, relevantQty, guard);
            remaining = remaining.subtract(take);
        }
    }

    // ── Aggregation zu Monats-Links + Node-Liste ────────────────────────────

    private FlowGraphDTO aggregateToGraph(List<Position> positions, Map<Long, BigDecimal> balances,
                                           List<FlowEdge> edges, Map<String, BigDecimal> relevantQty,
                                           Long anchorPositionId) {
        Map<String, FlowLinkDTO> links = new LinkedHashMap<>();
        Set<String> usedNodeIds = new LinkedHashSet<>();

        for (FlowEdge e : edges) {
            BigDecimal qty = relevantQty.get(e.id);
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) continue;

            usedNodeIds.add(e.sourceNode);
            usedNodeIds.add(e.targetNode);

            String month = e.date.toLocalDate().toString().substring(0, 7);
            String key = e.sourceNode + "|" + e.targetNode + "|" + month;
            FlowLinkDTO link = links.computeIfAbsent(key, k -> {
                FlowLinkDTO l = new FlowLinkDTO();
                l.setId(k);
                l.setSource(e.sourceNode);
                l.setTarget(e.targetNode);
                l.setValue(BigDecimal.ZERO);
                l.setMonth(month);
                l.setDetails(new ArrayList<>());
                return l;
            });
            link.setValue(link.getValue().add(qty));

            FlowLinkDetailDTO detail = new FlowLinkDetailDTO();
            detail.setDate(e.date);
            detail.setQuantity(qty.setScale(8, RoundingMode.HALF_UP));
            detail.setOriginalQuantity(e.debitQty.setScale(8, RoundingMode.HALF_UP));
            detail.setTransactionId(e.transactionId);
            detail.setTransaction(depotService.toTransactionDTO(e.tx));
            if (e.pairedTx != null) {
                detail.setPairedTransaction(depotService.toTransactionDTO(e.pairedTx));
            }
            link.getDetails().add(detail);
        }

        if (anchorPositionId != null) {
            usedNodeIds.add("pos-" + anchorPositionId);
        }

        return new FlowGraphDTO(buildNodes(positions, balances, usedNodeIds), new ArrayList<>(links.values()));
    }

    // ── Bestände & Node-Aufbau ───────────────────────────────────────────────

    private Map<Long, BigDecimal> computeBalances(List<Position> positions, List<Transaction> allTx) {
        Map<Long, BigDecimal> balances = new HashMap<>();
        for (Position p : positions) balances.put(p.getId(), BigDecimal.ZERO);
        for (Transaction tx : allTx) {
            Long posId = tx.getPosition().getId();
            BigDecimal delta = switch (tx.getType()) {
                case BUY, TRANSFER_IN, DEPOSIT -> tx.getQuantity();
                case SELL, TRANSFER_OUT, WITHDRAW -> tx.getQuantity().negate();
            };
            balances.merge(posId, delta, BigDecimal::add);
        }
        return balances;
    }

    private List<FlowNodeDTO> buildNodes(List<Position> positions, Map<Long, BigDecimal> balances, Set<String> usedNodeIds) {
        List<FlowNodeDTO> nodes = new ArrayList<>();
        for (Position p : positions) {
            String posNodeId = "pos-" + p.getId();
            if (!usedNodeIds.contains(posNodeId)) continue;

            FlowNodeDTO n = new FlowNodeDTO();
            n.setId(posNodeId);
            n.setKind("POSITION");
            n.setPositionId(p.getId());
            n.setPositionLabel(p.getLabel());
            n.setPositionType(p.getType().name());
            n.setCurrentBalance(balances.getOrDefault(p.getId(), BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP));
            nodes.add(n);

            addVirtualIfUsed(nodes, usedNodeIds, "buy-" + p.getId(), "BUY", p);
            addVirtualIfUsed(nodes, usedNodeIds, "sell-" + p.getId(), "SELL", p);
            addVirtualIfUsed(nodes, usedNodeIds, "ext-in-" + p.getId(), "EXTERNAL_IN", p);
            addVirtualIfUsed(nodes, usedNodeIds, "ext-out-" + p.getId(), "EXTERNAL_OUT", p);
        }
        return nodes;
    }

    private void addVirtualIfUsed(List<FlowNodeDTO> nodes, Set<String> usedNodeIds, String id, String kind, Position p) {
        if (!usedNodeIds.contains(id)) return;
        FlowNodeDTO n = new FlowNodeDTO();
        n.setId(id);
        n.setKind(kind);
        n.setPositionId(p.getId());
        n.setPositionLabel(p.getLabel());
        n.setPositionType(p.getType().name());
        nodes.add(n);
    }
}