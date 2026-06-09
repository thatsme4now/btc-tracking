package com.thatsme4now.depot.entity;

import lombok.Data;
import java.io.Serializable;

@Data
public class CurrentPriceId implements Serializable {
    private static final long serialVersionUID = -5988953490651285989L;
	private String ticker;
    private String currency;
}