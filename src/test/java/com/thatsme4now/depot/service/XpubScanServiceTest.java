package com.thatsme4now.depot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the xpub parsing/derivation math — no Spring context, no mempool, no DB,
 * since {@link XpubScanService#parseAccountKey} and {@link XpubScanService#deriveAddress} touch
 * neither (the injected MempoolPriceService is only used by the scan-orchestration methods, which
 * aren't exercised here).
 *
 * The BIP84 (zpub) vectors below are the official BIP84 test vectors (bitcoin/bips,
 * bip-0084.mediawiki, mnemonic "abandon abandon abandon abandon abandon abandon abandon abandon
 * abandon abandon abandon about") — account 0 zpub plus its first receive (m/84'/0'/0'/0/0) and
 * change (m/84'/0'/0'/1/0) addresses, so this is a genuine end-to-end correctness check of the
 * derivation + native-segwit address encoding, not just "bitcoinj was called with some arguments".
 *
 * The BIP32 master xpub/xprv below are BIP32's own official "Test Vector 1" (bip-0032.mediawiki,
 * seed 000102030405060708090a0b0c0d0e0f) — used here to verify legacy-type detection and, via the
 * xprv, that a pasted private key is firmly rejected rather than silently derived from. No verified
 * expected child address was available for this legacy vector, so that part is a structural check
 * (right prefix/length) rather than an exact-address assertion — the zpub test above is what
 * actually anchors the derivation math itself.
 */
class XpubScanServiceTest {

    private final XpubScanService service = new XpubScanService(null);

    private static final String BIP84_ZPUB =
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs";
    private static final String BIP84_RECEIVE_0 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
    private static final String BIP84_CHANGE_0 = "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el";

    private static final String BIP32_VECTOR1_XPUB =
            "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8";
    private static final String BIP32_VECTOR1_XPRV =
            "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi";

    @Test
    void parseAccountKey_zpub_detectsNativeSegwitType() {
        XpubScanService.ParsedAccountKey account = service.parseAccountKey(BIP84_ZPUB);
        assertThat(account.type()).isEqualTo(XpubScanService.AddressType.NATIVE_SEGWIT);
    }

    @Test
    void deriveAddress_zpub_matchesOfficialBip84TestVector_receiveAndChange() {
        XpubScanService.ParsedAccountKey account = service.parseAccountKey(BIP84_ZPUB);

        assertThat(service.deriveAddress(account, 0, 0)).isEqualTo(BIP84_RECEIVE_0);
        assertThat(service.deriveAddress(account, 1, 0)).isEqualTo(BIP84_CHANGE_0);
    }

    @Test
    void deriveAddress_zpub_secondReceiveAddressDiffersFromFirst() {
        // No official vector for index 1, but a basic sanity check that derivation actually
        // advances instead of accidentally returning the same address for every index.
        XpubScanService.ParsedAccountKey account = service.parseAccountKey(BIP84_ZPUB);
        assertThat(service.deriveAddress(account, 0, 1)).isNotEqualTo(BIP84_RECEIVE_0);
    }

    @Test
    void parseAccountKey_xpub_detectsLegacyType() {
        XpubScanService.ParsedAccountKey account = service.parseAccountKey(BIP32_VECTOR1_XPUB);
        assertThat(account.type()).isEqualTo(XpubScanService.AddressType.LEGACY);
    }

    @Test
    void deriveAddress_xpub_producesPlausibleLegacyAddress() {
        // Structural check only (no verified expected address for this vector's children) — starts
        // with '1' (mainnet P2PKH) and is a plausible base58 address length.
        XpubScanService.ParsedAccountKey account = service.parseAccountKey(BIP32_VECTOR1_XPUB);
        String address = service.deriveAddress(account, 0, 0);
        assertThat(address).startsWith("1");
        assertThat(address.length()).isBetween(25, 35);
    }

    @Test
    void parseAccountKey_rejectsPrivateKey_withSpecificMessage() {
        assertThatThrownBy(() -> service.parseAccountKey(BIP32_VECTOR1_XPRV))
                .isInstanceOf(XpubScanService.XpubException.class)
                .hasMessageContaining("PRIVATEN");
    }

    @Test
    void parseAccountKey_rejectsBlank() {
        assertThatThrownBy(() -> service.parseAccountKey("   "))
                .isInstanceOf(XpubScanService.XpubException.class);
    }

    @Test
    void parseAccountKey_rejectsGarbage() {
        assertThatThrownBy(() -> service.parseAccountKey("not-a-valid-xpub-at-all"))
                .isInstanceOf(XpubScanService.XpubException.class);
    }

    @Test
    void parseAccountKey_rejectsTruncatedButBase58ValidInput() {
        // A short, plausible-looking but structurally wrong string — must not be accepted just
        // because it happens to contain only base58 characters.
        assertThatThrownBy(() -> service.parseAccountKey("xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWG"))
                .isInstanceOf(XpubScanService.XpubException.class);
    }
}
