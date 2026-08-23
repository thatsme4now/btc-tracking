-- ============================================================
-- H2 In-Memory Schema
-- MODE=MySQL in JDBC URL aktiviert MySQL-Kompatibilität,
-- damit reservierte Wörter wie TRANSACTION funktionieren.
-- ============================================================

CREATE TABLE IF NOT EXISTS `position` (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    label       VARCHAR(100)  NOT NULL,
    type        VARCHAR(20)   NOT NULL DEFAULT 'EXCHANGE',
    description TEXT,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Migration für bestehende Installationen.
ALTER TABLE `position` ADD COLUMN IF NOT EXISTS description TEXT;

-- position_address: optionale Bitcoin-Adressen pro Position, für den
-- On-Chain-Bestandsabruf über die konfigurierte mempool-Instanz — siehe
-- PositionAddress-Entity/MempoolPriceService. last_fetch_*: zwischen-
-- gespeichertes Ergebnis des letzten Abrufs, um bei einem erneuten Abruf
-- Änderungen erkennen zu können, ohne dafür extra nachzufragen.
CREATE TABLE IF NOT EXISTS position_address (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id             BIGINT        NOT NULL,
    address                 VARCHAR(120)  NOT NULL,
    label                   VARCHAR(100),
    last_fetch_json         TEXT,
    last_fetch_balance_sats BIGINT,
    last_fetch_txs_json     TEXT,
    last_fetch_at           TIMESTAMP,
    CONSTRAINT fk_position_address_position FOREIGN KEY (position_id)
        REFERENCES `position`(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_position_address_position ON position_address (position_id);
-- Migration für bestehende Installationen.
ALTER TABLE position_address ADD COLUMN IF NOT EXISTS last_fetch_txs_json TEXT;

CREATE TABLE IF NOT EXISTS `transaction` (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 100 statt 36: echte Bitcoin-TXIDs (64 Hex-Zeichen) + ggf. "-in"/"-out"-
    -- Suffix (Selbst-Transfer-Paare) sprengen die alte UUID-Länge (36).
    transaction_id    VARCHAR(100),
    position_id   BIGINT        NOT NULL,
    type          VARCHAR(20)   NOT NULL,
    date          TIMESTAMP     NOT NULL,
    quantity      DECIMAL(18,8) NOT NULL,
    quantity_fiat DECIMAL(14,2),
    currency      VARCHAR(10)   NOT NULL DEFAULT 'EUR',
    exchange_rate DECIMAL(14,6) NOT NULL DEFAULT 1.000000,
    price_per_btc DECIMAL(14,2),
    fees          DECIMAL(18,8),
    fees_currency VARCHAR(10)   ,
    comment       VARCHAR(255),
    transfer_id   VARCHAR(36),
    is_duplicate  BOOLEAN       NOT NULL DEFAULT FALSE,
    import_history_id BIGINT,
    -- Echte On-Chain-Bitcoin-TXID (64 Hex-Zeichen), getrennt von transaction_id
    -- oben (das ist der interne CSV-Import-Dedup-Schlüssel). Nur bei
    -- TRANSFER_IN/TRANSFER_OUT relevant, optional, weich validiert — dient
    -- dem Sprung-Link in die konfigurierte mempool-Instanz.
    blockchain_tx_id VARCHAR(64),
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tx_position FOREIGN KEY (position_id)
        REFERENCES `position`(id) ON DELETE CASCADE
);
-- Migration für bestehende Installationen.
ALTER TABLE `transaction` ADD COLUMN IF NOT EXISTS blockchain_tx_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_tx_position ON `transaction`(position_id);
CREATE INDEX IF NOT EXISTS idx_tx_transfer ON `transaction`(transfer_id);
-- idx_tx_import_history NICHT hier: auf bestehenden Installationen existiert
-- die Spalte an dieser Stelle im Skript noch nicht (CREATE TABLE IF NOT
-- EXISTS oben ist dann ein No-op) — der Index wird weiter unten, NACH dem
-- ALTER TABLE ADD COLUMN, angelegt.

CREATE TABLE IF NOT EXISTS price_history (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker    VARCHAR(10)   NOT NULL DEFAULT 'BTC',
    date      DATE          NOT NULL,
    open      DECIMAL(14,2),
    high      DECIMAL(14,2),
    low       DECIMAL(14,2),
    close     DECIMAL(14,2) NOT NULL,
    volume    BIGINT,
    loaded_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ticker_date UNIQUE (ticker, date)
);

CREATE INDEX IF NOT EXISTS idx_ph_ticker ON price_history(ticker);
CREATE INDEX IF NOT EXISTS idx_ph_date   ON price_history(date);

CREATE TABLE IF NOT EXISTS current_price (
    ticker     VARCHAR(10)   NOT NULL,
    currency   VARCHAR(10)   NOT NULL DEFAULT 'EUR',
    price      DECIMAL(14,4) NOT NULL,
    price_date DATE          NOT NULL,
    loaded_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source     VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    PRIMARY KEY (ticker, currency)
);
-- Migration für bestehende Installationen (CREATE TABLE IF NOT EXISTS oben
-- greift bei bereits vorhandener Tabelle nicht mehr).
ALTER TABLE current_price ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

-- Year-end (31.12.) reference prices per currency, used by the "Bestandsansicht"
-- (yearly holdings) visualization for past years. Approximate values, seeded
-- once per (ticker, year, currency) by HistoricalPriceSeeder — see that class.
CREATE TABLE IF NOT EXISTS historical_price (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker     VARCHAR(10)    NOT NULL DEFAULT 'BTC',
    price_year INT            NOT NULL,
    currency   VARCHAR(10)    NOT NULL,
    price      DECIMAL(18,2)  NOT NULL,
    CONSTRAINT uq_hp_ticker_year_currency UNIQUE (ticker, price_year, currency)
);

CREATE INDEX IF NOT EXISTS idx_hp_year ON historical_price(price_year);

-- Monthly (Ultimo, i.e. last day of month) reference prices per currency,
-- used by the "Jahresansicht" visualization. Seeded once from the bundled
-- src/main/resources/data/monthly-btc-prices.csv resource (see
-- MonthlyPriceSeeder), and/or optionally filled in via a user-configured
-- self-hosted mempool instance (see MempoolPriceService,
-- MonthlyPriceService#fillMissingFromMempool) — never overwritten once a
-- row exists, whether seeded, mempool-filled, or manually corrected by the
-- user. `source` distinguishes MANUAL (seed/manual entry) from MEMPOOL.
CREATE TABLE IF NOT EXISTS monthly_price (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker      VARCHAR(10)    NOT NULL DEFAULT 'BTC',
    price_year  INT            NOT NULL,
    price_month INT            NOT NULL,
    currency    VARCHAR(10)    NOT NULL,
    price       DECIMAL(18,2)  NOT NULL,
    source      VARCHAR(20)    NOT NULL DEFAULT 'MANUAL',
    loaded_at   TIMESTAMP,
    CONSTRAINT uq_mp_ticker_year_month_currency UNIQUE (ticker, price_year, price_month, currency)
);

CREATE INDEX IF NOT EXISTS idx_mp_year_month ON monthly_price(price_year, price_month);

-- Migration für bestehende Installationen.
ALTER TABLE monthly_price ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE monthly_price ADD COLUMN IF NOT EXISTS loaded_at TIMESTAMP;

-- ============================================================
-- CSV-Import-Assistent (3-Step-Wizard): Staging-Tabelle + Historie
-- ============================================================

-- Zwischenspeicher für Zeilen aus einem laufenden Import (Step 2 "Review"),
-- bevor sie final in die transaction-Tabelle übernommen werden. Wird beim
-- Übergang Step 1 → Step 2 befüllt und nach Bestätigen/Abbrechen bzw. vor
-- jedem neuen Datei-Upload komplett geleert (siehe ImportWizardService).
CREATE TABLE IF NOT EXISTS import_staging_row (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    row_index       INT            NOT NULL,
    raw_typ         VARCHAR(50),
    type            VARCHAR(20),
    position_label  VARCHAR(100),
    date_raw        VARCHAR(64),
    date_parsed     TIMESTAMP,
    quantity        DECIMAL(18,8),
    quantity_fiat   DECIMAL(14,2),
    price_per_btc   DECIMAL(14,2),
    currency        VARCHAR(10),
    exchange_rate   DECIMAL(14,6),
    fees            DECIMAL(18,8),
    fees_currency   VARCHAR(10),
    comment         VARCHAR(255),
    transaction_id  VARCHAR(100),
    -- Echte On-Chain-Bitcoin-TXID, analog zu transaction.blockchain_tx_id.
    -- Optionales CSV-Mapping, siehe ImportWizardService#buildTradeRows/buildSelfRows.
    blockchain_tx_id VARCHAR(64),
    transfer_id     VARCHAR(36),
    is_duplicate    BOOLEAN        NOT NULL DEFAULT FALSE,
    is_fx_warning   BOOLEAN        NOT NULL DEFAULT FALSE,
    has_error       BOOLEAN        NOT NULL DEFAULT FALSE,
    error_reason    VARCHAR(255),
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_isr_row_index ON import_staging_row(row_index);
-- Migration für bestehende Installationen.
ALTER TABLE import_staging_row ADD COLUMN IF NOT EXISTS blockchain_tx_id VARCHAR(64);

-- Einfache Historie abgeschlossener Imports, angezeigt als eigene Kachel
-- auf der Übersicht.
CREATE TABLE IF NOT EXISTS import_history (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    imported_at    TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    filename       VARCHAR(255)   NOT NULL,
    total_rows     INT            NOT NULL DEFAULT 0,
    imported_rows  INT            NOT NULL DEFAULT 0,
    duplicate_rows INT            NOT NULL DEFAULT 0,
    error_rows     INT            NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ih_imported_at ON import_history(imported_at);

-- Migration für bestehende Installationen: `transaction` existierte schon vor
-- dieser Spalte, das CREATE TABLE IF NOT EXISTS oben greift bei bereits
-- vorhandener Tabelle nicht mehr (H2 überspringt es dann komplett). Dieses
-- Skript läuft bei jedem Start (siehe DatabaseConfig#dataSourceInitializer),
-- ADD COLUMN IF NOT EXISTS ist daher idempotent nachgezogen. Bewusst OHNE
-- FK-Constraint (siehe ImportWizardService#deleteHistory: die Zuordnung wird
-- vor dem Löschen eines History-Eintrags applikationsseitig aufgelöst).
ALTER TABLE `transaction` ADD COLUMN IF NOT EXISTS import_history_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_tx_import_history ON `transaction`(import_history_id);

-- Migration für bestehende Installationen: transaction_id war ursprünglich auf
-- UUID-Länge (36) ausgelegt (nur für Selbst-Transfer-Paare genutzt), echte
-- Bitcoin-TXIDs aus CSV-Importen (64 Hex-Zeichen, teils + "-in"/"-out"-Suffix)
-- passten dort nicht rein (JdbcSQLDataException beim Staging). ALTER COLUMN
-- ist idempotent (auf bereits VARCHAR(100)-Spalten ein No-op), läuft daher
-- gefahrlos bei jedem Start mit.
ALTER TABLE `transaction` ALTER COLUMN transaction_id VARCHAR(100);
ALTER TABLE import_staging_row ALTER COLUMN transaction_id VARCHAR(100);

-- ============================================================
-- App-weite Einstellungen (Singleton-Zeile, feste id=1)
-- ============================================================

-- tax_holding_period_cutoff_date: Stichtag, ab dem für neu angeschaffte
-- Coins (Kaufdatum >= Stichtag) die 1-Jahres-Haltefrist-Steuerfreiheit
-- (rein informativ, keine Steuerberatung) nicht mehr gilt — siehe
-- AppSettings-Entity. NULL = deaktiviert (Standard), vom Nutzer über die
-- Einstellungen setzbar.
--
-- mempool_host/mempool_port: Host/Port einer selbst gehosteten mempool-
-- Instanz für den optionalen Preisabruf (current-price/mempool,
-- monthly-prices/fill-missing) — siehe MempoolPriceService. Beide NULL
-- (Standard) = Funktion deaktiviert.
CREATE TABLE IF NOT EXISTS app_settings (
    id                              BIGINT NOT NULL PRIMARY KEY,
    tax_holding_period_cutoff_date  DATE,
    mempool_host                    VARCHAR(255),
    mempool_port                    INT
);
INSERT INTO app_settings (id, tax_holding_period_cutoff_date)
    SELECT 1, NULL WHERE NOT EXISTS (SELECT 1 FROM app_settings WHERE id = 1);

-- Migration für bestehende Installationen.
ALTER TABLE app_settings ADD COLUMN IF NOT EXISTS mempool_host VARCHAR(255);
ALTER TABLE app_settings ADD COLUMN IF NOT EXISTS mempool_port INT;