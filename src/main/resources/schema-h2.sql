-- ============================================================
-- H2 In-Memory Schema
-- MODE=MySQL in JDBC URL aktiviert MySQL-Kompatibilität,
-- damit reservierte Wörter wie TRANSACTION funktionieren.
-- ============================================================

CREATE TABLE IF NOT EXISTS `position` (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    label      VARCHAR(100)  NOT NULL,
    type       VARCHAR(20)   NOT NULL DEFAULT 'EXCHANGE',
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `transaction` (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id    VARCHAR(36),
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
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tx_position FOREIGN KEY (position_id)
        REFERENCES `position`(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tx_position ON `transaction`(position_id);
CREATE INDEX IF NOT EXISTS idx_tx_transfer ON `transaction`(transfer_id);

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
    PRIMARY KEY (ticker, currency)
);

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
-- MonthlyPriceSeeder), and/or backfilled live via CoinGecko (see
-- MonthlyPriceService.backfill) — never overwritten once a row exists,
-- whether seeded, backfilled, or manually corrected by the user.
CREATE TABLE IF NOT EXISTS monthly_price (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticker      VARCHAR(10)    NOT NULL DEFAULT 'BTC',
    price_year  INT            NOT NULL,
    price_month INT            NOT NULL,
    currency    VARCHAR(10)    NOT NULL,
    price       DECIMAL(18,2)  NOT NULL,
    CONSTRAINT uq_mp_ticker_year_month_currency UNIQUE (ticker, price_year, price_month, currency)
);

CREATE INDEX IF NOT EXISTS idx_mp_year_month ON monthly_price(price_year, price_month);