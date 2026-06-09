# Bitcoin Portfolio Tracker — Depot

A self-hosted, privacy-first Bitcoin portfolio tracker.  
Runs locally as a single JAR — no cloud, no accounts, no ads.

If you enjoy this small tool, please spend some sats: `turbolush199@walletofsatoshi.com`

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## Features

- Track BTC positions across multiple exchanges and wallets
- BUY / SELL / TRANSFER_IN / TRANSFER_OUT transactions
- Automatic price history via CoinGecko (EUR, USD, THB, …)
- CSV import with flexible column mapping (PapaParse)
- CSV export compatible with common tax tools
- Multi-currency display with live exchange-rate support
- Dark / light theme, EN / DE / TH UI
- Runs fully offline after initial price fetch

---

## Requirements

| Dependency | Version |
|---|---|
| Java | 21+ |
| Database | H2 (embedded) **or** MySQL 8+ |

---

## Quick Start — In-Memory (H2) (default, recommended)

No database setup, no persistence. Data is lost on every restart. Useful for testing.

```bash
java -jar depot.jar
```

The browser opens automatically at `http://localhost:8080/depot`.  

---

## Quick Start — H2 File

No database setup required. Data is persisted in `depot-data.mv.db` next to the JAR.
The file `depot-data.mv.db` is created automatically on first run. **Back it up to keep your data safe.**

Create `application-local.properties` next to the JAR:

```properties
depot.db=h2file
```

```bash
java -jar depot.jar --spring.config.additional-location=./application-local.properties
```

---

## Quick Start — MySQL (persistent)

1. Create the database and run the schema:

```bash
mysql -u root -p < depot.sql
```

2. Create `application-local.properties` next to the JAR:

```properties
depot.db=mysql
spring.datasource.url=jdbc:mysql://localhost:3306/depot
spring.datasource.username=YOUR_USER
spring.datasource.password=YOUR_PASSWORD
```

3. Run:

```bash
java -jar depot.jar --spring.config.additional-location=./application-local.properties
```

---

## Build from Source

```bash
git clone https://github.com/thatsme4now/depot.git
cd depot
./gradlew bootJar
```

Output: `build/libs/depot.jar`

**Run after build:**

```bash
java -jar build/libs/depot.jar
```

### Build Portable ZIP (JAR + bundled JRE)

Requires JDK 21 with `jlink` available.

```bash
./gradlew portableZip
# Output: build/dist/DepotBitcoin-portable.zip
```

### Build Windows Portable ZIP (downloads Temurin JRE)

```bash
./gradlew portableWinZip
# Output: build/dist/DepotBitcoin-portable-win.zip (~90 MB)
```

---

## Download Pre-built Release

Go to [Releases](../../releases) and download `depot.jar` from the latest release.

```bash
java -jar depot.jar
```

Java 21+ must be installed on your machine.

---

## Configuration Reference

All settings in `application.properties` (or override via external file / environment variables):

| Property | Default | Description |
|---|---|---|
| `depot.db` | `h2file` | `inmemory`, `h2file`, or `mysql` |
| `server.port` | `8080` | HTTP port |
| `spring.datasource.url` | — | MySQL JDBC URL |
| `spring.datasource.username` | — | MySQL user |
| `spring.datasource.password` | — | MySQL password |
| `spring.jpa.show-sql` | `false` | Log SQL statements |

---

## CSV Import Format

The import modal maps any CSV format to the internal schema interactively.  
The **export** format (re-importable) uses these columns:

| Column | Example |
|---|---|
| `typ` | `Trade`, `Einzahlung`, `Auszahlung` |
| `date` | `01.01.2024 14:30:00` |
| `exchange` | `Binance` |
| `buyQuantity` | `0.00500000` |
| `buyCurrency` | `BTC` |
| `sellQuantity` | `280.50` |
| `sellCurrency` | `EUR` |
| `fee` | `1.40` |
| `feeCurrency` | `EUR` |
| `exchangeRate` | `1.000000` |
| `comment` | optional |

---

## Project Structure

```
src/main/java/com/thatsme4now/depot/
├── controller/      # REST + MVC controllers
├── dto/             # Data transfer objects
├── entity/          # JPA entities
├── repository/      # Spring Data repositories
├── service/         # Business logic, CoinGecko, CSV import
src/main/resources/
├── templates/       # Thymeleaf HTML
├── static/          # JS (depot.js, i18n.js, currency.js), CSS
├── i18n/            # en.json, de.json, th.json
├── schema-h2.sql    # H2 schema
├── depot.sql        # MySQL schema + sample data
```

---

## License

[MIT](LICENSE)