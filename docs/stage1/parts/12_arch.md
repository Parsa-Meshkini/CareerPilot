
---

# 12. Integration, Scalability and Deployment Architecture

## 12.1 Browser Extension Architecture (Chrome Manifest V3)

```
extension/
├── manifest.json          permissions: "activeTab", "storage", "scripting"; host_permissions: CareerPilot API origin
│                          (+ the Experience York origin as an optional permission requested at runtime)
├── content/               ContentScript + SiteExtractor implementations (YorkExtractor; LinkedInExtractor disabled)
├── popup/                 ExtensionPopup (detected count, Import, "paste instead")
├── background/            ExtensionServiceWorker (API calls, token, batch chunking, status polling)
└── api/                   typed client for /api/import-batches
```

- **Lightweight by design:** DOM → plain records, nothing more. There is no AI in the extension and no parsing business rules; field mapping lives in the backend adapter so it can be fixed without republishing the extension.
- **User-initiated only:** the extension uses `activeTab` and runs only on click, so there are no background tabs, timers or automatic pagination.
- **Versioned extractors:** each payload carries `extractorVersion`. If a portal layout changes, the backend logs which version failed and the popup degrades to paste import.
- **Authentication:** the student signs in to CareerPilot once. The service worker stores a revocable, scoped API token (`import:write`) in `chrome.storage.session`. York or LinkedIn credentials are never seen or stored.

## 12.2 Experience York Integration Architecture

`YorkExtractor` (extension) → `ImportPayload` → `JobImportAPI` → `JobSourceRegistry` → `YorkBrowserImportAdapter` → `RawJobRecord` → `JobNormalizer` / `DuplicateDetector` → `JobPosting`.

| Concern | Decision |
|---|---|
| Official API? | Not assumed. There is no dependency on an undocumented endpoint. |
| Access | Only pages the authenticated student is already viewing; no automation of login, MFA or CAPTCHA; no credential storage. |
| List vs. detail | List pages yield SUMMARY postings; detail pages yield FULL. `mergeFrom()` upgrades SUMMARY records to FULL. |
| Testability | Saved, anonymized HTML fixtures of list and detail pages (`extension/tests/fixtures/`) + `MockJobSourceAdapter` for backend tests and demos. |
| Change resilience | Extractor versioning; adapter field map in configuration; graceful fallback to paste import. |
| Policy | Before any public release, confirm acceptable use with York's Co-op & Career Centre. Until then the extension is a personal productivity tool used on the student's own session. |

## 12.3 LinkedIn Integration Architecture

- **Core (Stage 2):** `ManualJobImportAdapter`. The student copies text from a posting they are viewing and pastes it; the LLM structures it. This involves no scraping and no automation.
- **Designed but disabled:** `LinkedInExtractor` / `LinkedInBrowserImportAdapter` (`enabled = false`). They exist so the architecture can accept a sanctioned source later without core changes.
- **Future:** an adapter for an official, authorized LinkedIn partner API, if the team ever obtains access, as one new `JobSourceAdapter` class.

## 12.4 Gmail Integration Architecture

`SettingsPage` → `EmailAPI` → `EmailSyncService` → `EmailProviderRegistry` → `GmailAdapter` (OAuth 2.0 authorization-code + PKCE; scope `gmail.readonly`) → `CredentialVault` (encrypted refresh token) → `EmailNormalizer` → `EmailEventDetector` (`HybridClassifier`) → `ApplicationMatcher` → `DomainEventBus` → observers.

- **Least data:** narrow query (last 2 days; job keywords or tracked company domains). Only a 4 KB excerpt is stored, NOT_RELEVANT excerpts are deleted, and attachments are never downloaded.
- **Course deployment:** the Google Cloud project stays in "Testing" publishing status with the team and TA listed as test users (no restricted-scope verification needed; about 7-day token lifetime handled by the reauth flow).
- **Production path (post-course):** Google OAuth brand and data-access verification plus the annual CASA security assessment required for restricted scopes. Alternatively, keep `EmlFileAdapter` / forwarding-address ingestion as the default.
- **Uncertainty is explicit:** the 0.85 confidence thresholds, the review queue and undo mean misclassification is recoverable and never silent.

## 12.5 Database / Domain Model

PostgreSQL, one schema, with every user-owned table carrying `user_id` (FK, indexed). The main tables map 1:1 to the CD-2 entities:

| Table | Key columns / constraints |
|---|---|
| `users_user` | id (UUID), email (unique) |
| `candidates_profile`, `candidates_fact` (type discriminator + typed columns / JSON), `candidates_goal` | fact `verified`, `source`; one profile and one goal per user |
| `jobs_importbatch`, `jobs_jobposting`, `jobs_jobanalysis`, `jobs_jobmatch` | UNIQUE(user_id, source_type, external_id) where external_id is not null; INDEX(user_id, fingerprint); INDEX(user_id, deadline) |
| `applications_application`, `applications_statuschange`, `applications_interview` | UNIQUE(user_id, job_id); `version` for optimistic locking |
| `documents_generateddocument`, `documents_applicationpackage` | `storage_key` (object storage), `version`, `verification_status` |
| `integrations_emailaccount`, `integrations_emailmessage`, `integrations_detectedemailevent` | UNIQUE(account_id, provider_message_id); encrypted token column |
| `agent_agentrun`, `agent_actionrecord`, `agent_memoryentry` | INDEX(user_id, kind, created_at) |
| `analytics_strategyadjustment`, `notifications_notification` | status, created_at |

Migrations are managed by Django. JSON columns are used only for genuinely variable data (`breakdown`, `extractedData`, `arguments`).

## 12.6 Scalability Plan

| Pressure | Mechanism |
|---|---|
| Many concurrent users | Stateless Django containers (N replicas) behind a reverse proxy; database connection pooling |
| Large batches (hundreds of postings) | Chunked payloads (≤ 200 per request); per-record work in Celery; idempotent upserts; progress counters on `ImportBatch` |
| Slow LLM calls | Always asynchronous (`ai` queue), never inside an HTTP request except single-posting paste (with timeout); workers scale independently per queue |
| LLM cost / rate limits | `UsageTracker` per-user budgets; Redis token-bucket rate limiter per provider; triage restricts deep analysis to top-K; analysis caching per posting; cheaper model for classification and extraction |
| Email volume | Incremental sync (`lastSyncAt`, message-id dedup); rules before LLM; per-account backoff |
| Documents | Rendering in the `docs` queue; files in object storage; signed URLs, so the web tier never streams large files |
| Growth beyond one database | Read replica for analytics; partition large tables by `user_id` hash if ever needed. Module boundaries allow extracting, for example, the `integrations` app into a separate service **only if** measurements justify it. |

**Why not microservices:** a small team, one domain and shared transactions (import → posting → match) make a modular monolith cheaper to build, test and deploy, while Celery queues already give independent scaling of the expensive parts.

## 12.7 Deployment Architecture

See **DD-1** (Section 5.4). Everything runs as Docker containers defined in `docker-compose.yml` (development) and `deployment/` (production overrides): `web` (Django/Gunicorn), `worker` (Celery, scalable), `beat` (Celery beat), `postgres`, `redis`, `minio` (development object storage), and `frontend` (static build served by the reverse proxy). The target is any Docker-capable host (a university VM, a PaaS such as Render or Fly.io, or a cloud VM); no provider-specific services are required, so the choice can be made in Stage 3 on cost and availability. **CI** (GitHub Actions): lint, unit tests with `MockLLMProvider` and mock adapters, the UML consistency check, and a Docker image build. **CD** (Stage 3): tag → build and push images → deploy with the compose file and environment secrets.

---

# 13. Security and Privacy

| Area | Design decision |
|---|---|
| Authentication | Django auth with email and password or university SSO (future). Session cookies (HttpOnly, Secure, SameSite=Lax) for the SPA; scoped, revocable personal API tokens for the CLI and extension. |
| Authorization and data isolation | Every repository method takes `userId` and filters by it. Controllers never accept a user id from the client. Object ids are UUIDs. Cross-user access returns 404. Tests assert isolation for every endpoint. |
| OAuth | Authorization code + PKCE, `state` for CSRF; minimal scope `gmail.readonly`; tokens encrypted at rest (`CredentialVault`, Fernet key from the environment, rotation supported); revoke and delete on disconnect or account deletion. |
| Secrets | Only in environment variables or the deployment secret store. `.env.example` is committed; `.env`, keys and tokens are git-ignored. Secret scanning (GitHub push protection + gitleaks in CI). |
| Third-party credentials | Never stored (York, LinkedIn, Gmail password). The extension never reads password fields. |
| Input validation | DRF serializers with JSON schemas for import payloads (size limits: 200 records, 20k characters of pasted text); file uploads limited to PDF/DOCX ≤ 5 MB with checks on magic bytes, not the extension; HTML stripped on normalization; output escaped in React. |
| Secure file handling | Private bucket; random object keys; 5-minute signed URLs; document text never logged. |
| LLM-specific | Prompt-injection resistance: posting and email text is passed as *data* in delimited fields, the model's output must match a schema, and the model cannot call tools directly (the agent's catalog contains no destructive or external-sending tools). Data minimization in prompts. Provider data-retention settings documented; no training on user data where the provider allows opting out. |
| Audit logging | `StatusChange` (who/what/why for every state change), `AgentActionRecord` (every agent action and its arguments), security events (login, token creation, OAuth connect/disconnect, data export/deletion). |
| Privacy rights | Export all my data (JSON + files ZIP) and delete my account (hard delete of rows and objects, token revocation) from Settings. Email excerpts and bodies have a retention limit (90 days). |
| Transport | TLS everywhere (reverse proxy), HSTS, strict CORS allowing the SPA origin and the extension id. |

For Stage 1 this level is a *design commitment*. Stage 2 implements authentication, isolation, secrets handling, validation and encryption. Stage 3 adds security testing (Section 16).

