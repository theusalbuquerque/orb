# Orb source restoration

The GitHub tree previously omitted the consolidated Orb screens and supporting
models while retaining parts of the newer playback code. Restoring only the
missing screens is insufficient: navigation, authentication, repositories,
settings, resources, native analysis and both flavor source sets belong together.

## Source provenance

- Complete UI baseline: the owner's `src (2)(2).zip`, with the changes from
  `src_automix_2.5_mix_v5_references.zip`.
- Playback integration: protocol v7 / mix-v8 remote analysis curves, tempo
  envelope, bounded harmonic adjustment, musical AutoPlay previews and explicit
  original-album-order state.
- The complete Orb playback controller retains artwork transition choreography
  and the real-stem implementation. Existing Orb 2.0 local planning is retained.
- The complete app build configuration was recovered from the owner's saved
  Gradle configuration and brought to the stated 1.6.2 version. No release tag or
  published APK is replaced by this source restoration.
- The server's existing mix-v8 implementation is retained.

## Build

Use JDK 17 and the committed Gradle wrapper:

```sh
./gradlew :app:compileDevDebugKotlin :app:compileProdDebugKotlin
./gradlew :app:assembleDevDebug :app:assembleProdDebug
```

The Android workflow checks both variants and assembles their debug APKs. A
successful compile does not validate audio transitions or appearance on a device.

## Local configuration

Use ignored `local.properties`, user-level Gradle properties, or environment
variables for optional configuration. Values are never included in this document.

- `ORB_SUPABASE_URL`
- `ORB_SUPABASE_PUBLISHABLE_KEY` (publishable client key only)
- `ORB_GOOGLE_WEB_CLIENT_ID`
- `ORB_MUSIXMATCH_SIGNING_SECRET`
- `ORB_DONATION_RECIPIENT`
- `ORB_DONATION_PIX_KEY`
- `ORB_DONATION_USD_ACCOUNT`
- `ORB_DONATION_USD_ACCOUNT_TYPE`
- `ORB_DONATION_USD_ROUTING`
- `ORB_DONATION_EUR_IBAN`
- `ORB_DONATION_CNY_IBAN`
- `ORB_DONATION_GBP_ACCOUNT`
- `ORB_DONATION_GBP_SORT_CODE`
- `ORB_DONATION_GBP_IBAN`

Publication review blocked embedded signing material and personal bank details in
the recovered source. Those values were externalized. Musixmatch is skipped when
its configuration is absent; other lyrics providers remain available. The
contribution screen retains its design and only lists fully configured payment
methods. Google/Supabase also require the owner's configuration for live login
and social features. Build-time values become part of the APK; never supply
privileged server credentials or service-role keys.

The original GitHub main is retained at
`backup/main-before-orb-ui-restoration`.
