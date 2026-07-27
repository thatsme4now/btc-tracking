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
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FlowService {

    private final PositionRepository positionRepo;
    private final TransactionRepository transactionRepo;

    public FlowGraphDTO buildFlowGraph() {
        List<Position> positions = positionRepo.findAll();
        List<Transaction> allTx = transactionRepo.findAll();
        allTx.sort(Comparator.comparing(Transaction::getDate));

        Map<Long, BigDecimal> balances = computeBalances(positions, allTx);

        Map<String, FlowLinkDTO> links = new LinkedHashMap<>();
        Set<String> usedNodeIds = new LinkedHashSet<>();
        Set<Long> processed = new HashSet<>();

        Map<String, List<Transaction>> byTransferId = allTx.stream()
                .filter(t -> t.getTransferId() != null)
                .collect(Collectors.groupingBy(Transaction::getTransferId));

        for (Transaction tx : allTx) {
            if (processed.contains(tx.getId())) continue;

            switch (tx.getType()) {
                case BUY -> {
                    String posNode = "pos-" + tx.getPosition().getId();
                    String buyNode = "buy-" + tx.getPosition().getId();
                    usedNodeIds.add(posNode);
                    usedNodeIds.add(buyNode);
                    addLink(links, buyNode, posNode, tx);
                    processed.add(tx.getId());
                }
                case SELL -> {
                    String posNode = "pos-" + tx.getPosition().getId();
                    String sellNode = "sell-" + tx.getPosition().getId();
                    usedNodeIds.add(posNode);
                    usedNodeIds.add(sellNode);
                    addLink(links, posNode, sellNode, tx);
                    processed.add(tx.getId());
                }
                case TRANSFER_OUT -> {
                    Transaction pairIn = findPaired(tx, byTransferId, TransactionType.TRANSFER_IN);
                    String fromNode = "pos-" + tx.getPosition().getId();
                    usedNodeIds.add(fromNode);

                    if (pairIn != null) {
                        processed.add(tx.getId());
                        processed.add(pairIn.getId());
                        if (!pairIn.getPosition().getId().equals(tx.getPosition().getId())) {
                            String toNode = "pos-" + pairIn.getPosition().getId();
                            usedNodeIds.add(toNode);
                            addLink(links, fromNode, toNode, tx);
                        }
                        // sonst: Self-Transfer (SELF-Import) -> kein Link, nur Gebühr verloren
                    } else {
                        String extNode = "ext-out-" + tx.getPosition().getId();
                        usedNodeIds.add(extNode);
                        addLink(links, fromNode, extNode, tx);
                        processed.add(tx.getId());
                    }
                }
                case TRANSFER_IN -> {
                    Transaction pairOut = findPaired(tx, byTransferId, TransactionType.TRANSFER_OUT);
                    if (pairOut != null) {
                        processed.add(tx.getId()); // wird bereits über OUT-Seite verlinkt
                    } else {
                        String toNode = "pos-" + tx.getPosition().getId();
                        String extNode = "ext-in-" + tx.getPosition().getId();
                        usedNodeIds.add(toNode);
                        usedNodeIds.add(extNode);
                        addLink(links, extNode, toNode, tx);
                        processed.add(tx.getId());
                    }
                }
                default -> { } // DEPOSIT / WITHDRAW aktuell ungenutzt
            }
        }

        return new FlowGraphDTO(buildNodes(positions, balances, usedNodeIds), new ArrayList<>(links.values()));
    }

    private Transaction findPaired(Transaction tx, Map<String, List<Transaction>> byTransferId, TransactionType wantedType) {
        if (tx.getTransferId() == null) return null;
        List<Transaction> group = byTransferId.get(tx.getTransferId());
        if (group == null) return null;
        return group.stream()
                .filter(t -> t.getType() == wantedType && !t.getId().equals(tx.getId()))
                .findFirst().orElse(null);
    }

    private void addLink(Map<String, FlowLinkDTO> links, String source, String target, Transaction tx) {
        String month = tx.getDate().toLocalDate().toString().substring(0, 7); // yyyy-MM
        String key = source + "|" + target + "|" + month;
        FlowLinkDTO link = links.computeIfAbsent(key, k -> {
            FlowLinkDTO l = new FlowLinkDTO();
            l.setId(k);
            l.setSource(source);
            l.setTarget(target);
            l.setValue(BigDecimal.ZERO);
            l.setMonth(month);
            l.setDetails(new ArrayList<>());
            return l;
        });
        link.setValue(link.getValue().add(tx.getQuantity()));

        FlowLinkDetailDTO detail = new FlowLinkDetailDTO();
        detail.setDate(tx.getDate());
        detail.setQuantity(tx.getQuantity());
        detail.setTransactionId(tx.getTransactionId());
        link.getDetails().add(detail);
    }

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
            FlowNodeDTO n = new FlowNodeDTO();
            n.setId("pos-" + p.getId());
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