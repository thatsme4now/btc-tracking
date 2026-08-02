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
    is_duplicate  TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'Flagged as possible duplicate at import',
    import_history_id BIGINT               COMMENT 'Herkunfts-Import (import_history.id), NULL wenn manuell angelegt oder Herkunfts-Import gelöscht — bewusst ohne FK-Constraint (siehe schema-h2.sql)',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tx_position FOREIGN KEY (position_id) REFERENCES `position`(id) ON DELETE CASCADE,
    INDEX idx_tx_position (position_id),
    INDEX idx_tx_transfer (transfer_id),
    INDEX idx_tx_import_history (import_history_id)
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
-- CSV-Import-Assistent (3-Step-Wizard): Staging-Tabelle + Historie
-- ============================================================

-- Zwischenspeicher für Zeilen aus einem laufenden Import (Step 2 "Review"),
-- bevor sie final in die transaction-Tabelle übernommen werden. Wird beim
-- Übergang Step 1 → Step 2 befüllt und nach Bestätigen/Abbrechen bzw. vor
-- jedem neuen Datei-Upload komplett geleert (siehe ImportWizardService).
CREATE TABLE IF NOT EXISTS import_staging_row (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    row_index       INT            NOT NULL COMMENT 'Chronologische Reihenfolge innerhalb des Imports',
    raw_typ         VARCHAR(50)    COMMENT 'Ursprünglicher CSV-Typ-Wert vor Remapping',
    type            VARCHAR(20)    COMMENT 'BUY, SELL, TRANSFER_IN, TRANSFER_OUT — NULL wenn nicht auflösbar',
    position_label  VARCHAR(100),
    date_raw        VARCHAR(64)    COMMENT 'Rohwert falls Datum nicht geparst werden konnte',
    date_parsed     DATETIME,
    quantity        DECIMAL(18,8),
    quantity_fiat   DECIMAL(14,2),
    price_per_btc   DECIMAL(14,2),
    currency        VARCHAR(10),
    exchange_rate   DECIMAL(14,6),
    fees            DECIMAL(18,8),
    fees_currency   VARCHAR(10),
    comment         VARCHAR(255),
    transaction_id  VARCHAR(36),
    transfer_id     VARCHAR(36),
    is_duplicate    TINYINT(1)     NOT NULL DEFAULT 0,
    is_fx_warning   TINYINT(1)     NOT NULL DEFAULT 0,
    has_error       TINYINT(1)     NOT NULL DEFAULT 0,
    error_reason    VARCHAR(255),
    created_at      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_isr_row_index (row_index)
) ENGINE=InnoDB;

-- Einfache Historie abgeschlossener Imports, angezeigt als eigene Kachel
-- auf der Übersicht.
CREATE TABLE IF NOT EXISTS import_history (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    imported_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    filename       VARCHAR(255)   NOT NULL,
    total_rows     INT            NOT NULL DEFAULT 0,
    imported_rows  INT            NOT NULL DEFAULT 0,
    duplicate_rows INT            NOT NULL DEFAULT 0,
    error_rows     INT            NOT NULL DEFAULT 0,
    INDEX idx_ih_imported_at (imported_at)
) ENGINE=InnoDB;

-- ============================================================
-- Sample data
-- ============================================================
INSERT INTO position (label, type) VALUES
    ('Binance', 'EXCHANGE'),
    ('Wallet 1-A', 'WALLET')
ON DUPLICATE KEY UPDATE label = VALUES(label);
