# Bitcoin Portfolio Tracker

A self-hosted, privacy-first Bitcoin portfolio tracker.  
Runs locally as a single JAR — no cloud, no accounts, no ads, no internet connection required.

📖 **Documentation:** in the app via the Help button, or directly at
[`/docs/index.html`](http://localhost:8080/docs/index.html) once the app is running —
source in [`src/main/resources/static/docs`](src/main/resources/static/docs).

![Java](https://img.shields.io/badge/Java-21-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

---

## Features

- Track BTC positions across multiple exchanges and wallets
- BUY / SELL / TRANSFER_IN / TRANSFER_OUT transactions
- Manual BTC price entry (EUR, USD, THB, …) — works fully offline; optionally fetch the current price / fill missing monthly prices from your own self-hosted mempool instance (EUR/USD only, opt-in, see Configuration Reference)
- CSV import with flexible column mapping (PapaParse)
- CSV export compatible with common tax tools. (Optional with password to encrypt data)
- Visualizations: Sankey flow diagram of BTC movements, holdings dashboard (allocation, metrics, per-purchase breakdown), yearly performance chart (holdings + value over time)
- Dark / light theme, EN / DE / TH UI

---

## Requirements

| Dependency | Version |
|---|---|
| Java | 21+ |
| Database | H2 (embedded) **or** MySQL 8+ |

---

## Installation

Five ways to run it — pick what fits your setup. Full step-by-step guides
(screenshots included) are in the [documentation](src/main/resources/static/docs)
under "Usage & Setup".

### 1. Direct Download (GitHub Releases)

Download the JAR (Java 21+ required) or, on Windows without Java, the portable
ZIP with bundled JRE.

```bash
java -jar btc-tracking.jar
```

→ [Releases](https://github.com/thatsme4now/btc-tracking/releases)

### 2. Umbrel Community App Store

One-click install on your Umbrel node via a dedicated Community App Store
(no listing in the official Umbrel App Store needed).

In umbrelOS: **App Store → Community App Stores** → add
`https://github.com/thatsme4now/thatsme4now-umbrel-apps` → install **BTC Tracking**.

→ [thatsme4now/thatsme4now-umbrel-apps](https://github.com/thatsme4now/thatsme4now-umbrel-apps)

### 3. Docker

```bash
docker run -d \
  --name btc-tracking \
  -p 8080:8080 \
  -v btc-tracking-data:/app/data \
  thatsme4now/btc-tracking:latest
```

→ [Docker Hub](https://hub.docker.com/repository/docker/thatsme4now/btc-tracking/general) · the named volume keeps data across restarts/updates.

### 4. Proxmox (VM / LXC)

No dedicated Proxmox template — runs as a regular VM or LXC container with
Docker installed, then use the `docker run` command from option 3 inside it.
For LXC, enable nested virtualization first (**Features → Nesting**).

### 5. Build from Source

```bash
git clone https://github.com/thatsme4now/btc-tracking.git
cd btc-tracking
./gradlew bootJar
java -jar build/libs/btc-tracking.jar
```

---

## Quick Start — H2 File (default, recommended)

No database setup required. Data is persisted in `btc-tracking-data.mv.db` next to the JAR.
The file `btc-tracking-data.mv.db` is created automatically on first run. **Back it up to keep your data safe.**

```bash
java -jar btc-tracking.jar
```

The browser opens automatically at `http://localhost:8080/btc-tracking`.

<details>
<summary>In-Memory (testing only, no persistence)</summary>

Create `application-local.properties` next to the JAR:

```properties
depot.db=inmemory
```

```bash
java -jar btc-tracking.jar --spring.config.additional-location=./application-local.properties
```
</details>

<details>
<summary>MySQL (persistent)</summary>

1. Create the database and run the schema:

```bash
mysql -u root -p < depot.sql
```

2. Create `application-local.properties` next to the JAR:

```properties
depot.db=mysql
spring.datasource.url=jdbc:mysql://localhost:3306/btc-tracking
spring.datasource.username=YOUR_USER
spring.datasource.password=YOUR_PASSWORD
```

3. Run:

```bash
java -jar btc-tracking.jar --spring.config.additional-location=./application-local.properties
```
</details>

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

**Mempool integration (optional, opt-in):** in-app under Settings → "Mempool integration",
not an `application.properties` setting. Enter the host/port of your own self-hosted
mempool instance (e.g. the mempool app on your Umbrel/LAN) to enable fetching the current
BTC price by button and filling in missing monthly (Ultimo) reference prices in the yearly
view — EUR/USD only, disabled by default (empty host/port). This app's own server (not your
browser) connects to that host/port; only use your local network. See Data & Disclaimer below.


## Project Structure

```
src/main/java/com/thatsme4now/depot/
├── controller/      # REST + MVC controllers
├── dto/             # Data transfer objects
├── entity/          # JPA entities
├── repository/      # Spring Data repositories
├── service/         # Business logic, CSV import
src/main/resources/
├── templates/       # Thymeleaf HTML
├── static/          # JS (depot.js, i18n.js, currency.js), CSS, docs/ (in-app help)
├── i18n/            # en.json, de.json, th.json
├── schema-h2.sql    # H2 schema
├── depot.sql        # MySQL schema + sample data
```

---

## Data & Disclaimer

All data, calculations, and exports are provided "as is" without warranty.
This tool is not financial or tax advice — verify all figures independently
before relying on them (e.g. for tax filing).

By default the app makes no outbound network calls — all BTC prices (current
and historical) are entered manually or come from the bundled CSV seed data.
The only exception is the optional, opt-in mempool integration (see
Configuration Reference): if you explicitly configure a host/port there, this
app's server will make local-network HTTP requests to the mempool instance
you specified, only when you press the corresponding button. Nothing is
contacted unless you configure it yourself.
This project is not affiliated with, endorsed by, or sponsored by the
Bitcoin Foundation, Umbrel, Docker, Proxmox, or the mempool project.

See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for third-party licenses.

Parts of this project were developed with AI assistance (Claude). No legal
disclosure requirement applies — noted here for transparency.

---

## ⚠️ Public Deployment Notice

This application is designed for private, self-hosted use. If you expose your
instance publicly (reverse proxy, port forwarding, cloud hosting, etc.),
**you** become the legal operator/provider of that public service under
applicable laws (e.g., EU/German telemedia and data protection law, including
GDPR). This includes any obligation to provide an imprint (*Impressum*) and a
privacy policy for your public instance.

The author provides this software "as is" and is not responsible for how
individual users choose to deploy or expose it.

---

## License

[MIT](LICENSE)
