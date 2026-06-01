# The Geek Gathering 2026 — Talk Submission Draft

**Event:** The Geek Gathering, Osijek, October 2026
**Submission form:** https://thegeekgathering.org/submit-your-talk
**Call closes:** August 1st, 2026 (12:00 PM)
**Selection notified:** September 1st, 2026

---

## Speaker Information

| Field | Value |
|---|---|
| Full name | Ivica Čardić |
| E-mail | ivica.cardic@gmail.com |
| Phone | _(fill in with country code, e.g. +385…)_ |
| LinkedIn | https://www.linkedin.com/in/icardic/ |
| Portrait URL | _(paste link to a high-resolution headshot — Dropbox / Drive / Imgur)_ |
| Shirt size | _(XS / S / M / L / XL / XXL)_ |
| Company | Liferay |
| Position | _(your current title — e.g. Senior Software Engineer / ByteChef Founder)_ |
| Country | Croatia |
| City | Osijek |

### Short biography (≈ 75 words)

Ivica is a software engineer at Liferay and the creator of **ByteChef** — an open-source, low-code automation platform that recently grew an AI Copilot and started talking back. He's spent fifteen years building distributed Java systems and now spends most of his time teaching LLMs to behave themselves inside a workflow engine. He has previously spoken at DEVCON and the Hungarian Web Conference, where his AI agent enriched CRM data and beat humans at board games — sometimes in the same demo.

---

## Talk Details

**Session type:** Talk (45 min)
_Alternative: Masterclass (3 h) — see the "Masterclass variant" section at the bottom if you'd rather do a hands-on workshop._

### Topic title

**Seven Habits of Highly Effective Agents**
*Spring AI patterns we wish we'd had a year ago — a field report from rebuilding ByteChef's Copilot*

### Talk / Workshop summary (≈ 165 words)

Most "AI agent" demos collapse the moment you try to ship them. They forget the conversation, hallucinate tool calls, never ask a clarifying question, and have no plan when one LLM call isn't enough. The agents that *do* survive production share the same seven habits — and we just rebuilt ByteChef's AI Copilot on Spring AI's new agentic stack to prove it.

This is a field report from a real open-source Java codebase. We'll walk through each habit with running code:

1. **Load knowledge on demand** — Agent Skills
2. **Ask before assuming** — AskUserQuestionTool
3. **Write down the plan** — TodoWriteTool
4. **Delegate to specialists** — Subagent Orchestration
5. **Talk to other agents** — A2A Integration
6. **Remember what matters forever** — AutoMemoryTools
7. **Forget gracefully** — the new Session API

Live demo: ByteChef's Copilot building a workflow end-to-end, hot-swapping between OpenAI, Anthropic, and Gemini. Plus an honest accounting of where the abstractions leak.

If you've ever shipped an agent that lied to a customer, this talk is for you.

### What attendees will learn

- A mental model for the seven Spring AI agentic patterns and when each one earns its keep
- How to compose `ChatClient`, advisors, and tools so agents stay coherent across long sessions
- Concrete techniques for cost control, multi-model routing, and turn-safe context compaction
- The honest trade-offs between Spring AI's `ChatMemory`, the new Session API, and file-based long-term memory
- Patterns for embedding an agent inside an existing workflow engine without turning it into a chatbot

### Audience level

Intermediate. Comfort with Java and Spring Boot is assumed; no prior Spring AI or LLM experience required.

### Special requirements

- Stable internet connection for live calls to OpenAI / Anthropic / Gemini APIs
- HDMI / DisplayPort with mirror to confidence monitor
- _(slides + demo run on TGG-provided device per the rules — code repository will be public so the demo is reproducible from the venue laptop)_

---

## Source material referenced in the talk

The Spring AI Agentic Patterns blog series by Christian Tzolov (Jan – Apr 2026):

- Part 1 — [Agent Skills](https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills)
- Part 2 — [AskUserQuestionTool](https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool)
- Part 3 — [TodoWriteTool](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-3-todowrite/)
- Part 4 — [Subagent Orchestration](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents)
- Part 5 — [A2A Integration](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration)
- Part 6 — [AutoMemoryTools](https://spring.io/blog/2026/04/07/spring-ai-agentic-patterns-6-memory-tools)
- Part 7 — [Session API](https://spring.io/blog/2026/04/15/spring-ai-session-management)

ByteChef references:

- Project: https://github.com/bytechef-ai/bytechef
- AI Hub / Copilot modules: `server/ee/apps/ai-copilot-app`, `automation-ai`, `platform-ai`

---

## Masterclass variant (optional, 3 h)

If you'd prefer to submit this as a **Masterclass (3 h)** instead of a 45-minute talk, here is the same content reframed:

**Title:** *Building Production AI Agents in Java — A Hands-On Spring AI + ByteChef Masterclass*

**Summary:** A hands-on 3-hour workshop where attendees build a working multi-step AI agent in Spring Boot, then progressively layer on each of the seven Spring AI agentic patterns — Agent Skills, AskUserQuestion, TodoWrite, Subagents, A2A, AutoMemory, and the Session API. By the end, each attendee leaves with an agent that plans, asks clarifying questions, delegates to specialized subagents, and remembers facts across restarts. We use ByteChef's open-source codebase as a reference implementation for the production hardening choices (multi-model routing, cost guardrails, turn-safe compaction, branch isolation for parallel subagents). Bring a laptop with Java 17+ and Docker; an OpenAI or Anthropic API key is recommended but a local Ollama option will also be supported.

---

## Submission checklist

- [ ] Confirm shirt size and current job title
- [ ] Add phone number with country code
- [ ] Upload a high-quality portrait photo and paste the link
- [ ] Pick a final title from the four options
- [ ] Decide: 45-minute talk **or** 3-hour masterclass (you can submit both via "+ ADD SPEAKER" / separate submission)
- [ ] Tick both consent checkboxes (data collection + privacy policy)
- [ ] Submit before **August 1st, 2026 12:00 PM**
