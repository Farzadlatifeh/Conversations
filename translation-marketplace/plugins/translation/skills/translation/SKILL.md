---
name: translation
description: "Translate, localize, back-translate, or review translations of general, technical, scientific, and document content. Use when the user explicitly selects Translation, asks to translate between languages, requests high-quality translation review, needs terminology consistency, or needs preservation of numbers, units, citations, equations, tables, captions, footnotes, and mixed RTL/LTR text."
---

# Translation

Translate accurately while preserving meaning, document structure, technical content, and relationships between elements.

## Workflow

1. Determine source and target languages from the request and context.
2. Preserve headings, paragraphs, lists, tables, captions, footnotes, references, equations, numbering, citations, symbols, units, identifiers, URLs, DOIs, filenames, and model numbers.
3. Translate meaning rather than source-language word order.
4. Use established technical and scientific terminology consistently.
5. Do not summarize, omit, expand, or add factual claims unless explicitly requested.
6. For Persian output, use fluent contemporary Persian while preserving Latin scientific symbols, formulas, standards, acronyms, identifiers, URLs, and DOIs.
7. Keep mathematical expressions LTR even when surrounding prose is RTL.
8. Avoid unnecessary Unicode direction-control characters in mixed RTL/LTR text.
9. When high-quality translation or `--hq` is requested, compare the completed translation against the source and correct omissions, duplicated text, altered numbers or units, terminology inconsistencies, citation errors, and RTL/LTR ordering defects before returning it.

## Legacy prompt compatibility

Interpret these legacy options when they appear in the user's request:
- `--hq`: perform the high-quality review pass.
- `--from <language>`: source language.
- `--to <language>`: target language.

Do not depend on `@tr`, `/tr`, Claude-specific commands, Claude model names, or `.claude` configuration for discovery. The canonical skill name is `translation` and the user-facing display name is `Translation`.
