# Bake-off labelling rubric

A prompt is **COMPLEX** when a competent answer requires at least one of:

- multi-step reasoning whose intermediate steps are not stated in the prompt;
- synthesis across several distinct sources or constraints held simultaneously;
- generating a non-trivial artifact (a proof, a design, a program of more than a few lines);
- resolving genuine ambiguity in the request before answering.

A prompt is **SIMPLE** when a competent answer is:

- a lookup, a restatement, a translation, a format conversion, or a mechanical summary;
- a classification into stated categories;
- an extraction of values that are literally present in the input.

Length, presence of code fences, tool count and requested output size are explicitly **not** criteria.
They are the signals the baseline already uses, and the point of the corpus is to be able to disagree
with them.

Ties go to SIMPLE. If two labellers would plausibly disagree, the entry does not belong in
`adversarial.jsonl`; put it in `exemplars.jsonl` or drop it.
