# Contributing

## Prerequisites

- JDK 21
- Git

No IDE required — Gradle wrapper is included.

## Local Setup

```bash
git clone https://github.com/YOUR_USERNAME/depot.git
cd depot
./gradlew bootJar
java -jar build/libs/depot.jar
```

App starts at `http://localhost:8080/depot` with H2 in-memory DB.

## Making Changes

1. Fork the repository
2. Create a branch: `git checkout -b feature/your-feature`
3. Make your changes
4. Verify the app starts and your change works end-to-end
5. Open a Pull Request against `main`

## Code Conventions

- Java 21, Spring Boot 3.x patterns
- Lombok for boilerplate (`@Data`, `@RequiredArgsConstructor`)
- No Spring Security — this is a local single-user tool
- Keep JS vanilla (no build toolchain for frontend)
- i18n keys go in all three files: `en.json`, `de.json`, `th.json`

## Adding a Currency

**Backend:** CoinGecko already supports any `vs_currency`. No backend change needed.

**Frontend** (`currency.js`):
```js
{ code: 'GBP', symbol: '£', locale: 'en-GB' }
```

## Adding a Language

Add `/src/main/resources/static/i18n/{lang}.json` with all keys from `en.json`,  
then register the code in `i18n.js`:
```js
const SUPPORTED = { en: 'English', de: 'Deutsch', th: 'ภาษาไทย', xx: 'Your Language' };
```

## Reporting Issues

Open a GitHub Issue with:
- Steps to reproduce
- Expected vs. actual behavior
- Java version (`java -version`) and OS
