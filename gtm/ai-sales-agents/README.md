# 5 AI sales agents in ByteChef

The five agents from Dragan Petric's "Beach bar, gradele i 5 AI agenata" newsletter, built as ByteChef workflows.

| # | Agent | Trigger | What it does |
|---|-------|---------|--------------|
| 1 | Prospector | Weekdays 09:00 | New leads from the sheet -> Apollo + Hunter enrichment -> AI picks channel and offer, writes the message -> Gmail / WhatsApp / LinkedIn (to you on Telegram) |
| 2 | Nurture | Weekdays 08:30 | Leads that are due -> next touch (follow-up, case study, post, check-in) -> schedules the one after. Stops after 5 touches |
| 3 | Negotiator | New Gmail email | Negotiates inside your price ranges. Every outgoing email waits for Approve / Reject on Telegram. Reject with a comment ("offer 1800") and the agent follows it. On a yes: booking link + row in Deals |
| 4 | Calendar and CRM | Calendly booking | HubSpot contact + closed-won deal, Google Calendar event with reminders 7 days / 1 day / 1 hour before, Telegram ping |
| 5 | Proposal generator | New row in Deals | Writes the proposal in your voice, fills your Google Docs template, Telegram approval, emails the client |

The agents hand off through one Google Sheet, which is also your pipeline view.

## Setup

1. **Google Sheet** with two tabs, first row = headers:
   - `Leads`: `name, email, company, domain, linkedinUrl, phone, status, channel, offer, touchCount, nextTouch, lastMessage, notes`
     New leads get `status = new`.
   - `Deals`: `email, name, company, engagement, details, price, status, date`
2. **Replace placeholders** in the JSON (search for `REPLACE`): spreadsheet ID, Telegram chat ID, Calendly link, proposal template Doc ID.
3. **Edit the prompt blocks**: OFFER (all agents), CONTENT LIBRARY (2), PRICE RANGES (3), MY PAST PROPOSALS (5).
4. **Proposal template**: a Google Doc with `{{client}} {{contact}} {{title}} {{intro}} {{scope}} {{duration}} {{deliverables}} {{price}} {{paymentTerms}} {{validUntil}}`.
5. **Import**: Project -> settings menu -> Import Workflow, one file per workflow. Connect: Google (Sheets, Gmail, Calendar, Docs, Drive), OpenAI, Apollo, Hunter, WhatsApp, Telegram, Calendly, HubSpot.

## Tool mapping vs. the article

| Article | ByteChef |
|---------|----------|
| Apollo | Apollo (enrich). Apollo search is not in the component, so leads come from the sheet |
| Clay | Hunter company enrichment |
| Make / Zapier | ByteChef itself |
| Instantly / Lemlist | Gmail + AI-written sequence (no open/click tracking; a reply hands the lead to agent 3) |
| ChatGPT API + CrewAI | AI Agent component with approval gate |
| Calendly, HubSpot | Calendly, HubSpot |
| PandaDoc + GPT-4 | Google Docs template + AI Agent + approval |

## Known gaps

- LinkedIn does not allow automated DMs, so LinkedIn-channel messages come to you on Telegram ready to paste.
- Agent 3 checks every unread email against the Leads sheet. Fine for a solo inbox; for a busy one, add a Gmail filter/label first.
- If Calendly already writes bookings into Google Calendar, turn that off for this event type or remove the Calendar step in agent 4 to avoid duplicates.
- Model is `gpt-5-mini` everywhere; swap the model cluster element for Anthropic or another provider if preferred.
