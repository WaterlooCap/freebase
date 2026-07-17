# Freebase: OSS Conversion + Custom OIDC Connector

**Date:** 2026-07-17
**Status:** Design — approved for planning
**Author:** Alex (with Claude)

## Context

The `freebase` fork currently runs Metabase v0.62.3.3 as an *enterprise build with the
license check bypassed*. `token_check.clj` is patched to fake a MetaStore response and
return a hardcoded set of 58 premium features, so the instance behaves as
"enterprise-unlimited" without a token.

This is not sustainable or defensible. Of the 58 unlocked features, the deployment
actually needs exactly one: **OIDC SSO**.

This design converts the fork to a genuine OSS (AGPL) Metabase build and replaces the
bypassed enterprise OIDC with an original OIDC connector written against Metabase's
documented OSS authentication extension points.

## Goals

- Run a genuine OSS Metabase build. No license bypass, no faked token, no ungated EE routes.
- Provide OIDC SSO against the self-hosted Authentik IdP at `sso.waterloocap.com`.
- Keep the fork delta small enough that upstream upgrades stay cheap.
- Never phone home (telemetry / update checks stay off).

## Non-goals

- Multi-provider OIDC. One IdP (Authentik). YAGNI.
- Group-claim → Metabase group sync. Four users; admin is granted manually.
- SAML / JWT / SCIM. Not needed, not built.
- **Custom branding.** Deferred to a follow-up spec — see "Deferred: branding" below.
  Confirmed in use, but explicitly lower priority than OIDC and carries a licensing
  judgment call that should not be smuggled in alongside this work.

## Production audit (2026-07-17)

Audited the live instance at `analytics.waterloocap.com` via the REST API rather than
assuming. Of the 58 features the bypass unlocks, **exactly two are actually in use.**

| EE feature | Status | Evidence |
|---|---|---|
| `sso-oidc` | **In use** | 4 users, all internal `@waterloocap.com` |
| `whitelabel` | **In use** | `application-name="Waterloo"`, 77KB logo, 20KB favicon, brand palette, custom help link |
| `sandboxes` | Not in use | `/api/mt/gtap` → 0 policies |
| `transforms`, `workspaces` | Not in use | `/api/ee/transforms` 404; `workspace-manager` count=0 |
| `serialization` | Not in use | `/api/ee/serialization` 404 |
| `audit-app` | Not in use | `/api/ee/audit-app/user` 404 |
| `scim` | Not in use | `/api/ee/scim/v2/Users` 401 |
| `content-verification` | Not in use | 0 official collections, 0 verified items |
| `advanced-permissions` | Permissive only | see below |

**Instance shape:** 46 users total (42 password, 4 OIDC). 20 active users are *external*,
across five non-Waterloo domains (lhfinancial.net ×13, amgwealthadvisors.com ×2,
elementconsultants.com ×2, ironclad-wealth.com ×2, wcfos.com ×1).

**Critical: external-user isolation does not depend on EE.** All 80 group/database
combinations in the permission graph are `view-data=unrestricted` with
`download={"schemas":"full"}`. There is no `blocked` permission anywhere. Isolation
between client firms is done entirely with **collection permissions, which are OSS** and
survive the strip untouched. The only EE permission values present (`data-model`,
`details`) are permissive *grants*; losing them reverts those to admin-only — strictly
tighter, never looser. **The strip cannot widen anyone's data access.**

### The four OIDC users

| Email | Admin |
|---|---|
| `arose@waterloocap.com` | yes |
| `abuchanan@waterloocap.com` | yes |
| `ndyer@waterloocap.com` | yes |
| `tchatmas@waterloocap.com` | no |

All internal. Three of the five active admins authenticate via OIDC; the remaining
admin (`lmoulton@waterloocap.com`) uses a password and is a useful independent
break-glass.

## Deferred: branding

`whitelabel` is genuinely in use — the instance is branded "Waterloo" with a custom
logo, favicon, palette, and help link, and **20 external client users see that branding**.
Stripping EE reverts the UI to stock Metabase appearance. This is a visible,
client-facing change with commercial rather than security consequences.

The settings live in the OSS tree (`src/metabase/appearance/settings.clj`) but each
carries `:feature :whitelabel`; the getter returns the `"Metabase"` default rather than
the stored value when the feature is absent. The code that *applies* branding is split:
`enterprise/frontend/src/metabase-enterprise/whitelabel/` (EE) but also
`frontend/src/metabase/ui/colors/colors.ts` and `GlobalStyles.tsx` (OSS).

Three routes, recorded now so the reasoning is not lost:

1. **Hardcode into the fork.** Change the logo asset and the built-in defaults in AGPL
   source. The `:feature :whitelabel` gate stays intact and functioning — we change what
   our fork's *built-in* appearance is, not unlock runtime configurability. Modifying
   AGPL source is squarely permitted. Requires a rebuild to change.
2. **Own settings + own application code.** Ungated `wc-brand-*` settings plus our own
   frontend read-sites. Most conservative; most work; duplicates machinery.
3. **Delete `:feature :whitelabel`.** ❌ **Explicitly rejected.** This is the same act as
   the `token_check.clj` bypass, scoped to one feature: removing Metabase's license check
   to unlock Metabase's implementation. Adopting it would defeat the entire purpose of
   this project. Recorded here so it is never quietly reintroduced as a "quick win".

Options 1 and 2 reach the same outcome by different roads, and option 1 arguably defeats
the gate's *purpose* even though it does not remove it. That is a judgment call worth
making deliberately, with the OIDC work already shipped and not blocked on it.

## Licensing rationale

This is the crux of the exercise, so it is worth stating precisely.

**What we stop doing.** Using EE code without a license. The bypass is reverted and
`enterprise/backend/src` is removed from the build classpath entirely
(`MB_EDITION=oss`).

**What we keep doing, legitimately.** The `enterprise/` directory stays in the tree
untouched, with `LICENSE.txt` restored. This is not a compromise: the upstream
`metabase/metabase` repository ships that directory publicly under the same commercial
license, so possessing it is exactly the posture of any clone. The license text itself
says access "does not constitute permission to use this code or Metabase Enterprise
Edition features" — possession is fine; *use* is what requires a token. Deleting the
directory would buy no legal ground we do not already have, while guaranteeing a
permanent merge conflict on every upstream sync.

**What we build.** Original code against `metabase.auth-identity`, which documents
adding an authentication provider as a public extension point:

> 1. Create a namespace for your provider (e.g. `metabase.sso.providers.my-provider`)
> 2. Declare hierarchy: `(derive :provider/my-provider ::provider/provider)`
> 3. For SSO providers: `(derive :provider/my-provider ::provider/create-user-if-not-exists)`
> 4. Implement `authenticate` multimethod (required)

We copy no EE code. Every primitive we depend on is in the AGPL `src/` tree.

## Key finding: OIDC is already mostly OSS

Upstream v0.62.3.3 ships a complete, working generic OIDC implementation in the AGPL
tree. What `sso-oidc` actually gates is thin: settings storage, routes, and the gate
itself.

| Need | Source | License | Precedent |
|---|---|---|---|
| Discovery + caching | `metabase.sso.oidc.discovery` | AGPL | — |
| JWKS + ID token validation | `metabase.sso.oidc.tokens` | AGPL | — |
| Encrypted state cookie, CSRF, browser binding | `metabase.sso.oidc.state` | AGPL | — |
| SSRF-guarded HTTP | `metabase.sso.oidc.http` | AGPL | — |
| Config schemas | `metabase.sso.oidc.schema` | AGPL | — |
| Config validation ("test connection") | `metabase.sso.oidc.check/check-oidc-configuration` | AGPL | — |
| Full auth-code flow | `metabase.sso.providers.oidc` (`:provider/oidc`) | AGPL | — |
| Provider registration | `metabase.auth-identity` | AGPL | documented extension point |
| OSS SSO route mounting | `metabase.server.auth-wrapper` | AGPL | `slack-connect` |
| Admin settings API | — | — | `metabase.sso.api.ldap` |
| Login button registration | `PLUGIN_AUTH_PROVIDERS` | AGPL | `builtin/auth/google.ts` |

The cryptographically hard and security-critical parts — the parts most dangerous to
reimplement — are already ours under AGPL. We are writing configuration, routes, and
provisioning glue.

`metabase.server.auth-wrapper` is the decisive precedent: it mounts
`/auth/sso/slack-connect` as an always-available OSS route and falls back to
"ee-build-required" stubs for the rest. An OSS-tree SSO provider mounted under
`/auth/sso/*` is an established pattern with a working example in the codebase.

## Architecture

```
Browser
  │  GET /auth/sso/oidc?redirect=/
  ▼
metabase.server.auth-wrapper                    [MODIFIED: mount "/oidc" beside "/slack-connect"]
  │
  ▼
metabase.sso.integrations.wc-oidc               [NEW]
  │  build config from settings → assoc :oidc-config into request
  │  auth-identity/login! :provider/wc-oidc
  ▼
metabase.sso.providers.wc-oidc                  [NEW — essentially just a derive]
  │  (derive :provider/wc-oidc :provider/oidc)
  ▼
metabase.sso.providers.oidc  (:provider/oidc)   [UNCHANGED AGPL]
  │  login! :around  → validate state cookie
  │  authenticate    → discovery, auth URL, token exchange,
  │                    ID token validation, claim extraction
  ▼
metabase.auth-identity.provider
  │  ::create-user-if-not-exists → auto-provision
  ▼
metabase.auth-identity.session/create-session-with-auth-tracking!
```

The base `:provider/oidc` reads its configuration via
`oidc.common/extract-oidc-config`, which checks `(:oidc-config request)` first. So our
provider inherits the entire flow — including the `login! :around` state validation —
by injecting config into the request. `metabase.sso.providers.wc-oidc` is close to a
one-liner.

### Naming

Provider key `:provider/wc-oidc`, distinct from EE's `:provider/custom-oidc` to avoid
any collision.

The enablement setting is named **`oidc-enabled`** deliberately.
`sso.settings/sso-source-enabled?` already contains
`:oidc (setting/get :oidc-enabled)`, so that function requires **no patch**. The base
provider sets `:sso_source :oidc` on extracted user data, which lines up.

Note this means our `defsetting oidc-enabled` shares a name with EE's
(`enterprise/.../sso/settings.clj:394`). Harmless in an OSS build, where EE is not on
the classpath. If someone ever builds `MB_EDITION=ee`, the duplicate registration is
expected to fail loudly at boot — which acts as a free guard against silently
reintroducing an EE build. *(Assumption: duplicate `defsetting` throws rather than
overwrites. Verify during implementation; if it silently overwrites, rename to
`wc-oidc-enabled` and patch `sso-source-enabled?`.)*

## Phase 1 — Strip the bypass

Revert to upstream `v0.62.3.3`:

| File | Change |
|---|---|
| `src/metabase/premium_features/token_check.clj` | Revert. Restores real token check, metering, `*token-features*`. |
| `src/metabase/premium_features/settings.clj` | Revert. Removes forced `development-mode? => false`. |
| `enterprise/backend/src/metabase_enterprise/api/routes/common.clj` | Revert. Restores `+require-premium-feature` gating. |
| `enterprise/LICENSE.txt` | Restore deleted commercial license text. |
| `src/metabase/analytics/settings.clj` | Revert to upstream. |
| `src/metabase/version/settings.clj` | Revert to upstream. |

Telemetry behavior is preserved via environment variables rather than source patches:

```yaml
MB_EDITION: oss                  # was: ee
MB_ANON_TRACKING_ENABLED: "false"
MB_SNOWPLOW_AVAILABLE: "false"
MB_CHECK_FOR_UPDATES: "false"
```

These are supported settings; hardcoding the getters was never necessary. Fork delta on
those two files drops to **zero**.

With no token configured, upstream `send-metering-events!` is already a no-op (it
requires `premium-embedding-token` to be set) and `*token-features*` returns `#{}`. So
reverting introduces no phone-home — the bypass was guarding against something that was
not there.

Retained from the current fork (unrelated to the bypass): `Dockerfile` (uv + DigiCert
cert fix), `deps.edn` (opensaml repository URL fix).

## Phase 2 — The OIDC connector

### New files

- **`src/metabase/sso/providers/wc_oidc.clj`**
  `(derive :provider/wc-oidc :provider/oidc)` and
  `(derive :provider/wc-oidc :metabase.auth-identity.provider/create-user-if-not-exists)`.
  Plus `build-config` reading settings into the shape `:provider/oidc` expects
  (`:client-id`, `:client-secret`, `:issuer-uri`, `:scopes`, `:redirect-uri`).

- **`src/metabase/sso/integrations/wc_oidc.clj`**
  Two handlers:
  - `sso-initiate` — check enabled, build redirect URI
    (`{site-url}/auth/sso/oidc/callback`), call `login!`, wrap response with
    `sso/wrap-oidc-redirect` to set the encrypted state cookie.
  - `sso-callback` — call `login!` with code/state, create session on success, clear
    state cookie, redirect to stored `:redirect`.

- **`src/metabase/sso/api/oidc.clj`**
  `PUT /api/oidc/settings`, modeled directly on `metabase.sso.api.ldap`:
  superuser check → `check-oidc-configuration` → persist only if `:ok` →
  return `{:discovery ..., :credentials ...}` on failure so the form can show why.
  Client secret uses the `update-password-if-needed` obfuscation pattern from
  `ldap.clj` so the stored secret is not clobbered by a round-tripped mask.

- **`frontend/src/metabase/plugins/builtin/auth/oidc.ts`**
  ~20 lines, mirroring `google.ts`: push a provider with an `OidcButton`, gated on
  `oidc-enabled`. Also `PLUGIN_IS_PASSWORD_USER.push((user) => user.sso_source !== "oidc")`.

- **`frontend/src/metabase/auth/components/OidcButton/`** — the login button.

- **Admin settings form** — issuer URI, client ID, client secret, scopes, enabled
  toggle, and a "test connection" action backed by `check-oidc-configuration`.

### Modified files

| File | Change |
|---|---|
| `src/metabase/sso/settings.clj` | Add `oidc-enabled`, `oidc-issuer-uri`, `oidc-client-id`, `oidc-client-secret` (`:sensitive?`), `oidc-scopes`. Relax `ee-sso-configured?` so OIDC counts without `config/ee-available?`. |
| `src/metabase/sso/core.clj` | Export the new public vars. |
| `src/metabase/sso/init.clj` | Require `metabase.sso.providers.wc-oidc`. |
| `src/metabase/server/auth_wrapper.clj` | Mount `"/oidc"` beside `"/slack-connect"` in the always-available OSS route map. |
| `src/metabase/sso/api.clj` | Add `oidc-routes` via `(api.macros/ns-handler 'metabase.sso.api.oidc)`, mirroring `ldap-routes`. |
| `src/metabase/api_routes/routes.clj` | Mount `"/oidc" (+auth metabase.sso.api/oidc-routes)` beside the existing `"/ldap"` entry (line ~193). |
| `frontend/.../admin/settingsRoutes.tsx` | Route for the OIDC form. |
| `docker-compose.yml` | `MB_EDITION: oss`, telemetry env vars. |

### Settings

| Setting | Env var | Notes |
|---|---|---|
| `oidc-enabled` | `MB_OIDC_ENABLED` | Drives login button + `sso-source-enabled?` |
| `oidc-issuer-uri` | `MB_OIDC_ISSUER_URI` | `https://sso.waterloocap.com/application/o/<slug>/` |
| `oidc-client-id` | `MB_OIDC_CLIENT_ID` | |
| `oidc-client-secret` | `MB_OIDC_CLIENT_SECRET` | `:sensitive? true` |
| `oidc-scopes` | `MB_OIDC_SCOPES` | default `["openid" "email" "profile"]` |

`defsetting` gives env-var backing for free, so these are settable either from the admin
form or from `docker-compose.yml`.

### Authentik specifics

Authentik is standards-compliant with working discovery, so
`discover-oidc-configuration` should need no special-casing.

- Discovery: `https://sso.waterloocap.com/application/o/<slug>/.well-known/openid-configuration`
- Claims: `sub`, `email`, `given_name`, `family_name` — matching the base provider's
  defaults exactly (`email` / `given_name` / `family_name`), so no attribute mapping is needed.
- Redirect URI to register in Authentik: `https://<metabase-host>/auth/sso/oidc/callback`

`oidc-allowed-networks` (AGPL SSRF guard) must permit `sso.waterloocap.com`. It is
public DNS, so the default should work; if the host resolves to a private address,
set `:allow-private`.

## Provisioning model

Authentik is the gatekeeper. Its application policy decides who may reach Metabase at
all; Metabase auto-provisions anyone who arrives with a valid token, via the existing
`::create-user-if-not-exists` mixin the base `:provider/oidc` already derives from.

Access control lives in Authentik only — no duplicated allow-list in Metabase to drift.
Auto-provisioned users are **non-admin**; admin is granted manually to the three admins
in the OIDC cohort.

Note the 42 password users are untouched by this work. They keep logging in exactly as
they do today; only the 4 OIDC users change.

## Cutover plan

Sequencing is not negotiable, because getting it wrong locks us out of our own instance.

1. **Set and verify break-glass passwords for all four OIDC accounts** (`arose`,
   `abuchanan`, `ndyer`, `tchatmas`) *while the current EE build is still running*.
   Accounts with `sso_source = "oidc"` may have no usable password. Verify by actually
   logging in with each — not merely by setting them.
   `lmoulton@waterloocap.com` is an active password-authenticated admin and serves as an
   independent break-glass if all four are somehow locked out.
2. Build Phase 1 + Phase 2 together on a branch.
3. Test locally: `docker-compose` against real `sso.waterloocap.com`.
4. Clear the four stale OIDC identities — delete `auth_identity` rows bound to EE's
   `:provider/custom-oidc` and null out `sso_source` on those users, so the new connector
   re-links cleanly on first login.
5. Deploy **one** container that is already OSS *and* has working OIDC. Prod never sits
   in a state with no SSO.
6. Verify OIDC login for all four; confirm the three admins retained superuser.

Password login is the permanent fallback: `disable-password-login` is EE-gated, so an
OSS build cannot turn it off. That is our standing break-glass.

Expect the UI to revert to stock Metabase branding at step 5. This is visible to the 20
external client users. See "Deferred: branding".

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **Lockout.** Cutting `ee → oss` kills EE OIDC instantly; the 4 accounts with `sso_source=oidc` — 3 of them admins — cannot log in without a password. | High | Step 1 of cutover: set and *verify* passwords for all four before touching the build. `lmoulton` is an independent password admin. |
| **Branding loss visible to 20 external client users.** `whitelabel` is genuinely in use; the strip reverts the UI to stock Metabase. | Medium | Accepted for now — cosmetic, not security. Follow-up spec; see "Deferred: branding". Flag to stakeholders before cutover. |
| Losing an EE feature that is actually in use beyond SSO. | ~~Medium~~ **Resolved** | Audited live instance 2026-07-17. Only `sso-oidc` and `whitelabel` are in use; the other 56 are dead weight. |
| External-user data isolation depends on EE. | ~~High~~ **Resolved** | Audited. All 80 group/db combos are `view-data=unrestricted`; isolation is by OSS collection permissions. The strip cannot widen access. |
| `ee-sso-configured?` patch missed → login button never renders. | Medium | Covered by test; also caught immediately in local docker-compose. |
| Duplicate `defsetting oidc-enabled` if someone builds EE. | Low | Expected to fail loudly at boot (verify). If it silently overwrites, rename to `wc-oidc-enabled`. |
| Authentik issuer mismatch (trailing slash) breaks ID token `iss` validation. | Low | Common Authentik gotcha. Assert exact issuer string from the discovery document. |
| `oidc-allowed-networks` blocks the IdP. | Low | Verify during local test. |

## Testing

- **Unit** — `build-config` shape from settings; `oidc-enabled=false` rejects initiate;
  callback with bad state rejected; unknown email auto-provisions as non-admin.
- **Integration** — full auth-code round trip against Authentik from local
  docker-compose. This is the test that matters; the unit tests mostly guard wiring.
- **Regression** — password login still works with `oidc-enabled=true` (break-glass
  must not regress).
- **Negative** — confirm the OSS build genuinely has no EE: assert
  `(premium-features/has-feature? :sandboxes)` is false and that `/api/ee/*` 404s.
  This is the test that proves the bypass is actually gone.

## Open questions

None blocking.
