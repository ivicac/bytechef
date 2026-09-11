# ByteChef inbound plan

Status as of September 14, 2026.

Tracked as one ticket since September 14: https://github.com/bytechefhq/bytechef-website/issues/52. Tick items there; this file is the snapshot at move time.

## Done

- [x] Comparison pages published: https://www.bytechef.io/compare (10 pages, fixes below)
- [x] Templates library published: https://www.bytechef.io/workflow-templates (fixes below)
- [x] Connector developer guide exists in docs (linking below)

## Sprint 1, by September 24

### Repo and README
- [x] Ship a single `docker run` command for local trial. PR #5686 merged September 11 (Ivica). Still to do: test on a clean machine and on Windows PowerShell. README part in PR #5738: command at the top of Quick Start with a PowerShell variant, untested on Windows. 5737 must merge after #5686.
- [x] README rewrite in one PR: new headline "Open-source AI agents and workflow automation", rewritten n8n FAQ and heading, scopes changed to Private / Workspace / Organization, Copilot marked 🚧 and Git-native marked EE, em dashes removed, stars badge, LinkedIn in the social row, star CTA at the bottom, video thumbnail of the existing YouTube demo instead of the "Live Demo" text link (swap for the new video when it lands), Discord named as the canonical community channel. Hardcoded remember-me key is handled by PR #5686. (Ivica) PR #5738, mergeable. Copilot kept as shipped, it is wired into the server app on master.
- [x] "Learn ByteChef by doing": make it run immediately on import (manual trigger or a clear "run it now" instruction). It fires at midnight today. (Matija) Switched to a manual trigger (empty triggers array, renders as "Manual" in the gallery) with the meta text updated to match. In bytechefhq/bytechef-workflows PR #58; the website needs a redeploy after it merges. Docs quick start updated on 5737 to match.
- [x] README: link the templates library right under Quick Start, pointing at "Learn ByteChef by doing" as the zero-credential first workflow. (Ivica) Done on 5737.
- [x] README and CONTRIBUTING: link the connector guide with the wording "Want a connector we don't have? Build it in an afternoon" plus a link to open connector requests. (Ivica) Done on 5737, requests link is the `workflow-component` label query (131 open). CONTRIBUTING's connector-request link had a broken label and is fixed.
- [x] Update the repo description: "Open-source AI agents and workflow automation. Self-host or embed in your SaaS. Apache 2.0 alternative to n8n and Zapier." Set the repo's website field. (Ivica) Done on GitHub, September 11.
- [x] Root LICENSE: name the exact EE paths (`server/ee/`, `client/src/ee/`) and point both to `server/ee/LICENSE`. (Matija) Done on 5737. Issue templates already existed under `.github/ISSUE_TEMPLATE`, eight forms including a connector request.

### Templates
- [ ] Ship the Ollama agent template, then two or three agent templates with tools and an approval step. (Matija) First agent template done as "Build your first agent" on OpenAI instead of Ollama (easier setup, per Ivica): OpenAI model + HTTP Client tool + Logger, manual trigger, in bytechef-workflows PR #58, imported and run green. Docs quick start "Build First Agent" added on 5737. Still open: two or three agent templates with tools and an approval step, with the approval as a workflow step after the agent (works today): GitHub issue triage, support email reply, refund request. Approval as an agent tool is blocked by #5056 (suspending tools do not suspend the agent or resume the LLM); once fixed, convert the support email reply template to it.
- [x] Remove "Delete Sheet" and "List Sheets". (Igor) Closed September 11: the two templates stay, per Ivica.
- [x] Fix: `execute-pyhton-finance` slug, lowercase "out of working hours", remove em dashes from Key Features. (Igor) In bytechef-workflows PR #58: folder renamed to `execute_python_finance` (website redirect from the old slug on the unpushed website master), em dashes removed from three meta.json files, title fixed to "Out of Working Hours Email Triage".
- [ ] Add `getting-started` to the template filter bar. (Igor) Not done as of September 14: the three onboarding templates carry `category: getting-started` in meta.json, but TEMPLATE_CATEGORIES on the website has no such entry, so they show under Other on both the live site and master.
- [x] Fix the mp4 video render on the template pages. (Igor) Fixed September 11, bytechef-website commit 86242ba on master (unpushed): all nine mp4 files had the moov atom at the end, so the player showed a blank 150px strip until the file tail was fetched, then jumped to full height. Files re-muxed with moov first and every video tag given its intrinsic width and height. Igor to confirm on his browser after deploy.

### SEO
- [x] Today: confirm all 277 integration URLs, all template URLs and all compare pages are in the sitemap, then submit it in Search Console. (Igor) Done September 11: `https://www.bytechef.io/sitemap.xml` (326 URLs, incl. 312 integration, template and compare pages) submitted to Search Console and read the same day, status Success, 326 pages discovered. Docs sitemap already there (545 pages). The 2022 `sitemap/sitemap-index.xml` entry still listed; it 404s, remove it when convenient.

### Announce
- [ ] Post the templates library and compare pages on LinkedIn and Discord this week. Feed them into the carousel series. (Ivica)

### Compare pages
- [x] n8n page: replace the "Requires manual guardrail design" row with mid-run resume and approvals plus audit in one governance layer. Add a durable execution row. (Ivica) Commit 73ba389 on bytechef-website, now on upstream/master. n8n-side claims verified against docs.n8n.io on September 11; the approvals cell corrected in e4ccacf to credit n8n's built-in send-and-wait approvals.
- [x] n8n page: change the Copilot conversion claim to match what works today, or mark it as coming. (Ivica, Matija confirms) Marked "coming soon" in commit c5485ad on bytechef-website master (differentiator, migration CTA), not pushed. Flip it back to a live claim once Matija confirms the converter end to end. The features page still says "See how ByteChef uses AI Copilot to convert n8n automations".
- [x] n8n page: rewrite "Choose ByteChef if" around Apache 2.0, self-host and embedded. Cut one readability bullet. (Ivica) Commit 73ba389 on bytechef-website, now on upstream/master.

## Sprint 2, by October 1

### Hacktoberfest
- [ ] Connector guide: have one outsider build a connector using only the guide and note where they got stuck. (Matija)
- [ ] Connector guide: add a PR checklist at the end: tests, docs, icon, categories, review flow. (Matija)
- [ ] Open and label 15 well-scoped connector requests as good first issue, each pointing at the guide. (Igor)
- [ ] Announce Hacktoberfest participation on LinkedIn and Discord. (Igor)

### README
- [ ] Record a 2-3 minute product overview video for the README top: what ByteChef is, an agent and a workflow running, self-host and embedded in one sentence each. Not a tutorial. (Ivica)
- [ ] Later: a separate getting-started walkthrough for the docs quick start and the "Learn ByteChef by doing" page. (Igor) The docs quick starts "Build First Workflow" and "Build First Agent" are rewritten on branch 5737 with 18 screenshots captured September 11; the video is still open.
- [ ] Add a "what's new" section to the README, updated every release, with each release posted on LinkedIn. (Igor)

### Templates
- [x] Make "Use template" a one-click import into app.bytechef.io and add a "Download JSON" link for self-hosters. (Matija) Done September 14: the "Use template" dialog offers "Import to ByteChef Cloud" (recommended) and "Copy template to clipboard (JSON)" with a note for self-hosters to paste it into their own instance. Shipped as a clipboard copy rather than a download link.

### SEO
- [x] Integrations: make category filters real URLs (`/integrations/category/crm`) with server-rendered cards, so every integration page gets an internal link. Done September 11, bytechef-website commit 3e5c6d6 on master (unpushed): 20 category pages at `/integrations/category/<name>`, paged with `?page=N`, sidebar and mobile select are links, category pages added to the sitemap. Needs push and deploy. (Matija)
- [x] Integrations: replace "Load more" with paginated URLs or render all 277 cards server-side, whichever is cheaper. Done September 11 as paginated URLs, bytechef-website commits bcd1ef8 and c6f84ab on master (unpushed): `/integrations?page=N` (12 pages of 24 since September 14, commit f698896) and `/integrations?category=crm&page=N`, template category pages take `?page=N`; each page has its own canonical and title, bad pages 404, search stays client-side. Needs push and deploy.
- [x] All compare pages: title pattern "ByteChef vs X: open-source workflow automation compared". Add "Apache 2.0 alternative" to the n8n and Activepieces meta descriptions. (Ivica) Done September 11, commit 73ba389 on upstream/master.
- [x] Retitle every template as a search query, e.g. "Classify Gmail emails with OpenAI and route to Slack". (Igor) Done September 11: 14 use-case templates retitled in bytechef-workflows PR #59 (meta.json name + workflow label, slugs unchanged); "Learn ByteChef by doing" and "Build your first agent" keep their names because README and docs link to them. Website commit 841d9ae lets card titles wrap to two lines. Needs PR merge, then website push and redeploy to pick up the new titles.

### Agent readiness (Is Agentic scans of September 11)
www.bytechef.io was at 61/100 and is fixed on the unpushed website master (real 404s, markdown negotiation, OpenAPI at /openapi.json, JSON API errors, homepage FAQ). Rescan after deploy. The two other domains, measured September 14:

- [x] blog.bytechef.io (76/100, https://is-agentic.com/scan/blog.bytechef.io): done September 14, bytechef-blog commit bf0202c merged and deployed; rescan the same day: 100/100 (essential 7 of 7, recommended 8 of 11). Left open by the scan: MCP manifest (not applicable), JSON-LD identity type on the home page, Organization schema with contactPoint and address. Markdown for every page via Accept: text/markdown, markdown 404 body, /llms.txt with a when-to-use section, recovery links on the HTML 404, an "about" section on the home page (content ratio 3.3% to 5.3% on a production build), 28 tests. Vary: Accept on HTML pages is still stripped by Next.js (same as www), and MCP does not apply to a blog. Merge to master, deploy, rescan. Original scope: give the 404 page a short markdown body pointing at the sitemap and llms.txt (the status is already 404); pass "content without JavaScript" with one clear H1 and sequential heading levels on the homepage (raw HTML already carries 1,816 characters of text, so the structure is what fails); serve markdown on `Accept: text/markdown` with `Vary: Accept, Accept-Encoding` (today it returns HTML); add `llms.txt` with a "when to use ByteChef" section (today 404). The MCP check does not apply to a blog, ignore it. (Ivica, bytechef-blog repo)
- [ ] docs.bytechef.io (59/100, https://is-agentic.com/scan/docs.bytechef.io): return a real 404 status for missing paths (today 200 with the app shell); server-render the docs homepage so raw HTML carries at least 500 characters of text with an H1 (today 14 characters); publish or link the OpenAPI specs at `/openapi.json` (the website serves them at www.bytechef.io/openapi.json, point the docs there or mirror them); return JSON errors on the docs API surface the scan found (API surface scored 2 of 8); add `Vary: Accept` on the markdown redirect (today a 307 to text/plain); extend the existing `llms.txt` with a "when to use" section. MCP manifest is optional, evaluate after the rest. (Ivica)

## October onward

- [ ] Templates: cadence of five a week, one owner. (Igor)
- [ ] Integrations: generate pairwise "X and Y integration" pages for the 200 most common pairs, each linking its matching templates. (Matija builds, Igor picks pairs)
- [ ] Integrations: list the templates that use each integration on its page. (Matija)
- [ ] Launch on Product Hunt once docker run, the agent templates and the video are live. (Ivica)
- [ ] Publish the pre-seed deck with the story behind it after the round closes. (Ivica)

## Track weekly

- [ ] Stars, Docker pulls, Discord joins, merged community PRs. (Igor)

Baseline, September 11, 2026: 1,001 stars, 170 forks, ~60 contributors, 6 good first issues. Kestra for reference: 28,072 stars, ~540 contributors, 47 good first issues.
