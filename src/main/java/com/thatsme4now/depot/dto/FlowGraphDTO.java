package com.thatsme4now.depot.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FlowGraphDTO {
    private List<FlowNodeDTO> nodes;
    private List<FlowLinkDTO> links;
}