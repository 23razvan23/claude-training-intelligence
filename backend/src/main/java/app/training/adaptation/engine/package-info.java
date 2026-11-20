/**
 * The adaptive engine's rules (stage 2-3, SPEC.md Module 3, ADR 0022): a deterministic, versioned rules engine that each
 * morning reviews the next 7 days of planned sessions against the day's signals (ADR 0021) and proposes changes with
 * reasons, which the coach accepts, edits or rejects. The rules themselves are pure ({@link app.training.adaptation.engine.RulesEngine});
 * {@link app.training.adaptation.engine.SuggestionService} reads the inputs and stores the suggestions.
 */
package app.training.adaptation.engine;
