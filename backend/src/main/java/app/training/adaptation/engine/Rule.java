package app.training.adaptation.engine;

import app.training.adaptation.engine.Model.Proposal;
import java.util.List;

/** One SPEC.md starting rule. Pure: it reads the run's context and proposes changes; the guardrails come after. */
interface Rule {
    /** Short stable name, stored with each suggestion ({@code inputs_snapshot.rule}). */
    String id();

    List<Proposal> apply(Context c);
}
