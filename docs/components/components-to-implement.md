# Components To Implement (not yet in master)

Cross-checked open `workflow-component` issues + the `#2282` AI Agent Tools checklist
against existing component directories in `server/libs/modules/components/`.
Only components with **no directory in master** are listed here.

Sections A–C come from ByteChef's own issues. **Section D** (added later) comes from
diffing three mature automation platforms — n8n (307 nodes), Activepieces (722 pieces),
and Sim (~230 tools) — against master **and** sections A–C, surfacing integrations those
platforms ship that ByteChef neither has nor tracks yet.

Verified against umbrella dirs: `aws/` (only `aws-s3`), `google/` (has calendar, drive,
sheets, docs, mail, workspace-admin, bigquery, … — **no** analytics), `microsoft/`
(has dynamics-crm, excel, outlook-365, teams, sharepoint, one-drive, to-do — **no** SQL).

---

## A. New SaaS / API components (have a dedicated open issue)

**Status: DONE — 54 / 57 implemented** on the `components` branch (✅ = component
directory exists). Grammarly, Borneo and Booking are deferred: none of them offer a
public, self-serve server API to integrate against.

| # | Issue | Component | Status |
|---|-------|-----------|--------|
| 1 | #2192 | Facebook Pages | ✅ |
| 2 | #2290 | Google Analytics | ✅ |
| 3 | #2294 | Microsoft SQL | ✅ |
| 4 | #2296 | Microsoft Clarity | ✅ |
| 5 | #2297 | Line | ✅ |
| 6 | #2351 | Action Network | ✅ |
| 7 | #2784 | AgencyZoom | ✅ |
| 8 | #3249 | CloudConvert | ✅ |
| 9 | #3626 | Breakcold | ✅ |
| 10 | #3667 | NocoBase (distinct from existing `nocodb`) | ✅ |
| 11 | #3669 | Airtop | ✅ |
| 12 | #3670 | Apaleo | ✅ |
| 13 | #3671 | Blackboard | ✅ |
| 14 | #3672 | Bubble | ✅ |
| 15 | #3673 | DeepL | ✅ |
| 16 | #3674 | Exa | ✅ |
| 17 | #3676 | Fireflies.ai | ✅ |
| 18 | #3677 | Ghost | ✅ |
| 19 | #3678 | Omnisend | ✅ |
| 20 | #3679 | Grammarly | deferred — no public server API (client-side SDK only) |
| 21 | #3713 | ServiceNow | ✅ |
| 22 | #3714 | SerpApi | ✅ |
| 23 | #3715 | Recall.ai | ✅ |
| 24 | #3716 | Odoo | ✅ |
| 25 | #3717 | Linkup | ✅ |
| 26 | #3718 | OpenWeather | ✅ |
| 27 | #3719 | Borneo | deferred — no public/self-serve API |
| 28 | #3720 | You.com | ✅ |
| 29 | #3722 | Typefully | ✅ |
| 30 | #3723 | Shortcut | ✅ |
| 31 | #3724 | Sentry | ✅ |
| 32 | #3725 | Semantic Scholar | ✅ |
| 33 | #3726 | Rocketlane | ✅ |
| 34 | #3727 | Retell AI | ✅ |
| 35 | #3728 | PeopleDataLabs | ✅ |
| 36 | #3729 | OnePageCRM | ✅ |
| 37 | #3730 | Neon | ✅ |
| 38 | #3732 | Mem | ✅ |
| 39 | #3733 | Mailcheck | ✅ |
| 40 | #3734 | Listen Notes | ✅ |
| 41 | #3735 | LMNT | ✅ |
| 42 | #3736 | Jungle Scout | ✅ |
| 43 | #3737 | Foursquare | ✅ |
| 44 | #3738 | D2L Brightspace | ✅ |
| 45 | #3739 | Coinbase | ✅ |
| 46 | #3741 | Canvas | ✅ |
| 47 | #3743 | Browserbase | ✅ |
| 48 | #3744 | APITemplate.io | ✅ |
| 49 | #3746 | AWS SES (new sub-service under `aws/`) | ✅ |
| 50 | #3747 | AWS Lambda (new sub-service under `aws/`) | ✅ |
| 51 | #3748 | AWS Textract (new sub-service under `aws/`) | ✅ |
| 52 | #3749 | AWS Transcribe (new sub-service under `aws/`) | ✅ |
| 53 | #5021 | Booking | deferred — Booking.com APIs are partner-restricted |
| 54 | #763  | SugarCRM | ✅ |
| 55 | #762  | Teamleader | ✅ |
| 56 | #759  | Bigin | ✅ |
| 57 | #1948 | Harvest | ✅ |

## B. New helper / utility / tool components (have a dedicated open issue)

**Status: DONE — 4 / 5 implemented.** Code Interpreter is deferred: it overlaps the
existing `script` component (GraalVM polyglot Java/JS/Python/Ruby); needs a scope
decision before implementing.

| # | Issue | Component | Status |
|---|-------|-----------|--------|
| 58 | #2416 | List Helper | ✅ |
| 59 | #2419 | File Helper | ✅ |
| 60 | #2420 | Phone Number Helper | ✅ |
| 61 | #2423 | Zip Helper (a.k.a. CompressionHelper in #2282) | ✅ |
| 62 | #3740 | Code Interpreter | deferred — overlaps existing `script` component |

## C. Named in #2282 checklist but no dedicated issue yet (need issue + implementation)

**Status: DONE — 16 / 20 implemented.** Deferred: Entelligence (no public API), Git and
Kafka (protocol-level integrations, deferred together with SSH/AMQP/MQTT), Twitter Media
(media upload belongs to the existing `x/twitter` component and needs OAuth1 signing).

- CoinGecko ✅
- ERPNext ✅
- Elasticsearch ✅
- Entelligence — deferred, no public API
- FileMaker ✅
- Git — deferred, protocol-level
- Google Cloud Firestore ✅
- Google Cloud Realtime Database ✅
- Google Translate ✅
- Grafana ✅
- Home Assistant ✅
- Kafka — deferred, protocol-level
- LDAP ✅
- Mailgun ✅
- Nextcloud ✅
- QuickChart ✅
- TOTPHelper ✅
- Twitter Media — deferred, extend existing `x/twitter`
- Vapi ✅
- Wikipedia ✅

---

## Needs decision / review (not clearly "new component")

These open issues touch AI-agent internals, task-dispatcher/flow, or may be actions on
existing components rather than brand-new component directories. Confirm scope before
turning into implementation tasks.

- #2414 AI Agent Document Reader — AI agent tool
- #2415 AI Agent Memory — AI agent tool
- #4450 Built-in chat memory component (JDBC + Redis) — AI agent infra
- #4459 VectorStore Search Tool — AI agent tool
- #4482 AI Agent Utils (Spring AI Community tools) — AI agent infra
- #3839 AI Agent tool — AI agent
- #2745 Call workflow tool — flow/feature
- #2751 MCP Tool trigger — trigger for existing `mcp-client`
- #2449 Delay — likely overlaps existing `wait` component
- #2918 Webhook with auth methods (v2) — improvement to existing `webhook`

## Excluded — component already exists in master

`#725` Microsoft Dynamics (`microsoft-dynamics-crm`), `#2829` Google Workspace Admin
(`google-workspace-admin`), `#3745` AWS S3 (`aws-s3`), `#2823` ZoomInfo (`zoominfo`),
`#3721` Deepgram, `#3668` DHL, `#2530` Reddit, `#2512` Ahrefs, `#2830` HeyGen,
`#2825` zenrows, `#3454` X/Twitter, `#3158` Claude Code, `#2447` MCP Client,
`#2317` WooCommerce, `#2313` Zoom, `#2304` Posthog, `#2299` Mautic, `#2288` Bitbucket,
`#2289` Bolna, `#2598` Productboard, `#2715` Wrike, `#4445` Redis, `#4446` FTP,
`#4460` Script tool, `#4461` HTTP Client tool, `#4623` Merge Helper, `#4157` Property
Testing, `#3827` Form, and the many older per-action/partial issues (Notion, Hubspot,
Discord, Salesforce, Pipedrive, Xero, QuickBooks, Calendly, Cal.com, RSS, Retable,
Acumbamail, VBOUT, Beamer, Keap, Affinity, Accelo, Intercom, Liferay, Reckon, MYOB,
Clickup, Salesflare, Pipeliner, Twilio, WhatsApp, MongoDB, MySQL, Postgres). Also the
Postgres/MongoDB/MySQL/Redis/RabbitMQ entries left unchecked in #2282 are stale — those
directories exist.

---

# D. Gap vs n8n / Activepieces / Sim (not in master, not in A–C)

Method: enumerated every integration folder in each platform's repo (git clone,
`api.github.com` is egress-blocked), normalized names (case/`-`/`_`-insensitive, plus an
alias map for `gmail`↔`google-mail`, `x`↔`twitter`, `s3`↔`aws-s3`, `cal`↔`cal.com`, etc.),
and subtracted master (208 dirs incl. `aws/*`, `google/*`, `microsoft/*` sub-services) and
sections A–C above. Result: **562 candidate integrations**. The `[a/n/s]` tag marks which
of Activepieces / n8n / Sim ship it — more platforms = stronger demand signal.

## D1 — Present in ALL THREE platforms (top priority)

**Status: DONE** (OpenAI intentionally skipped — redundant with the universal `ai`
component; AMQP/MQTT deferred as protocol-level integrations).

| Component | Platforms | Note | Status |
|-----------|-----------|------|--------|
| Lemlist | a,n,s | sales engagement | ✅ |
| Okta | a,n,s | identity / SSO | ✅ |
| OpenAI | a,n,s | direct provider (ByteChef has universal `ai` — may be redundant) | skipped |
| UptimeRobot | a,n,s | monitoring | ✅ |

## D2 — Present in TWO platforms (high priority)

**Status: DONE.** All D2 components below are implemented on the `components` branch,
except SSH (protocol-level integration, deferred together with AMQP/MQTT from D1).

Acuity Scheduling `[a,n]`, Algolia `[a,s]`, Amazon SQS `[a,s]`, Ashby `[a,s]`,
Azure DevOps `[a,s]`, Bannerbear `[a,n]`, Bitly `[a,n]`, Brandfetch `[n,s]`,
Chargebee `[a,n]`, Clockify `[a,n]`, Cloudflare `[n,s]`, Confluence `[a,s]`,
Contentful `[a,n]`, ConvertKit `[a,n]`, Customer.io `[a,n]`, Databricks `[n,s]`,
Datadog `[a,s]`, Devin `[a,s]`, Discourse `[a,n]`, Dropcontact `[n,s]`, Dub `[a,s]`,
Fathom `[a,s]`, Formstack `[a,n]`, Freshservice `[a,n]`, Gamma `[a,s]`,
GetResponse `[a,n]`, Gong `[n,s]`, Grist `[a,n]`, Help Scout `[a,n]`,
Hugging Face `[a,s]`, Invoice Ninja `[a,n]`, Mailjet `[a,n]`, Matrix `[a,n]`,
MessageBird `[a,n]`, Metabase `[a,n]`, Mindee `[a,n]`, Mistral AI `[a,n]`,
Netlify `[a,n]`, NeverBounce `[a,s]`, Onfleet `[a,n]`, Paddle `[a,n]`,
Perplexity `[n,s]`, Phantombuster `[a,n]`, Pinecone `[a,s]`, Postmark `[a,n]`,
Qdrant `[a,s]`, QuickBase `[a,n]`, Raindrop `[a,n]`, Segment `[a,n]`, Sendy `[a,n]`,
Square `[a,s]`, SSH `[n,s]`, SurveyMonkey `[a,n]`, Tapfiliate `[a,n]`,
Vercel `[a,s]`, Vero `[a,n]`, Wealthbox `[a,s]`, Workable `[a,n]`, Workday `[a,s]`,
Wufoo `[a,n]`, ZeroBounce `[a,s]`.

## D3 — Notable single-platform integrations (curated by category) — DONE

All categories below are complete; per-item deferrals are noted inline.

Well-known products worth tracking; the long tail of niche/AI-wrapper pieces is omitted
(see D4 for the count).

- **CRM / Sales — DONE:** Close ✅, Front ✅, Twenty ✅, Salesloft ✅, Streak ✅, Folk ✅,
  Kommo ✅, Kustomer ✅, HighLevel ✅, Freshworks CRM ✅, Zendesk Sell ✅; Copper already
  exists in master (stale entry); NetSuite deferred (OAuth 1.0a TBA, account-specific
  setup).
- **AI / ML — DONE:** Cohere ✅, AssemblyAI ✅, Gladia ✅, Synthesia ✅, Runway ✅,
  Jina AI ✅, Flowise ✅, Letta ✅, Mem0 ✅, Zep ✅, Descript ✅ (API token, verified
  against Activepieces); already covered by `ai/llm`: DeepSeek, Groq, Google Gemini,
  Azure OpenAI, Amazon Bedrock, Stability AI, OpenRouter (`ai/llm/router/open-router`);
  deferred: Google Vertex AI (belongs in `ai/llm` with Spring AI GCP deps).
- **Databases / Infra / DevOps — DONE:** ClickHouse ✅, Neo4j ✅, QuestDB ✅, CrateDB ✅,
  Upstash ✅, Railway ✅, Tailscale ✅, 1Password ✅ (Connect), Bitwarden ✅,
  LaunchDarkly ✅, New Relic ✅, Splunk ✅ (HEC), CircleCI ✅, Gitea ✅, Trigger.dev ✅,
  Rundeck ✅; covered by existing components: TimescaleDB (`postgresql`), Amazon RDS
  (`mysql`/`postgresql` data access); now also done: Temporal ✅ (HTTP
  API reads), Cursor ✅ (Background Agents API), Infisical ✅ (v4 REST), Dagster ✅
  (GraphQL); deferred: DynamoDB (AWS SigV4/SDK work under `aws/`), Couchbase
  (SDK/binary protocol; `ai/vectorstore/couchbase` exists).
- **Cloud storage — DONE:** Azure Blob Storage ✅ (SAS), Google Cloud Storage ✅, Backblaze B2 ✅.
- **Comms / Email / SMS — DONE:** SMTP ✅ (covered by existing `email` component),
  IMAP ✅ (covered by existing `email` component), Constant Contact ✅ (OAuth2),
  EmailOctopus ✅, Campaign Monitor ✅, Elastic Email ✅, SendPulse ✅ (OAuth2 client
  credentials), Mandrill ✅, Telnyx ✅, Plivo ✅, Vonage ✅, ManyChat ✅, Missive ✅,
  Twilio Voice — deferred (extend the existing `twilio` component instead).
- **Marketing / Social — DONE:** Buffer ✅, Postiz ✅, Mastodon ✅, Bluesky ✅ (app
  password), Pinterest ✅ (OAuth2), Twitch ✅ (OAuth2), Vimeo ✅, Hootsuite ✅ (OAuth2),
  TikTok ✅ (OAuth2), Instagram Business ✅ (Facebook Graph OAuth2).
- **Payments / Finance — DONE:** Mollie ✅, Razorpay ✅, Lemon Squeezy ✅, Recurly ✅,
  Zuora ✅ (OAuth2 client credentials), PayPal ✅ (OAuth2 client credentials), Wise ✅,
  Quaderno ✅; deferred: Actual Budget (sync-protocol API, no plain REST endpoint).
- **Docs / PDF / e-sign — DONE:** PandaDoc ✅, PDF.co ✅, PDFMonkey ✅, Carbone ✅,
  SignRequest ✅, SignNow ✅, eSignatures ✅, PDF4me ✅ (verified against Activepieces).
- **Analytics / Monitoring — DONE:** Matomo ✅ (self-hosted, token_auth),
  Plausible ✅, Umami ✅, Fathom Analytics ✅, Incident.io ✅, Rootly ✅.
- **Support / Ticketing — DONE:** Chatwoot ✅, Crisp ✅, Gorgias ✅, Pylon ✅.
- **HR / Recruiting — DONE:** Greenhouse ✅, Lever ✅, Rippling ✅.
- **Productivity / PM — DONE:** TickTick ✅ (OAuth2), Motion ✅, Taskade ✅,
  Smartsheet ✅, SmartSuite ✅, Teable ✅, MeisterTask ✅, YouTrack ✅ (self-hosted/cloud
  instance URL), Obsidian ✅ (Local REST API plugin); deferred: Podio (legacy app-auth
  OAuth), Evernote (OAuth 1.0a / Thrift, proxy-only in references).
- **Forms / Survey — DONE:** Tally ✅, Fillout ✅, Formbricks ✅, Cognito Forms ✅,
  Gravity Forms ✅ (WordPress REST API), Paperform ✅.
- **Protocols / other — DONE:** Microsoft OneNote ✅ (Graph OAuth2), Microsoft
  Planner ✅ (Graph OAuth2), Webex ✅, PagerDuty ✅ (already exists), Google Ads ✅
  (OAuth2 + developer token), Google My Business ✅ (OAuth2), Microsoft Power BI ✅
  (Azure AD OAuth2), Microsoft Dataverse ✅ (environment URL + OAuth2), Oracle Fusion
  Cloud ERP ✅ (Basic auth REST); deferred: AMQP (message-broker protocol, not a REST
  component), SAP S/4HANA and SAP Concur (references are proxy-only; tenant-specific
  OAuth/BTP host resolution).

## D4 — Long tail (not itemized)

Beyond D1–D3 there are ~400 more single-platform entries — mostly niche AI wrappers,
regional SaaS, tiny lead-enrichment/verification tools. The named examples are now
implemented: Avian ✅, Bocha Search ✅, Icypeas ✅, LeadMagic ✅, Reoon Email
Verifier ✅, Sixtyfour ✅, Pubrio ✅ (verified against Activepieces).
The remaining unnamed long tail stays low priority; the full raw diff (562 rows with
per-platform tags) is preserved in `scratchpad/diff.py` output.

### False positives excluded during the diff
n8n `Postgres` (`postgresql` exists), `Html` / `GraphQL` (internal / `graphql-client`),
`Crypto` (`crypto-helper`), `Jwt` (`jwt-helper`), `Code`/`Set`/`If`/`Merge`/`Filter`
(flow primitives); Activepieces `fireflies-ai` (already tracked as #3676). Alias-matched
as already-present: `gmail`, `outlook`, `microsoft-excel-365`, `sharepoint`, `x`/`twitter`,
`amazon-s3`, `cal-com`, all `zoho-*`, `bigin-by-zoho`.

---

# E. Re-diff 2026-07-14 (still missing after A–D)

Re-ran the three-platform diff against the `bf010e20ca5` snapshot (which now ships all of
A–D) using the canonical set built from **component directory names** (not inline
`component("key")` strings — OpenAPI components like `jira`, `monday`, `box` define their
key elsewhere and were false "missing" hits under the old key-only method). Two genuine
gaps remained that the earlier passes had not surfaced.

**Status: DONE — all 8 implemented** (mirroring the reference base URL / auth / core
endpoints; each ships a connection, actions, a tool cluster element, and a generated
definition snapshot).

## E1 — Present in TWO platforms (newly surfaced)

| Component | Platforms | Note | Status |
|-----------|-----------|------|--------|
| Granola | a,s | AI meeting-notes; Bearer; list notes / folders | ✅ |
| MillionVerifier | a,s | email verification; `api` query key; verify email + credits | ✅ |
| Pushbullet | a,n | push messaging; `Access-Token` header; create push + get pushes | ✅ |

## E2 — Notable single-platform (newly surfaced, not niche long-tail)

| Component | Platform | Note | Status |
|-----------|----------|------|--------|
| Aircall | a | cloud phone; HTTP Basic (apiId:apiToken); list + create contact | ✅ |
| BigCommerce | a | e-commerce; `X-Auth-Token` + store-hash base; list products + customers | ✅ |
| Clay | s | GTM enrichment; webhook URL + `x-clay-webhook-auth`; send record | ✅ |
| Drift | n | conversational marketing; OAuth2; list + create contact | ✅ |
| Magento | n | Adobe Commerce; Bearer integration token; get product by SKU + get order | ✅ |

Everything else the re-diff reported was a false positive already present under an alias
(`elasticsearch`, `sentry`, `jira`, `monday`, `telegram`, all `google/*` sub-services) or
the D4 niche long-tail (~640 single-platform AI-wrapper / regional-SaaS entries left
low-priority by design). NetSuite stays deferred (OAuth 1.0a, account-specific host) as
already noted in D3.
