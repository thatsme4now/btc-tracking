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
| `server.ssl.enabled` | `false` | Enable HTTPS — see [TLS / HTTPS](#tls--https) below |
| `server.ssl.key-store` | — | Path to a PKCS12 keystore, e.g. `file:./keystore.p12` |
| `server.ssl.key-store-password` | — | Keystore password |
| `server.ssl.key-store-type` | — | `PKCS12` |
| `server.ssl.key-alias` | — | Key alias inside the keystore |

**Mempool integration (optional, opt-in):** in-app under Settings → "Mempool integration",
not an `application.properties` setting. Enter the host/port of your own self-hosted
mempool instance (e.g. the mempool app on your Umbrel/LAN) to enable fetching the current
BTC price by button and filling in missing monthly (Ultimo) reference prices in the yearly
view — EUR/USD only, disabled by default (empty host/port). This app's own server (not your
browser) connects to that host/port; only use your local network. See Data & Disclaimer below.

**Password login (optional, opt-in):** in-app under Settings → "Password login", not an
`application.properties` setting. A single, app-wide password — no username, no separate
accounts. Off by default; enabling/disabling it or changing the password takes effect
immediately, no restart needed. Once set, it protects the whole app — every page and every API
endpoint — behind a login screen, and is separate from the "Lock" feature (which encrypts your
data at rest; Password login instead controls who can reach the app at all). Repeated wrong
attempts are rate-limited with an escalating lockout. Without HTTPS (see below), this protects
against casual access on your network but not against someone who can already observe your
network traffic — the password itself is never logged or stored anywhere except as a salted
hash.

### TLS / HTTPS

Off by default — the app serves plain HTTP, same as before this option existed. If you run it
directly (portable `.exe`, plain `java -jar`, or a bare `docker run -p 8080:8080` *without* a
reverse proxy in front) and want the connection itself encrypted — recommended if you also
enable Password login above, since otherwise the password travels in cleartext on your network
— you can turn on HTTPS with a self-signed certificate:

1. Generate a keystore (`keytool` ships with any JDK/JRE — replace `changeit` with your own
   password):
   ```bash
   keytool -genkeypair -alias btc-tracking -keyalg RSA -keysize 2048 -validity 3650 \
     -storetype PKCS12 -keystore keystore.p12 -storepass changeit \
     -dname "CN=btc-tracking"
   ```
2. Add to `application.properties`, next to the JAR (or `depot-data.mv.db`):
   ```properties
   server.ssl.enabled=true
   server.ssl.key-store=file:./keystore.p12
   server.ssl.key-store-password=changeit
   server.ssl.key-store-type=PKCS12
   server.ssl.key-alias=btc-tracking
   ```
3. Restart the app. It's now reachable only via `https://…` on the same port — plain `http://`
   will simply refuse to connect (there is no automatic redirect). Your browser will warn about
   the self-signed certificate the first time; the connection is still genuinely encrypted, you
   just need to accept/import the certificate once per device.

To turn it off again: remove those five lines (or set `server.ssl.enabled=false`) and restart —
plain configuration, no code changes or rebuild involved either way.

**Generic Docker (`docker run`, not Umbrel):** put `keystore.p12` in the same volume already
used for the database (`/app/data`) and pass the settings as environment variables instead of
editing a file inside the image (Spring Boot maps `SERVER_SSL_ENABLED` etc. to the equivalent
property automatically):
```bash
docker run -d \
  --name btc-tracking \
  -p 8080:8080 \
  -v btc-tracking-data:/app/data \
  -e SERVER_SSL_ENABLED=true \
  -e SERVER_SSL_KEY_STORE=file:/app/data/keystore.p12 \
  -e SERVER_SSL_KEY_STORE_PASSWORD=changeit \
  -e SERVER_SSL_KEY_STORE_TYPE=PKCS12 \
  -e SERVER_SSL_KEY_ALIAS=btc-tracking \
  thatsme4now/btc-tracking:latest
```

**⚠️ Do not enable this on the Umbrel install.** Umbrel already terminates HTTPS for you at its
own reverse proxy (`app_proxy`) in front of every app; that proxy expects to reach this
container over plain HTTP internally on the port declared in its `docker-compose.yml`. Turning
on `server.ssl.enabled` inside the Umbrel container would make the app unreachable through
Umbrel, since `app_proxy` wouldn't be able to speak HTTPS to it.


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
