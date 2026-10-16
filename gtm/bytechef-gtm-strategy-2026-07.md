# ByteChef Go-To-Market Strategy

**Version:** 1.2 — July 28, 2026
**Owners:** Ivica + co-founder — both part-time on content AND outbound; ownership per track defined in §8
**Updated:** October 9, 2026 - target lists linked in §6.4 and §7.3
**Updated:** October 16, 2026 - merged the April 2026 GTM playbook: regulated/self-hosted ICP (§6.1), community and founder-content plays (§6.2), partner channel (§6.5), outbound sourcing and cadence rules (§7.3), weekly checklist (§8), case-study template (Appendix D), homepage copy (Appendix E). Dropped from April: the ICP execution order that put embedded last, the € pricing tables, "100+ connectors", and the April 90-day numeric targets. This document replaced them.
**Review cadence:** biweekly metrics review; full revision at the Day-90 gate (~October 31, 2026)

---

## 1. Executive Summary

ByteChef is one product with two commercial motions: an **open-source workflow automation + AI agent orchestration platform** (bought by companies for their own use) and an **embedded iPaaS** (bought by SaaS companies to ship customer-facing integrations inside their product). This document defines **one strategy with two tracks** rather than two strategies or one blended message.

**The core decisions:**

1. **Track A (OSS Automation) is the standing engine.** It follows demonstrated inbound demand, feeds both goals (adoption/community and first payers), and is owned day-to-day by the content lead.
2. **Track B (Embedded iPaaS) gets a 90-day validation sprint**, founder-led. Current inbound tells us nothing about embedded demand because the story has never been told publicly — the sprint runs the missing experiment. Embedded is where ByteChef's structural differentiation is strongest (open source; no execution metering when self-hosted) and where established budgets are largest ($15–40k+/yr category contracts).
3. **A decision gate at Day 90** with pre-committed criteria determines whether embedded becomes the primary revenue track or returns to a maintained side door.

**Goals (6–12 months):** grow open-source adoption and community; land first paying customers.
**Capacity:** ~1 FTE total (Ivica part-time + content lead part-time). Everything in this plan is sized to that.

---

## 2. Context and Evidence

### 2.1 What we know

| Signal | Finding | Implication |
|---|---|---|
| Inbound today | Skews "internal automation for ourselves" | Track A funnel is real and warm |
| Embedded inbound | ~None — but embedded has had ~zero marketing airtime | Absence of signal ≠ absence of demand; test properly |
| Commercial surface | Public pricing exists for BOTH motions (bytechef.io/pricing) | No packaging work blocks either track |
| Team | 2 co-founders, each part-time for content AND outbound (~1.2–1.5 FTE combined) | One motion at full quality + one properly-resourced experiment; outbound can run at ~2× a solo-founder pace |
| Product surface (embedded) | Connected users, integration instances, white-label, embedded APIs exist; sample app exists; docs incomplete, some features missing — but enough to demo | Sprint cost is marketing + docs, not engineering |

### 2.2 Why one strategy, two tracks

- **Not two strategies:** one engine, one connector catalog (190+), one community. Every OSS automation user hardens the platform and grows the catalog that embedded buyers evaluate. Two full GTM motions at this capacity means both done badly.
- **Not one blended message:** the audiences have different jobs-to-be-done. "Automate your internal workflows" (ops/dev/IT buying for themselves) and "ship native integrations inside your SaaS" (product/engineering leaders buying for their customers) share almost no messaging, channels, or competitor set. A homepage that says both at once converts neither — segment the paths, share the core.

---

## 3. Shared Core

### 3.1 Product narrative

ByteChef is an **open-source platform that unifies AI agent orchestration and workflow automation** — build precise workflows where you need control, deploy autonomous agents where you need adaptability, and run it all on your own infrastructure with no vendor lock-in.

The same engine powers two things:
- **For your company:** internal automation and AI agents (Zapier/Make/n8n territory).
- **For your product:** white-label, customer-facing integrations embedded in your SaaS (Paragon/Prismatic/Appmixer territory).

### 3.2 Positioning pillars (shared by both tracks)

1. **No black box, no lock-in.** Inspect the engine. Extend any of 190+ connectors or write your own. Nothing proprietary wired into your stack — and for embedded buyers, no vendor-death risk inside *your* product. The automation platform is open core; the embedded iPaaS ships under EE, source-available, with a free evaluation licence — so on either track you validate fully before you pay. (Say "open source" for Track A, where it is literally true; say "source-available, validate free" for Track B. Never claim CE contains embedded — it does not, and a prospect will check.)
2. **Your infrastructure, your data.** Self-host in your VPC. Data residency and compliance by construction, not by enterprise addendum. (Strong EU resonance.)
3. **Pricing that doesn't punish growth.** Self-hosted execution is unmetered — no task meter, no instance meter, no per-connected-user meter watching your success. Cloud tiers are a low-friction on-ramp, not a trap (overage is $1 per 1,000 tasks; see §5).
4. **AI-native.** Agent orchestration, MCP servers, A2A, AI copilot — not a legacy workflow tool with an AI feature bolted on.

### 3.3 Positioning statements

**Track A:**
> For engineering and ops teams who want automation and AI agents without SaaS meters or data leaving their infrastructure, ByteChef is an open-source automation platform that runs anywhere. Unlike n8n or Zapier, it unifies agents and workflows in one engine, with an Apache-style open core and transparent pricing.

**Track B:**
> For B2B SaaS teams that need to ship native, customer-facing integrations, ByteChef is a source-available embedded iPaaS with white-label UX and flat, adoption-independent pricing. Unlike Paragon, Prismatic, or Appmixer, you can read the engine you're embedding, run it in your own VPC, and validate the whole thing on a free evaluation licence before paying — and it never meters your customers' usage.

---

## 4. Competitive Landscape

### 4.1 Embedded iPaaS (Track B competitors)

| Vendor | Plans | Metering | Entry price | Typical contract | Public pricing? |
|---|---|---|---|---|---|
| **Paragon** | Pro, Enterprise | Tasks + connected users | ~$500–3,000+/mo; 5-figure annual floor even for startups | ~$15–40k/yr mid-market; $50–100k+ enterprise | No — sales-quoted |
| **Prismatic** | Scale, Enterprise, Custom | Per integration instance | ~$500/mo | ~$6–10k/yr entry; low-to-mid 5 figures typical | No — sales-quoted |
| **Appmixer** | Starter, Professional, Self-managed | Data messages + users | Starter $500/mo (10 users, **1,000 messages/mo**); Professional from $1,300/mo | ~$6–16k+/yr; self-managed quote-only | Partially |

> Figures assembled from third-party sources (Nango, Merge, Albato, Capterra, SaaSworthy, SoftwareSuggest) as of July 2026; vendor pages block scraping. **Verify manually before publishing any comparison page that names numbers.**

**Category-wide observations we exploit:**
- **Everyone meters success.** Tasks (Paragon), instances (Prismatic), data messages (Appmixer). No one in the set offers unmetered execution. Self-hosted ByteChef offers it structurally for free — the customer runs the compute.
- **Pricing opacity is the norm.** Paragon and Prismatic hide all numbers; buyers must take a sales call to budget. Transparent pricing is a differentiator by itself.
- **The buyer's pain is the renewal, not the purchase.** Usage grows → quote shocks. "Paragon renewal shock" is a real content and outbound angle.
- **The umbrella is high.** The market already accepts $15–40k/yr for this job. ByteChef's Embedded Growth (~$12k/yr) is credible, not suspiciously cheap.

**Per-vendor attack lines:**
- **vs Paragon:** openness + cost + control. They sell managed polish at 5-figure floors; we sell the same job, inspectable and flat-priced, in your VPC.
- **vs Prismatic:** the developer-audience overlap is largest — argument must be *open source vs proprietary dev platform*, not price alone. Extend the engine, own the runtime, no per-instance meter.
- **vs Appmixer:** they already say "embeddable designer + self-hosted + cheaper" — our edge is open source (free validation, community catalog, no license to start, no vendor-death risk) and dramatically better unit economics ($1/1,000 tasks overage vs their $500/1,000-message starter allowance — ~500× on list figures).

**Honest weakness to manage:** incumbents' price buys polish — connector depth on specific integrations, embedded UI components maturity, managed infra, SOC2 story. Early deals must be scoped with design partners who value control and cost over turnkey polish, with our hands-on help closing the gap.

### 4.2 OSS automation (Track A competitors — summary)

n8n (huge gravity, fair-code license, cloud + enterprise), Activepieces (AGPL, embeddable), Windmill, Temporal (dev-heavy adjacent), Zapier/Make (SaaS incumbents, price/lock-in refugees are our inbound). Track A does not try to out-shout n8n; it wins the segment that wants **true open source + AI agents + self-host + no per-task pricing**, and converts on production-readiness.

---

## 5. Pricing Strategy

### 5.1 Current state (bytechef.io/pricing)

| | Starter | Growth | Enterprise |
|---|---|---|---|
| **Automation** | $29/mo ($23 annual) — 1,000 tasks, 1 workspace, 1 user | $169/mo ($135) — unlimited users, 3 workspaces, RBAC, 30-day logs | Custom — unlimited workspaces, self-host, priority support |
| **Embedded** | $249/mo ($200) — 10 connected users, 1,000 tasks | from $999/mo ($799) — custom users/tasks | Custom |

Overage: **$1 per 1,000 tasks** on all plans. OSS self-hosting: free, unmetered.

### 5.2 The task-meter tension — and resolution

Our cloud pricing meters tasks and connected users — the same structure we attack incumbents for. Resolution (in priority order):

1. **Lead every embedded pitch with the self-hosted flat-fee tier.** Make Embedded Enterprise explicitly: *flat annual fee, unlimited executions, unlimited connected users, your VPC.* This is where the real $15–40k deals happen, and there the "no success tax" claim is 100% true. Cloud metered tiers are positioned as the low-friction on-ramp.
2. **Where meters are compared, reframe to unit economics.** We don't claim "no meter" on cloud — we show the meter: $1 per 1,000 tasks vs the category's punitive allowances. Publish the cost-per-execution table.
3. **Defer pricing surgery.** Restructuring embedded cloud to flat tiers is cleaner but is not worth mid-sprint capacity. Revisit at the Day-90 gate with real buyer feedback.

### 5.3 Deal guidance (Track B sprint)

- **Anchor:** Embedded Growth $999/mo billed annually (~$12k/yr). Do not discount below it in cash terms.
- **Design-partner sweetener is service, not price:** we build their first 3 integrations with them, shared Slack channel, roadmap influence, launch case study. If a discount is demanded: first-year 30–40% off in exchange for a public logo + case study, reverting to list at renewal — written into the order form.
- **Self-hosted embedded deals:** start quoting flat at $15–20k/yr (inside the category band, above "too cheap to trust"), adjust with evidence.

---

## 6. Track A — OSS Automation (standing engine)

**Owner:** co-founder (with Ivica ~1–2 hrs/wk for technical review and user calls).
**Time share:** ~80% of GTM capacity in steady state; during the sprint this track runs at maintenance cadence (community responsiveness + the pre-written comparison pieces + the reliability launch) while both founders' outbound hours go to Track B.

### 6.1 ICP

- 10–500-person companies with engineering capability; ops/platform/IT-adjacent devs.
- Self-host preference: data control, EU/compliance, cost predictability.
- Currently on Zapier/Make (hitting per-task price walls) or evaluating/outgrowing n8n (license discomfort, AI-agent needs, Java-shop affinity).
- Secondary: teams standardizing AI agents who need orchestration + guardrails + human-in-the-loop.
- Secondary: **regulated and self-hosted buyers**. That means finance and insurance, plus professional services (consulting, law and accounting firms) that want data control, compliance and annual contracts. These deals close on referrals and trust, not outbound volume. Work the warm network and the two financial-industry pilots. Treat this segment as opportunistic, not systematic.
- Not separate segments: digital agencies and automation consultants are a distribution channel (§6.5), not buyers to chase.

### 6.2 Funnel and plays

**Awareness → Install:**
- **Comparison/SEO content** (the compounding asset): "ByteChef vs n8n", "vs Activepieces", "vs Zapier", "open-source Zapier alternative", "self-hosted Make alternative", "open-source AI agent orchestration". One well-researched piece every 2 weeks minimum, on the existing blog. Be honest and don't trash n8n: respect earns links. Expect long-tail rankings in 3–6 months. Until then, the pages pay off as URLs to share in outreach, Reddit and LinkedIn.
- **Community posts** (one every 2 weeks, rotate the venue): r/selfhosted first, then r/SaaS, r/opensource, r/devops, r/startups, r/indiehackers, r/webdev, r/programming for architecture deep-dives, r/nocode / r/lowcode, and r/LocalLLaMA for agent features. Lead with value, not the product: "I built an open-source alternative to X because…" or "How we solved Y". Link GitHub, not the marketing page. Post Wednesday 9–11am ET and answer every comment, critical ones first. On HN, Show HN works once, so save it for a notable release. Between releases, comment helpfully on threads about Zapier pricing, n8n and integration pain.
- **Founder LinkedIn** (personal accounts, not the company page; founder posts far outperform company posts at this stage). Target 1 longer post plus up to 2 short ones a week; the floor is 1 a week. Post Tue–Thu, 8–10am CET, with at least ~18h between posts. Rotate the longer posts through four types:
  - problem-aware (the hidden cost of building integrations one by one);
  - behind-the-scenes (building GTM as a technical founding team);
  - contrarian (per-task pricing is a tax on customer success);
  - milestone (what users actually automate, anonymized data).

  The first line must hook. Keep longer posts under 1,300 characters and end with a question, not always a product link. Reply to every comment within 4h and engage with a few relevant posts daily. When capacity allows, DM people who post about Zapier/Make pricing frustration.
- **Directories and lists:** every "n8n alternatives" / "open-source automation" listicle (Nango-style vendors, awesome-lists, alternativeto, G2 categories). One-time sweep, then quarterly.
- **Release-driven launches:** each meaningful release gets a launch post + short demo video + community posts (HN Show HN when genuinely notable, r/selfhosted, relevant Discords). **Next launch spine: the error-handling/reliability release** (error workflows, auto-recovery, crash recovery, per-run timeouts, alert rules) — framed as *"automations you can trust in production."* Reliability is precisely what separates toy automation from paid automation, and it tees up the conversion play.

**Install → Activation:**
- Ruthless first-30-minutes quality: quickstart accuracy, `docker compose up` happy path, 5 canonical templates (the workflows people actually search for). Fix every friction report within the week — at our scale, support IS marketing.

**Activation → Community:**
- Fast, human responses in GitHub issues/Discord (existing behavior, keep it). Monthly "what shipped" community note. Highlight community connectors/workflows.
- **GitHub is top of funnel.** The README's first three lines must answer what this is, who it's for, and why to care, with a GIF or screenshot. Keep "good first issue" labels fresh and GitHub Discussions on and answered. Put a star CTA in every post. Track the weekly star delta and what drove it.

**Community → Paid (first payers live here):**
- **Instrument first:** we currently lack a reliable view of heaviest users. Cheapest instruments: cloud signup data; Discord/GitHub activity ranking; optional anonymous telemetry ping (opt-in, self-hosted) if acceptable; "who's running this in production?" thread.
- **Talk to the top 10–20 users** (Ivica, 30-min calls): what would production adoption require? The answers are the EE/paid roadmap.
- **Make the upgrade path visible:** in-product and in-docs signposts where paid gates live (RBAC, workspaces, log retention, support SLA). A happy self-hosted user should never wonder *whether* there's a commercial tier or *how* to talk to us.
- **Conversion nudge:** after ~30 days of self-hosted use, show one non-intrusive prompt offering managed cloud or Enterprise support, with prices per §5.1.
- Expected first payers: Growth-tier cloud teams and self-hosted Enterprise (support + production features). Price points are already live.

### 6.3 Track A metrics

- **North star:** weekly active workflows (cloud) + best-available proxy for active self-hosted installs.
- Leading: GitHub star velocity, Docker pulls, docs quickstart completions, Discord joins, cloud signups.
- Revenue: Starter→Growth conversions, Enterprise conversations opened, MRR.
- Content: organic sessions to comparison pages, signups attributed to content.

### 6.4 Track A target list (Croatia)

**File:** `bytechef-automation-target-list-croatia-2026-10.xlsx` (built October 4, 2026). 114 internal-use buyers (rows 1-100 are the target list, 101+ are reserve) and 27 implementation partners, scored against the §6.1 ICP, with suggested contacts and tracking columns. The Notes tab holds the method, rubrics, sequencing and research gaps.

This list is for selective, founder-led outreach in the home market, aimed at first payers and production references. It is not a volume sequence and stays below the Track B sprint in priority until the Day-90 gate. Start with partners (CROZ, Kodilion, Notch, Provexio), then named-tool users, with regulated self-hosters as slow burners.

**What to do with it (owner: co-founder, Ivica joins calls):**
1. **Partners first.** Contact CROZ, Kodilion, Notch and Provexio. Book quick design-partner calls with the micro n8n shops (Sinqra, AIS Tech, cerkicai). Run the Odoo partners (E-Sustavi, Notus IT, Uvid, Ecodica, 2P) as one mini-campaign with one pitch: "the integration layer around Odoo".
2. **Named-tool buyers next.** Alma Career Croatia, Plavi tim, FORA Solutions, PREMIS, A.G.S. Zagreb, GA Croatia, Hypefy, AGRIVI, Hidrocibalae. Re-open every job ad before citing it, and say "saw you were hiring for..." rather than "you use...".
3. **Regulated self-hosters in parallel, as slow burners.** Lead with self-hosting, AI agents with human approval and no per-task fees. Expect long cycles and public procurement.
4. **Group accounts once.** Poslovni sustavi (five city companies), the Agram group, and Angelina Yachting with Ultra Sailing.
5. **Pitch in Croatian.** Fill in contacts and log every touch, reply and call in the workbook's yellow columns.
6. **Close the research gaps** listed in the Notes tab (partner directories, Power Platform partners, manual job-board sweep, GitHub stargazers).
7. **Pace:** a handful of personalized touches per week. Never at the expense of Track B outreach hours before the Day-90 gate.

### 6.5 Partner channel — agencies and automation consultants

Partners are a channel, not an ICP. Automation consultants implement ByteChef for Track A buyers as a Zapier/Make replacement. Agencies that build SaaS products for clients can embed ByteChef (Track B) instead of building an integration layer. The Croatian partners in §6.4 are the first instance of this channel. Broader recruitment starts after the Day-90 gate.

**Where to find them:**
- **n8n ambassadors with client-facing businesses.** n8n has no white-label mode, so this is a real gap. Template in Appendix B.
- Automation communities: No Code Founders (Slack), Make user communities, r/nocode, r/automation.
- LinkedIn searches for "automation consultant", "no-code agency", "Zapier expert" and "Make expert", aimed at solo practitioners and agencies of 1–20 people.

**Draft program terms** (from the April playbook, not yet ratified; confirm before the first offer): 20% partner discount, with the partner setting their own client markup; co-marketing through joint case studies and a partner-directory listing; founding-partner benefits for the first 10 partners for 12 months; a referral bonus of 15% of first-year revenue on referred direct customers.

**Rules:** no partner portal until there are 5+ active partners. Spend at most 2–3 hrs/week on recruitment. Treat every partner conversation as a test of whether the channel works. If partners aren't converting after 3 months, stop investing.

---

## 7. Track B — Embedded iPaaS: 90-Day Validation Sprint

**Lead:** Ivica (assets, message); outreach shared by both founders. **Window:** ~Aug 4 – Oct 31, 2026.
**Hypothesis:** *B2B SaaS teams will engage seriously (calls → design partnerships) with an open-source, flat-priced, self-hostable embedded iPaaS, once the story is actually told.*

### 7.1 Assets (Weeks 1–2)

1. **Embedded quickstart docs** — the single highest-leverage asset. End-to-end path: SaaS engineer lands on docs → runs the sample app → white-label integration working **in one afternoon**. Includes: architecture overview (connected users, integration instances, white-labeling), auth/embedding steps, 2 worked integrations, troubleshooting. Gaps in product features are documented honestly ("not yet supported: X") — design partners forgive gaps, not surprises.
2. **Embedded landing page** (copy draft in Appendix A): who it's for, the differentiator line, pricing clarity per §5.2, design-partner CTA, links to quickstart + sample app.
3. **Sample app polish pass:** README, seeded demo data, deploy-in-one-command, short Loom walkthrough (reused in outreach).
4. Homepage: embedded elevated to a co-equal path (segmented hero or clear dual CTA) — small change, big signal.

### 7.2 Anchor content (co-founder drafts from Ivica's outlines; Weeks 2–6)

1. **"Open-source Paragon & Prismatic alternative"** — the comparison page; honest about polish gaps, brutal on pricing structure and lock-in. (Primary SEO + outbound artifact.)
2. **"The success tax: how embedded iPaaS pricing punishes your growth"** — tasks/instances/data-messages exposé with the unit-economics table; our flat self-hosted framing per §5.2. (The shareable thesis piece.)
3. **"How embedding ByteChef works"** — technical walkthrough derived from the quickstart. (Credibility piece for the engineer who gets forwarded the email.)

**Backlog after the anchors:** "The real cost of building integrations in-house" (a calculator: engineering hours × rate × connectors, plus auth, rate limits and maintenance); "How to add workflow automation to your SaaS product" (a non-promotional tutorial with ByteChef as one option); "Open-source iPaaS: what it means and why it matters" (thought leadership).

### 7.3 Founder-led outreach (Weeks 3–12)

**Run by BOTH founders** — split the list (by vertical or alphabet, whatever's cleanest), shared templates and a shared tracking sheet so voice and follow-up discipline stay consistent. Combined pace: ~15–20 personalized first-touches/week, which supports a bigger list without sacrificing personalization.

**Target list (50–80 named companies), criteria:**
- B2B SaaS, seed–Series B, ~5–50 engineers (big enough to have integration pressure, small enough to lack an integrations team);
- Evidence of pain: thin/empty integrations page, "integrations engineer" job posts, public roadmap or changelog promising integrations, users requesting integrations on their community/support forums;
- Verticals where customers demand integrations: vertical SaaS (health, construction, logistics, legal), dev tools, HR/fin ops tools;
- EU bias where self-host/data-residency resonates most.

**Built list:** `bytechef-embedded-outreach.xlsx` - 101 companies across five segments (EU vertical, US vertical, dev tools, HR/fin-ops, Croatia), scored and split into waves, with contacts and the shared tracking columns in one workbook. It replaces the old target-list .md and tracker .csv. The list ran past the 50-80 target; treat waves 1-2 as the sprint list and wave 3 as reserve.

**Two lists, one per track.** Embedded (Track B) prospects live in `bytechef-embedded-outreach.xlsx`; internal-automation (Track A) prospects live in `bytechef-automation-target-list-croatia-2026-10.xlsx` (§6.4). A few Croatian companies fit both (AGRIVI, Hypefy, Oradian, plus Ciklopea, AdriaScan, Lognet and Entrio). Pick one track and one owner per account so nobody gets two pitches.

**What to do with it (both founders, split by the workbook's owner column):**
1. **Fill the contact gaps.** 14 companies have no named contact, including Wave 1's Attio, folk, Cargoson, Vertuoza, Humaans and Kenjo. Find the CTO, CPO or founder, not sales.
2. **Run the pre-send checklist per company.** Open the integrations page and confirm the hook fact, sanity-check headcount on LinkedIn, rewrite the hook in your own voice.
3. **Send Wave 1 first.** That's the 22 international score-9+ companies plus the 14 Croatia Wave 1 companies. Send the Croatian Fiskalizacija 2.0 sub-segment first regardless of score, because the January 1, 2027 non-VAT wave makes Q4 2026 the window. Pitch Croatian companies in Croatian.
4. **Sequence:** first touch, follow-up at day 4, break-up at day 10. Combined pace 15-20 first touches a week, then Wave 2, then Wave 3.
5. **Log everything** in the yellow columns: touch dates, reply, call booked, call held, partner status. The Day-90 gate in §7.5 is read from this workbook.

**Sequence:** personalized founder-to-founder email (template in Appendix B) → follow-up at day 4 → break-up at day 10. Sustained weekly volume beats a week-one blast. Every reply gets a 15-min call offer, not a deck. Calls can be taken by either founder; log every call against the §7.3 script questions in the shared sheet.

**Buyer personas:** the primary buyer is the CTO / VP Engineering, who feels the build cost. Second is the CPO / Head of Product, who hears the integration requests. At small companies, the CEO is often the product leader.

**Sourcing for later waves** (signal-based, highest intent first):
- GitHub repos where a company is building its own integration layer (`integrations`, `workflow`, `zapier`, `n8n`);
- job posts for "integrations engineer" or "partnerships (integrations)" on LinkedIn Jobs, Welcome to the Jungle (Otta) and Wellfound;
- n8n/Zapier community threads where SaaS companies ask about white-label or embedded options;
- G2/Capterra reviews that complain about missing integrations;
- YC/Techstars directories, filtered to B2B SaaS at Series A–B.

**Cadence rules:**
- Send a LinkedIn connection request with a short note on the same day as the first email.
- Send at most ~10 cold emails/day per sending domain, and warm up any new domain first.
- Answer positive replies within 2 hours.
- Put every "not now" on a 90-day re-engagement list.
- Test 2–3 subject lines, e.g. "integrations on {{Product}}'s roadmap?", "{{Company}} + embedded integrations?", or "saw you're hiring an integrations engineer".

**Design-partner offer (cap at 5):** we build your first 3 integrations with you; shared Slack; roadmap influence; first-year terms per §5.3; you give us feedback calls + a case study at ship.

**Call script tests (log every answer):** Do they accept the problem framing? Which pillar lands first — open source, self-host, or flat pricing? What's the polish objection? Who else did they evaluate, and what were they quoted?

### 7.4 Ecosystem placement (Weeks 3–8, background)

- Get listed in existing "Paragon alternatives"/"Prismatic alternatives" posts (Nango, Merge, Albato actively maintain these and add vendors; email them).
- G2/Capterra embedded-integration categories.
- One well-timed HN/community post when the quickstart + landing page are live ("Show HN: open-source embedded iPaaS" — genuinely novel angle).

### 7.5 Gate criteria (pre-committed)

**Day-45 checkpoint (leading indicators):**
- Outreach reply rate ≥10% and ≥6 discovery calls booked, OR embedded landing page converting visitors to quickstart/CTA at a non-trivial rate.
- If both ~zero: fix the message/list mid-sprint, don't wait for Day 90.

**Day-90 gate** (thresholds sized to two-founder outbound at ~15–20 touches/week; a solo pace would justify ~8 calls / 2 commitments):
- **PASS (embedded becomes primary revenue track):** ≥12 discovery calls held AND ≥2 design-partner commitments (signed or verbally committed with a start date; target 3).
- **FAIL (embedded returns to maintained side door):** below both thresholds → Track A becomes the sole active motion; embedded assets stay live (landing page, quickstart, comparison post keep ranking; inbound still gets answered) but zero proactive investment.
- **Ambiguous middle** (calls but no commitments): diagnose from call logs — message problem (iterate 30 more days) vs product-gap problem (write the gap list, decide build-vs-shelve with real evidence).

### 7.6 Sprint risks

| Risk | Mitigation |
|---|---|
| Founder time collapses into engineering/support | Calendar-block outreach hours for both founders; if one drops, the other's list segment keeps the sprint alive; sprint fails honestly rather than silently |
| Docs debt bigger than 2 weeks | Scope quickstart to ONE golden path; everything else is "talk to us" |
| Polish objection dominates calls | Design-partner service offsets; log gaps as roadmap evidence |
| Track A stalls during sprint | Co-founder pre-writes two comparison pieces before sprint start; anchor pieces double as published content; community responsiveness stays non-negotiable |
| Pricing dissonance (cloud task meter) | §5.2 framing adopted everywhere BEFORE first outreach email goes out |

---

## 8. Operating Cadence (2 part-time people)

Both founders split their part-time GTM hours between content and outbound. During the sprint, the working split:

- **Weekly 30-min GTM sync (Mon):** last week's numbers, this week's 3 priorities each, blockers.
- **Ivica (~6–8 hrs/wk):** quickstart/docs + landing page (wks 1–2) → embedded outreach + calls (wks 3–12, ~8–10 touches/wk) + 1–2 hrs Track A user calls.
- **Co-founder (~6–8 hrs/wk):** weeks 1–2: build the 50–80-company target list + pre-write two Track A comparison pieces; weeks 3–12: embedded outreach (~8–10 touches/wk, own list segment) + anchor-piece drafting + community responsiveness + reliability-release launch coordination. Content cadence during the sprint leans on the three anchor pieces doing double duty as published content.
- **Voice consistency:** shared templates (Appendix B), shared tracking sheet (company, touch dates, reply, call notes against the script questions), and both founders sit in on each other's first two calls to calibrate.
- **Biweekly metrics review (30 min):** the two dashboards in §6.3 / §7.5 only. No vanity metrics.
- **Monday checklist** (before the GTM sync):
  - **Outreach:** touches sent vs the §7.3 pace; every reply answered; calls booked or held; the workbook updated; pipeline sorted into close / needs follow-up / dead.
  - **Content:** this week's LinkedIn posts drafted; Reddit/HN post drafted if it's a posting week; comments answered.
  - **Assets:** progress on the landing page, quickstart and case study until each ships, then their conversion numbers.
  - **Community:** GitHub issues triaged; "good first issue" labels added; Discussions answered; the star delta and its cause noted.
- **What we are explicitly NOT doing** (capacity protection): paid ads; conferences/sponsorships; separate embedded brand or domain; unified-API pivot; sales hires; pricing restructure before the gate; more than one launch per month; company-page LinkedIn content (founder accounts only); building features instead of selling (the product is good enough for design partners); treating agencies as a customer segment (§6.5); an EIC Accelerator application before the Day-90 gate gives us traction data to put in it.

---

## 9. 12-Month Arc (assuming Day-90 PASS)

- **Q4 2026:** 2–5 design partners shipping; first case study (Appendix D; start with the two financial-industry pilots); embedded pricing hardened (flat self-hosted tier formalized); reliability-release launch compounds Track A.
- **Q1 2027:** repeatable embedded playbook documented (ICP refined from won/lost, standard demo, standard order form); Track A conversion path instrumented end-to-end; first $50–100k ARR target.
- **Q2 2027:** decide on first GTM hire (likely content→full-time or a technical founder-sales support) from revenue, not hope.

(If FAIL: same arc with Track A conversion as the sole revenue focus and embedded assets in maintenance.)

---

## Appendix A — Embedded Landing Page (copy draft)

**Hero:**
> ## Ship native integrations inside your SaaS — without the success tax
> ByteChef is the embedded iPaaS you can read, run, and self-host. Give your customers the integrations they're asking for, white-labeled in your product, running on your infrastructure. Flat pricing. No task meters. No per-customer fees. No black box.
>
> [Read the quickstart] [Become a design partner]

**Social-proof strip:** 190+ connectors · Source-available · Self-host or cloud · AI-native

**Problem block:**
> Your customers keep asking for integrations. Building each one in-house costs weeks of engineering. The embedded iPaaS vendors will happily do it for a five-figure annual contract — priced per task, per instance, or per connected user, so the bill grows every time your customers actually use what you shipped.

**Three pillars:**
> **No black box, no lock-in.** Read the engine you're wiring into your product. Run the whole thing on a free evaluation licence before you pay. Extend any of 190+ connectors — or write your own. Source-available and self-hostable, so there's no vendor-death risk inside your product.
>
> **Runs in your VPC.** White-label integration UX embedded in your app; engine on your infrastructure. Data residency and compliance by construction. (Cloud option when you'd rather not host.)
>
> **Pricing that ignores your growth.** Self-hosted: flat annual fee — unlimited executions, unlimited connected users. Cloud: from $249/mo, overage at $1 per 1,000 tasks. Compare that to the category and bring a calculator.

**How it works (4 steps):** Deploy (or use our cloud) → Configure integrations from 190+ connectors or build your own → Embed the white-label UX with our SDK → Your customers connect their accounts; you never touch their credentials.

**Design-partner CTA:**
> We're onboarding 5 design partners this quarter. We'll build your first three integrations with you, you get direct roadmap influence and founder-level support, and a first-year partner price. [Book 15 minutes]

**FAQ seeds:** How is this different from Paragon/Prismatic? · What does self-hosting require? · What's not supported yet? (honest list) · What does the sample app show? · Licensing — what's open source, what's EE, and what do I get for free?

> **Licensing answer (must be one short paragraph, no hedging).** The automation platform is open source. The embedded iPaaS is an EE feature: the source is in the repo and you can read it, but running it needs a licence — and the evaluation licence is free. **[DECIDE: evaluation term — how long, and full-featured or capped.]** Say this plainly here and in the quickstart; a prospect who discovers it by cloning the repo instead of reading it here has been surprised rather than informed, which §7.1 explicitly warns against.

---

## Appendix B — Outreach Templates

**First touch (founder-to-founder, ≤120 words):**

> Subject: integrations on {{Product}}'s roadmap?
>
> Hi {{Name}} — saw {{specific, true observation: thin integrations page / job post / roadmap item / user request thread}}.
>
> I'm the founder of ByteChef, an open-source embedded iPaaS: white-label integrations your customers configure inside {{Product}}, running from your own infrastructure. Flat pricing — no per-task or per-customer meters, which is the part people usually don't believe until they've been quoted by Paragon.
>
> We're taking on 5 design partners this quarter: we build your first three integrations with you.
>
> Worth 15 minutes to see the sample app? Either way — the whole thing is open source if you'd rather just poke at it: https://github.com/bytechefhq/bytechef.
>
> — Ivica

**Follow-up (day 4):** one line + the Loom walkthrough link + one new fact ("here's the 3-minute demo of a customer connecting Salesforce inside a sample SaaS — {{loom}}").

**Break-up (day 10):** "Closing the loop — if integrations climb the roadmap later, the quickstart will still be here: {{link}}. Good luck with {{true thing about their product}}."

**Ecosystem-listing email (to Nango/Merge/Albato content teams):** "You maintain {{'X alternatives' post}}. ByteChef is an open-source embedded iPaaS ({{repo, 190+ connectors, self-hosted}}) — likely relevant to your readers comparing options. Happy to provide a summary blurb + screenshots if useful."

**Partner DM (n8n ambassadors, LinkedIn, 5–10/week once §6.5 starts):**

> Hi {{Name}}, I've seen your work in the n8n community, and you clearly know automation deeply. I'm building ByteChef, an open-source automation platform with something n8n doesn't have: a proper white-label/embedded mode, so your clients get a workflow builder under your brand and domain. Pricing is transparent, so you know your cost before you set your client's price. Not pitching. I'm genuinely curious whether this fills a gap you've run into with clients.

---

## Appendix C — Source Notes (competitive pricing)

Assembled July 2026 from: Nango and Merge pricing-critique posts (Paragon, Prismatic), Albato comparison posts (Paragon vs Prismatic, Prismatic vs Tray), Paragon docs (task billing), Capterra/SaaSworthy/SoftwareSuggest/GetApp (Appmixer tiers), vendor pricing pages where reachable. Vendor sites block automated access; **manually re-verify any number before it appears in public comparison content.** Key figures: Paragon 5-figure annual floor, tasks + connected users; Prismatic ~$500/mo entry per-instance; Appmixer Starter $500/mo (10 users / 1,000 data messages), Professional from $1,300/mo; ByteChef overage $1/1,000 tasks.

---

## Appendix D — Case Study Template

**Length:** 500–800 words, one page. Use hard numbers wherever possible.

- **Headline:** How {{Customer}} {{achieved specific result}} with ByteChef.
- **One-line summary:** {{Customer}} used ByteChef to {{deliver X integrations / automate Y workflows / save Z engineering hours}}, which led to {{outcome}}.
- **About {{Customer}}** (2–3 sentences): what they do, their size, their product.
- **The challenge** (3–5 sentences): what their customers or teams asked for, what it cost them (engineering time, opportunity cost, churn), and why the alternatives failed (price, not embeddable, lock-in). Add one customer quote stating the pain.
- **The solution** (3–5 sentences): the deployment model (embedded, self-hosted, cloud), the key connectors and workflows, the time to go live, and the features that mattered (white-label, self-host, pricing, open source).
- **The results** (3–5 bullets): time to ship ("0 to X live integrations in Y weeks vs Z months in-house"), engineering hours saved per month, customer activation rate, cost vs the previous solution, and workflow success rate or support-ticket reduction. Add one customer quote stating the results.
- **What's next** (1–2 sentences), then the CTA: "Want results like {{Customer}}? Talk to us → {{link}}".

**Getting it from a pilot or design partner:**
1. Ask for 30 minutes: "We're writing up how the integration has gone. Can I ask a few questions? Happy to anonymize."
2. Ask these questions:
   - How did you handle this before, and what did that cost?
   - Why ByteChef instead of building in-house or buying an alternative?
   - How long did it take to go live, and what did that look like?
   - What's the measurable impact so far?
   - What would you tell another CTO considering this?
   - Can we use your name, or should we anonymize?
3. Draft it and send it for approval. If they want anonymity, use the "{{European Fintech Company}}" format. That's still credible.
4. **Minimum viable version:** if a full case study is a no, get a 1–2 sentence testimonial for the landing pages.

---

## Appendix E — Homepage Copy (Track A path)

Track A's half of the segmented homepage in §7.1 item 4. Appendix A is the embedded half. The audience is developers, ops engineers and technical founders who are hitting Zapier/Make limits.

**Hero:**
> ## Open-source workflow automation and AI agents, with no per-task fees when you self-host
> Build workflows, connect 190+ apps and run AI agents, self-hosted or in the cloud. Your automation bill shouldn't grow faster than your business.
>
> [Deploy free (self-hosted)] [Start on cloud]

**Social proof strip:** GitHub stars · 190+ connectors · open source · self-host or cloud.

**Problem block:** "Zapier charges you more as you grow. We don't."
- **Per-task pricing punishes growth.** Automation costs grow faster than the business.
- **Vendor lock-in.** Proprietary platforms own your workflows, and switching costs are real.
- **Not enough control.** You can't self-host, write real code or inspect the source. You're renting, not owning.

**Three pillars:**
- **Unmetered when self-hosted.** Cloud starts at $29/mo, with overage at $1 per 1,000 tasks (§5.1).
- **Open source.** Self-host for free, inspect every line, fork it if you need to.
- **Code when you need it.** The visual builder is for speed; code steps in Java, JavaScript, Python and Ruby cover what no-code can't.

**How it works ("Running in 5 minutes"):** Deploy with `docker compose up` or sign up for cloud → build in the visual editor with 190+ connectors and code steps → scale horizontally with the coordinator/worker architecture.

**Pricing block:** if it compares us with Zapier or Make, every competitor number must be manually verified on publish day (see the Appendix C caveat). Otherwise, show only our own §5.1 table.

**Final CTA:** "Stop paying the automation tax." [Self-host free (Docker)] [Start on cloud], with the GitHub link prominent.

**Implementation notes:** keep the page under 5 scroll-lengths on desktop and build it mobile-first (LinkedIn traffic is mostly mobile). Use product screenshots, architecture diagrams and code, not stock photos. Add a Cal.com booking widget. Track page views, CTA clicks, GitHub clicks, demo bookings and installs.
