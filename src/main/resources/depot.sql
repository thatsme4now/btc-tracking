-- ============================================================
-- Bitcoin Portfolio Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS `btc-tracking` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `btc-tracking`;

-- position: one row per exchange/wallet
-- description: free-text note, added later — siehe PositionAddress-Feature.
-- Bestandsinstallationen: ALTER TABLE `position` ADD COLUMN description TEXT;
CREATE TABLE IF NOT EXISTS `position` (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    label          VARCHAR(100)  NOT NULL COMMENT 'Exchange or wallet name, e.g. Binance, Ledger',
    type           VARCHAR(20)   NOT NULL DEFAULT 'EXCHANGE' COMMENT 'Exchange or wallet name, e.g. Binance, Ledger',
    description    TEXT          COMMENT 'Free-text note about this position',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- position_address: optionale Bitcoin-Adressen pro Position, für den
-- On-Chain-Bestandsabruf über eine selbst gehostete mempool-Instanz — siehe
-- PositionAddress-Entity/MempoolPriceService. last_fetch_*: zwischen-
-- gespeichertes Ergebnis des letzten Abrufs (Guthaben, Rohantwort, Zeit-
-- punkt), um bei einem erneuten Abruf Änderungen erkennen zu können.
-- Bestandsinstallationen: dieses CREATE TABLE IF NOT EXISTS reicht, da die
-- Tabelle bisher nicht existierte. last_fetch_txs_json kam etwas später
-- dazu — Bestandsinstallationen mit der Tabelle bereits vorhanden:
-- ALTER TABLE position_address ADD COLUMN last_fetch_txs_json TEXT;
CREATE TABLE IF NOT EXISTS position_address (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id             BIGINT        NOT NULL,
    address                 VARCHAR(120)  NOT NULL COMMENT 'Bitcoin address, format-checked but not checksum-verified',
    label                   VARCHAR(100)  COMMENT 'Optional free-text label, e.g. Cold Storage UTXO',
    last_fetch_json         TEXT          COMMENT 'Raw GET /api/address/:address response from the last fetch',
    last_fetch_balance_sats BIGINT        COMMENT 'Confirmed balance in satoshis from the last fetch',
    last_fetch_txs_json     TEXT          COMMENT 'Cached up-to-10 recent txs from GET /api/address/:address/txs, from the last fetch',
    last_fetch_at           DATETIME,
    INDEX idx_position_address_position (position_id),
    CONSTRAINT fk_position_address_position FOREIGN KEY (position_id) REFERENCES `position` (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 2. Neue Tabelle: transaction
-- Hinweis für bestehende MySQL-Installationen: dieses Skript läuft (anders als
-- schema-h2.sql) nicht automatisch bei jedem Start — transaction_id ggf.
-- manuell nachziehen: ALTER TABLE `transaction` MODIFY transaction_id VARCHAR(100);
--                      ALTER TABLE import_staging_row MODIFY transaction_id VARCHAR(100);
--                      ALTER TABLE `transaction` ADD COLUMN blockchain_tx_id VARCHAR(64);
--                      ALTER TABLE import_staging_row ADD COLUMN blockchain_tx_id VARCHAR(64);
CREATE TABLE IF NOT EXISTS transaction (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 100 statt 36: echte Bitcoin-TXIDs (64 Hex-Zeichen) + ggf. "-in"/"-out"-
    -- Suffix (Selbst-Transfer-Paare) sprengen die alte UUID-Länge (36).
    transaction_id    VARCHAR(100),
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
    blockchain_tx_id VARCHAR(64)           COMMENT 'Echte On-Chain-Bitcoin-TXID, getrennt von transaction_id (Import-Dedup-Schlüssel) — nur TRANSFER_IN/TRANSFER_OUT, optional, weich validiert',
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
-- source: 'MANUAL' (Standard) oder 'MEMPOOL' (per Knopfdruck von der in
-- app_settings konfigurierten mempool-API geholt) — siehe MempoolPriceService.
-- Bestandsinstallationen: ALTER TABLE current_price ADD COLUMN source
--   VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
CREATE TABLE `current_price` (
  `ticker` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'BTC',
  `currency` varchar(10) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EUR',
  `price` decimal(14,2) NOT NULL,
  `price_date` date NOT NULL,
  `loaded_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `source` varchar(20) NOT NULL DEFAULT 'MANUAL',
  PRIMARY KEY (`ticker`,`currency`)
) ENGINE=InnoDB;

-- historical_price / monthly_price fehlten bisher komplett in diesem Skript,
-- obwohl HistoricalPriceService/MonthlyPriceService (Bestands- bzw.
-- Jahresansicht) sie voraussetzen — auf MySQL führte das zu SQL-Fehlern
-- ("Table doesn't exist"), unabhängig von der mempool-Integration. Hier als
-- Bugfix ergänzt, 1:1 zu schema-h2.sql.

-- Year-end (31.12.) reference prices per currency — siehe HistoricalPriceSeeder.
CREATE TABLE IF NOT EXISTS historical_price (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker     VARCHAR(10)    NOT NULL DEFAULT 'BTC',
    price_year INT            NOT NULL,
    currency   VARCHAR(10)    NOT NULL,
    price      DECIMAL(18,2)  NOT NULL,
    UNIQUE KEY uq_hp_ticker_year_currency (ticker, price_year, currency),
    INDEX idx_hp_year (price_year)
) ENGINE=InnoDB;

-- Monthly (Ultimo) reference prices per currency — siehe MonthlyPriceSeeder,
-- MonthlyPriceService. source: 'MANUAL' (CSV-Seed/manuelle Eingabe) oder
-- 'MEMPOOL' (per Bulk-Fill von der mempool-API geholt).
CREATE TABLE IF NOT EXISTS monthly_price (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker      VARCHAR(10)    NOT NULL DEFAULT 'BTC',
    price_year  INT            NOT NULL,
    price_month INT            NOT NULL,
    currency    VARCHAR(10)    NOT NULL,
    price       DECIMAL(18,2)  NOT NULL,
    source      VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    loaded_at   DATETIME,
    UNIQUE KEY uq_mp_ticker_year_month_currency (ticker, price_year, price_month, currency),
    INDEX idx_mp_year_month (price_year, price_month)
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
    transaction_id  VARCHAR(100),
    blockchain_tx_id VARCHAR(64)   COMMENT 'Echte On-Chain-Bitcoin-TXID, analog zu transaction.blockchain_tx_id',
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
-- App-weite Einstellungen (Singleton-Zeile, feste id=1)
-- ============================================================

-- tax_holding_period_cutoff_date: Stichtag, ab dem für neu angeschaffte
-- Coins (Kaufdatum >= Stichtag) die 1-Jahres-Haltefrist-Steuerfreiheit
-- (rein informativ, keine Steuerberatung) nicht mehr gilt. NULL = deaktiviert
-- (Standard), vom Nutzer über die Einstellungen setzbar.
--
-- mempool_host/mempool_port: Host/Port einer selbst gehosteten mempool-
-- Instanz für den optionalen Preisabruf — siehe MempoolPriceService. Beide
-- NULL (Standard) = Funktion deaktiviert.
--
-- login_password_hash: BCrypt hash of the optional, app-wide login password
-- — see SecurityConfig/AppSettings entity. NULL/blank (default) = login
-- disabled.
-- Bestandsinstallationen: ALTER TABLE app_settings
--   ADD COLUMN mempool_host VARCHAR(255), ADD COLUMN mempool_port INT,
--   ADD COLUMN login_password_hash VARCHAR(255);
CREATE TABLE IF NOT EXISTS app_settings (
    id                              BIGINT NOT NULL PRIMARY KEY,
    tax_holding_period_cutoff_date  DATE,
    mempool_host                    VARCHAR(255),
    mempool_port                    INT,
    login_password_hash             VARCHAR(255)
) ENGINE=InnoDB;
INSERT IGNORE INTO app_settings (id, tax_holding_period_cutoff_date) VALUES (1, NULL);

-- ============================================================
-- Sample data
-- ============================================================
INSERT INTO position (label, type) VALUES
    ('Binance', 'EXCHANGE'),
    ('Wallet 1-A', 'WALLET')
ON DUPLICATE KEY UPDATE label = VALUES(label);
