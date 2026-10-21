# AI integration

Nouri uses AI only to **identify** food: dishes, components, portion ranges, ingredients and label text. Calories and nutrients are always calculated on the device from reference data (see [NUTRITION_ENGINE.md](NUTRITION_ENGINE.md)). The prompts state this explicitly ([ai/Prompts.kt](shared/src/commonMain/kotlin/app/nouri/core/ai/Prompts.kt)). Model output is schema-validated before use ([ai/AiValidation.kt](shared/src/commonMain/kotlin/app/nouri/core/ai/AiValidation.kt)).

> **Verification status:** Nothing here has been exercised against live OpenAI services. The development sandbox cannot reach `auth.openai.com`, `api.openai.com` or `developers.openai.com`. Endpoint names, parameters and error codes were taken from OpenAI's official documentation (found via web search) and are covered by unit tests against mocked HTTP. They must be verified on a real device with a real account before release (see [BLOCKERS.md](BLOCKERS.md)).

## Provider interface

[`AiProvider`](shared/src/commonMain/kotlin/app/nouri/core/ai/AiProvider.kt): `status()`, `analyzeMeal`, `identifyIngredients`, `generateRecipes`, `parseMealText`, `readLabel`. Authentication is handled outside the interface.

| `ProviderKind` | Billing (`BillingMode`) | Notes |
|---|---|---|
| `CHATGPT_PLAN` | `PLAN_USAGE`: counts against the user's ChatGPT plan limits | Sign in with ChatGPT. Plan usage needs an explicitly granted scope. |
| `OPENAI_API_KEY` | `API_BILLED`: billed to the user's own API account | Disabled until the user enters a key. |
| `MOCK` | `NONE` | Default. Deterministic demo fixtures. Results are marked `simulated` and the UI labels them. Photos are not analysed. |
| `ON_DEVICE` | — | Reserved for a future local model. |

Errors are typed (`AiError`: `NotConfigured`, `Unauthorized`, `UsageLimitReached`, `RateLimited`, `CapabilityUnavailable`, `InvalidResponse`, `Network`, `Timeout`, `Server`, `Refused`). Each maps to a recoverable message, and none of them loses user data.

## Safeguards in `AiService`

- **Photo consent.** Image requests to any non-demo provider fail with `NotConfigured("Photo analysis needs your permission first")` until the user consents (`AiSettings.photoConsent`). The camera and label flows show a disclosure dialog before the first upload; agreeing stores the consent, which can be revoked in Settings › Privacy.
- **No silent fallback.** A failed request can be retried with the API-key provider only if all of these hold:
  - the user explicitly enabled `allowApiKeyFallback`;
  - the primary provider is `CHATGPT_PLAN`;
  - an API key is connected;
  - the error is **not** `UsageLimitReached` or `Refused`.

  Reaching a plan limit pauses AI features. It never switches billing.
- **No silent demo results.** If a real provider fails, the app never substitutes mock output.
- **Signing in is not permission to spend.** Signing in identifies the account. Plan usage requires the `chatgpt.tokens.use.direct` scope to be granted (`TokenResponse.grantsPlanUsage`). Without it the result is `SignedInWithoutPlanUsage` and no plan-usage requests are made.

## Sign in with ChatGPT (SIWC)

Implementation: [auth/SiwcProtocol.kt](shared/src/commonMain/kotlin/app/nouri/core/auth/SiwcProtocol.kt), [auth/SiwcAuthManager.kt](shared/src/commonMain/kotlin/app/nouri/core/auth/SiwcAuthManager.kt), [app/…/ai/ChatGptSignIn.kt](app/src/main/kotlin/app/nouri/ai/ChatGptSignIn.kt).

Flow (the open-source/local app variant):

1. **Discovery.** `/.well-known/openid-configuration` on `https://auth.openai.com` supplies the authorize, token, revocation and JWKS endpoints. The constants in `SiwcConfig` are fallbacks only.
2. **Authorization request.**
   - `response_type=code` with a fresh `state`, `nonce` and PKCE (S256; 64-character verifier) on every attempt.
   - A stable per-install `ext_agent_host_id`: `urn:uuid:` followed by a lowercase version-4 UUID, generated once and stored with the other secrets. OpenAI rejects other formats with `invalid_authorize_request` (`param: ext_agent_host_id`) — the first real-device test hit exactly this with the earlier `nouri-<hex>` format. Stored values in the old format are replaced automatically.
   - `scope = openid profile email offline_access resource.invoke chatgpt.tokens.use.direct` and `resource=https://api.openai.com/v1`.
   - **First registration** uses `client_id=dynamic_agent_client` with `agent_name_hint=Nouri`. The server issues the real `client_id` in the callback. **Re-authorisation** uses the stored client id with `id_token_hint` and `login_hint`.
3. **Browser.**
   - The URL opens in the user's browser (a Custom Tab via `androidx.browser`, started from Settings › AI provider).
   - The redirect is a **loopback** address `http://127.0.0.1:<OS-assigned port>/auth/callback`. Only the port varies.
   - `AndroidLoopbackReceiver` accepts loopback connections, returns 404 for other paths, and serves a short page (worded differently when the user denied access) linking to `nouri://auth-complete`. That link is needed because Android 15+ doesn't bring the app back to the front automatically.
4. **Callback validation.** The path must match and `state` is compared in constant time. The issued `client_id` must be present, must not be the placeholder, and must match on re-auth.
5. **Code exchange** (PKCE verifier, `resource`). The ID token is decoded and validated: issuer, audience, nonce, expiry. `IdTokenSignatureVerifier` is a hook for JWKS signature checks; **it is not wired in the Android container yet**, so sessions record `idTokenSignatureVerified=false`.
6. **Refresh.**
   - Access tokens refresh a minute before expiry, or after a 401, under a mutex.
   - Refresh tokens **rotate**: the newest one is always kept.
   - `earliest_refresh_at` is honoured.
   - A 4xx on refresh clears the session, so the user reconnects.
7. **Sign-out.** The refresh token is revoked at the discovery `revocation_endpoint` (RFC 7009) with up to three attempts. Local tokens are then cleared. If revocation can't be confirmed, the user is told to disconnect Nouri in ChatGPT settings.

**Token storage.** Tokens live only on the device, as required by the SIWC terms. They are encrypted with an Android Keystore AES-256-GCM key (`KeystoreCipher`) in `noBackupFilesDir/secrets`. Backups are disabled app-wide. Tokens are never logged and are only used for this user's own requests. Nouri has no access to ChatGPT conversations or memories and does not request it.

### Eligibility

According to OpenAI's documentation and terms, open-source and local apps can self-serve this integration. Commercial or hosted apps need to join OpenAI's partner programme. The user must confirm which applies before release (see [BLOCKERS.md](BLOCKERS.md)).

Sources (see the official documentation):
- OpenAI developer docs for Sign in with ChatGPT: https://developers.openai.com
- Sign in with ChatGPT terms: https://openai.com/policies/sign-in-with-chatgpt-terms
- OpenAI Help Center article on Sign in with ChatGPT: https://help.openai.com

## Responses API usage

[ai/openai/ResponsesClient.kt](shared/src/commonMain/kotlin/app/nouri/core/ai/openai/ResponsesClient.kt) calls `POST /v1/responses`:

- `store: false` and `stream: true` on every request. The SIWC plan-usage docs require both, and the API-key provider uses the same settings for consistency.
- The system prompt goes in the top-level `instructions` field, not a system-role message.
- No `max_output_tokens`: plan-usage requests reject it.
- Images are sent as base64 data URLs of the processed JPEG only.
- Structured outputs use strict JSON schemas, with a fallback when the schema is rejected.
- **A request succeeds only on a `response.completed` event.** Partial streamed text is never used, because a usage limit can end a stream mid-output.
- Error mapping (`mapError`):
  - codes containing `usage_limit`, `subscription_sharing_usage_limit_exceeded` or `insufficient_quota` → `UsageLimitReached` (never triggers a fallback);
  - 401 → `Unauthorized`;
  - 403 → `Refused`;
  - 429 → `RateLimited`;
  - 5xx → `Server`.
- **Model choice.** The provider lists `GET /v1/models` for the account and picks from `OpenAiProvider.DEFAULT_MODEL_PREFERENCE`, falling back to a general `gpt-*` model. Plan-usage tokens receive a catalogue of `models[]` entries (only `visibility == "list"` entries are offered, and `slug` is the model id); API keys receive the standard `data[].id` list. `ResponsesClient.parseModelList` accepts both, and for plan usage the first catalogue entry is used when no preferred name matches. **Those model names are placeholders**, resolved against what the account actually offers at runtime. The user can override the model in settings.

## Personalisation

If `PrivacySettings.personaliseAiPrompts` is on, prompts include up to six favourite cuisines and up to fifteen dish names the user has logged. These are only used to break ties between similar-looking dishes. Corrections are learned on the device (`CorrectionMemory`); no model is fine-tuned.
