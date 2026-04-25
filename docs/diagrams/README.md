# Blog Post Diagrams

Diagram sources for the Medium/dev.to blog post.

## How to render

### Mermaid diagrams
- Paste into [mermaid.live](https://mermaid.live) → export as PNG/SVG
- Or use the Mermaid CLI: `mmdc -i file.mmd -o file.png -t dark -b transparent`

### Excalidraw diagrams
- Open [excalidraw.com](https://excalidraw.com) → Import → paste JSON
- Export as PNG (2x resolution recommended)

## Files

| File | Description | Suggested placement in blog |
|------|-------------|----------------------------|
| `01-chunk-checkpoint-flow.mmd` | Core concept: chunks → checkpoints → pause/resume | After "The Pattern: Chunk-Based Execution" heading |
| `02-before-after-system.mmd` | Before/After OOM timeline (the money shot) | After "What This Looked Like for Us" heading |
| `03-hybrid-architecture.mmd` | Sequence diagram: pause/resume flow | After "The Hybrid Pause/Resume Architecture" heading |
| `04-hysteresis.mmd` | Hysteresis thresholds visualization | After "Hysteresis: Preventing Flapping" heading |
| `05-comparison-table.md` | Styled comparison table (render as image) | Replace the markdown table in "But Can't I Just Use...?" |
