/**
 * The backtest of the adaptive engine (stage 2-4, ADR 0023): replays past mornings with the real signals and rules
 * code, using only the rows that existed by each morning, and stores the results under a run id, never in the live
 * tables.
 */
package app.training.adaptation.backtest;
