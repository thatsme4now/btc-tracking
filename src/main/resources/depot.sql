-- ============================================================
-- Bitcoin Portfolio Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS `btc-tracking` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `btc-tracking`;

-- position: one row per exchange/wallet
CREATE TABLE IF NOT EXISTS `position` (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    label          VARCHAR(100)  NOT NULL COMMENT 'Exchange or wallet name, e.g. Binance, Ledger',
    type           VARCHAR(20)   NOT NULL DEFAULT 'EXCHANGE' COMMENT 'Exchange or wallet name, e.g. Binance, Ledger',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 2. Neue Tabelle: transaction
CREATE TABLE IF NOT EXISTS transaction (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id    VARCHAR(36),
    position_id   BIGINT        NOT NULL,
    type          VARCHAR(20)   NOT NULL COMMENT 'BUY, SELL, TRANSFER_IN, TRANSFER_OUT',
    date          DATETIME          NOT NULL,
    quantity      DECIMAL(18,8) NOT NULL COMMENT 'Amount in BTC',
    quantity_fiat DECIMAL(14,2)           COMMENT 'EUR paid or get',
	currency   	   VARCHAR(10)   NOT NULL DEFAULT 'EUR',
    exchange_rate DECIMAL(14,6) NOT NULL DEFAULT 1.000000,
    price_per_btc DECIMAL(14,2)           COMMENT 'EUR per BTC, null for transfers',
    fees          DECIMAL(18,8)           COMMENT 'Transaction or network fees',
    fees_currency VARCHAR(10)             COMMENT 'Transaction fees unit',

    comment		  VARCHAR(255)             COMMENT '',
    transfer_id   VARCHAR(36)              COMMENT 'UUID linking TRANSFER_IN / TRANSFER_OUT pair',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tx_position FOREIGN KEY (position_id) REFERENCES `position`(id) ON DELETE CASCADE,
    INDEX idx_tx_position (position_id),
    INDEX idx_tx_transfer (transfer_id)
) ENGINE=InnoDB;


-- price_history: daily BTC/EUR candles from CoinGecko
CREATE TABLE IF NOT EXISTS price_history (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker    VARCHAR(10)   NOT NULL DEFAULT 'BTC',
    date      DATE          NOT NULL,
    open      DECIMAL(14,2),
    high      DECIMAL(14,2),
    low       DECIMAL(14,2),
    close     DECIMAL(14,2) NOT NULL,
    volume    BIGINT,
    loaded_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_ticker_date (ticker, date),
    INDEX idx_ticker (ticker),
    INDEX idx_date   (date)
) ENGINE=InnoDB;

-- current_price: latest BTC/EUR price
CREATE TABLE `current_price` (
  `ticker` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'BTC',
  `currency` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EUR',
  `price` decimal(14,2) NOT NULL,
  `price_date` date NOT NULL,
  `loaded_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`ticker`,`currency`)
) ENGINE=InnoDB;

-- ============================================================
-- Sample data
-- ============================================================
INSERT INTO position (label, type) VALUES
    ('Binance', 'EXCHANGE'),
    ('Wallet 1-A', 'WALLET')
ON DUPLICATE KEY UPDATE label = VALUES(label);
