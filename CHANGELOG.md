# Changelog

All notable changes to this project will be documented here.  
Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

---

## [1.0.0] — 2024-XX-XX

### Added
- Initial release
- BTC portfolio tracking across multiple exchanges and wallets
- BUY / SELL / TRANSFER_IN / TRANSFER_OUT transaction types
- CoinGecko price history (365 days, daily OHLC)
- Multi-currency support: EUR, USD, THB
- CSV import with interactive column mapping (PapaParse)
- CSV export (re-importable format)
- Allocation donut chart (ApexCharts)
- BTC price history chart (1 year, area)
- Inline BTC price edit
- Dark / light theme toggle
- i18n: English, German, Thai
- H2 in-memory mode (zero-config)
- MySQL mode (persistent)
- Portable ZIP builds via Gradle (`portableZip`, `portableWinZip`)
- Auto-opens browser on startup
- Offline mode toggle (blocks CoinGecko fetch)
