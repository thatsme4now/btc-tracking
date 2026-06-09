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
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tx_position FOREIGN KEY (position_id)
        REFERENCES `position`(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tx_position ON `transaction`(position_id);
CREATE INDEX IF NOT EXISTS idx_tx_date     ON `transaction`(date);
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