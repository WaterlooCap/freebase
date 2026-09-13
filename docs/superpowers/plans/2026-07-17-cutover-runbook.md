# Cutover Runbook — Freebase OSS Conversion (OIDC + Branding)

**Branch:** `feat/oss-conversion-custom-oidc`
**What it does:** Converts the fork from an enterprise license bypass to a genuine OSS (AGPL) build, reimplementing OIDC SSO and Waterloo branding as original code. See `docs/superpowers/specs/2026-07-17-oss-conversion-custom-oidc-design.md`.

**Status going in:** all 14 implementation tasks reviewed clean; full backend suite 46 tests / 3976 assertions pass; frontend type-check clean; final whole-branch review = "ready with follow-ups" (no blocking issues).

> **Step 1 is the only step that cannot be undone from a laptop. Do it first, verify it, then proceed.**

## Pre-flight (do this while prod is STILL on the current EE build)

1. **Set and VERIFY break-glass passwords for all four OIDC accounts.** They authenticate via OIDC today and may have no usable password; the moment the OSS build starts, EE OIDC is gone and only password login + the new connector remain.
   - `arose@waterloocap.com` (admin)
   - `abuchanan@waterloocap.com` (admin)
   - `ndyer@waterloocap.com` (admin)
   - `tchatmas@waterloocap.com` (non-admin)

   **Verify by actually logging in** with each in a private window. Setting a password without testing it is not verification.

   `lmoulton@waterloocap.com` is an active password-authenticated admin and is the independent break-glass if all four fail. Password login is the permanent fallback — `disable-password-login` is EE-gated, so an OSS build cannot turn it off.

2. **Register the OIDC application in Authentik** (`sso.waterloocap.com`):
   - Redirect URI (exact): `https://analytics.waterloocap.com/auth/sso/oidc/callback`
     — this string is pinned by a test (`redirect-uri-is-stable-test`); if you change one, change both.
   - Note the **client ID** and **client secret**.
   - Confirm the **issuer URI including the trailing slash**: `https://sso.waterloocap.com/application/o/<app-slug>/`. A trailing-slash mismatch breaks ID-token `iss` validation — the most common Authentik gotcha.
   - Authentik emits `sub`, `email`, `given_name`, `family_name` — which match the connector's defaults exactly, so no attribute mapping is needed.

3. **Confirm `MB_ENCRYPTION_SECRET_KEY` is set** in the deploy environment (it already is on the current instance). The OIDC state cookie (CSRF protection) is encrypted with it; without it the SSO flow fails closed.

4. **Back up the app DB.**

5. **Tell stakeholders** who own the external client relationships (lhfinancial.net ×13, amgwealthadvisors.com, elementconsultants.com, ironclad-wealth.com, wcfos.com — 20 active external users) that a deploy is happening. Branding should be identical after cutover — but they should hear it from you first.

## Cutover

6. **Set the OIDC env vars** in the deploy environment (docker-compose reads them):
   ```
   MB_EDITION=oss                    # already set in docker-compose.yml
   MB_FREE_OIDC_ENABLED=true
   MB_FREE_OIDC_ISSUER_URI=https://sso.waterloocap.com/application/o/<app-slug>/
   MB_FREE_OIDC_CLIENT_ID=<from Authentik>
   MB_FREE_OIDC_CLIENT_SECRET=<from Authentik>
   ```
   (Alternatively configure OIDC via the admin API `PUT /api/oidc/settings` after boot — but env vars are simplest and keep the secret out of the app DB.)

7. **Deploy the new container** (OSS + OIDC + branding, all at once). Prod never sits in a state with no SSO or with stock branding.

8. **The branding migration runs automatically on boot** (`branding.init/init!` in `metabase.core.core/init!*`), copying the existing `application-*` values (name "Waterloo", logo, favicon, colors) into the `wc-brand-*` settings. Idempotent — a no-op on every boot after the first.

9. **Clear the four stale OIDC identities** so the new connector re-links cleanly on first login:
   ```sql
   -- Inspect first.
   SELECT u.email, u.sso_source, ai.provider
     FROM core_user u
     LEFT JOIN auth_identity ai ON ai.user_id = u.id
    WHERE u.sso_source = 'oidc';

   -- Then clear (EE's connector used provider 'custom-oidc').
   DELETE FROM auth_identity WHERE provider = 'custom-oidc';
   UPDATE core_user SET sso_source = NULL WHERE sso_source = 'oidc';
   ```
   On next OIDC login the connector re-provisions the identity (matched by email) and stamps `sso_source = 'oidc'` again.

## Verify

10. Log in via OIDC as each of the four accounts. Confirm the three admins still have superuser (auto-provisioned users are non-admin; if a promotion was lost, re-grant in Admin → People).
11. Confirm branding renders: "Waterloo" name in the title/nav, the logo, the favicon, and the `#3E90C5` brand color across the app.
12. Log in as a password user (e.g. an lhfinancial.net account) and confirm their collections look exactly as before. **Collection permissions are OSS and untouched by this change, so this should be a no-op — verify it anyway.**
13. Confirm `/api/ee/*` returns 404 and premium features are gated (`has-feature? :sandboxes` is false) — proving the bypass is genuinely gone.

## Known limitations (accepted, not blocking)

- **Server-rendered charts and embedding are not branded.** Dashboard-subscription / alert **email chart images** (rendered via `static-viz`) and any **embedding** flows still use stock Metabase colors, because those code paths read Metabase's gated `application-colors`, which this project deliberately does not touch. The in-app UI (login, dashboards, questions, admin) is fully branded. If branded email charts matter, that's a follow-up (extend the `wc-brand-colors` read into `static-viz`).
- **Whitelabel "concealment" features** (hiding Metabase links, custom loading messages, help-link overrides beyond the basic setting) are EE-only and not reimplemented. The core brand identity (name, logo, favicon, colors) is covered.

## Rollback

Redeploy the previous image and restore the app DB backup. The `auth_identity` deletion in step 9 is the only destructive change; the DB backup covers it. Because password login always works on the OSS build, a partial failure (e.g. OIDC misconfig) does not lock admins out — fix the config and retry without rolling back.

## Follow-ups (none blocking; tracked from the final review)

- Branding for server-rendered charts / embedding (above).
- `sso-callback` truthy-`:redirect` log tidy-up (non-exploitable; logs "success for user nil" on a bare callback GET).
- Anti-bypass count-guard threshold `>=10` → `>=19`; restore `application-name` in that test's fixture.
- Dead `provider-name` var in `free_oidc.clj`; `oidc.ts` could use a settings wrapper for consistency.
