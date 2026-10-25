# Dataset preprocessing and single-input detection

## Repository contents and local prerequisites

The current project uses `sqlia_backend` and `sqlia_frontend`, replacing the
legacy `sqlia_app` and `sqlia_web` directories. Source code, tests, schema,
visualization scripts and application assets are versioned.

Datasets (`data/raw`, `data/processed`), experiment reports and model checkpoints
(`data/experiments`), runtime state (`data/runtime`), local databases (`db`),
third-party JARs (`lib`), build outputs, caches, logs, local IDE settings and
backups are not included. Keep these resources locally; a fresh clone does not
contain a ready-to-run dataset or trained models.

Prepare the backend database using `sqlia_backend/SQLIA_schema.sql` and populate
the required Training and Validation data before starting the website. Performance
comparisons additionally require `sqlia_backend/data/processed/test.csv`.
The external generalization and ablation pages require separately generated
research reports; missing reports are not bundled with the source.
Install the SPMF dependency locally using the instructions below. Maven uses
Java 21; restore frontend packages with `flutter pub get`.

## Deployed backend and frontend

Start from `sqlia_backend` using the dedicated entry point, never the importing
default `Main`:

```sh
mvn compile exec:java@api
```

Website and research are separate workflows. The website opens the existing dataset
read-only, mines rules from Training and warms up its interactive classifiers with
the original 2,000-row limit. It does not import data, load experiment checkpoints,
or open a feedback journal at startup. After mining Training rules, it calibrates
only the Pure ARM decision threshold on Validation (maximum F1, then minimum FPR).
Missing, single-class, invalid or Training-overlapping Validation fails startup
explicitly. Test is never read for calibration.

- The original five dashboard functions remain: architecture diagrams, single-input
  comparison, rule analysis, performance comparison and replay. A separate external
  generalization entry displays saved research reports, not live website predictions.
- Detection, verified feedback, monitoring and replay share the current in-memory
  website models. Verified feedback and enabled replay updates affect later website
  predictions. Restarting rebuilds this state from Training; updates are session-local.
- ML/profile thresholds retain their original default 0.5; Pure ARM uses its
  Validation-calibrated threshold, visible in status, predictions and comparisons.
  The performance endpoints use the current state without retraining or updates and
  return `evaluationMode=interactive_website_snapshot`. They are not final research
  metrics, especially after feedback or replay.
- The performance and replay pages select current `data/processed/test.csv`, never
  obsolete `test_holdout.csv`, whose overlap with Training invalidates it for evaluation.
  Existing overlap/label/input validation remains enabled.
- `/api/deployment` and `/api/research/results` are no longer website endpoints.
  Optional checkpoint-deployment classes and old journals are retained but not loaded.
  Existing research models, reports and journals have not been deleted or overwritten.

Run the frontend from `sqlia_frontend`:

```sh
flutter run -d chrome
```

Its default API URL is `http://localhost:8080`. For an isolated local validation
instance, use backend `-Dsqlia.port=18080` and frontend
`--dart-define=API_BASE_URL=http://localhost:18080`.
The original pages and five-architecture options remain. No saved experiment path
is required in the UI. For formal results use the independent command-line research
runners below, not the mutable website session.

Dataset selectors display `Test dataset` and still use `data/processed/test.csv`.
The five architecture diagrams separate startup setup from the per-input prediction
flow. Solid downward arrows show processing order; online feedback is described
separately and never implies that rules are re-mined on each request.

Validation commands:

```sh
cd sqlia_backend
mvn test
cd ../sqlia_frontend
flutter test test/widget_test.dart test/frozen_performance_test.dart test/single_input_test.dart
flutter build web
```

On this external macOS volume, resource-fork `._*` entries can be misdetected as
test code. Maven excludes those entries; the Flutter command explicitly lists all
actual test files to avoid decoding binary resource forks as Dart source.

## External generalization: isolation and reporting

The external workflow is separate from website detection, feedback, replay and the
database-matching frozen Test protocol. The dashboard's **External generalization**
page reads saved reports; opening it never trains a model or evaluates a dataset.
It must show missing, blocked, running and failed report states explicitly.

The baseline was preserved in `research_backups/baseline-20261025-1322/`, including
a SQLite backup-API snapshot, source/settings, datasets, checkpoints, results and
a SHA-256 manifest. Existing data and research results are not overwritten.

`sqlia_backend/data/raw/clean_sql_dataset.csv` is an external candidate, not an
additional default training source. The user supplied
[Biggest SQL Injection Dataset](https://www.kaggle.com/datasets/gambleryu/biggest-sql-injection-dataset)
as its provenance and defined `0=benign`, `1=malicious`. The public Kaggle metadata
identifies version 1 and creator Gambler yu but does not establish independently
collected inputs; the displayed license is unknown. Do not redistribute this dataset
as a project deliverable or claim independent collection from the filename alone.

Before external scoring:

1. Audit CSV structure, labels, conflicting labels, duplicates, and overlaps against
   **all original sources**, including rows excluded from the existing processed data.
   Preserve row-level exclusion reasons and hashes without changing the raw CSV.
2. Perform development comparisons using old Training/Validation only. Same-Training
   supervised rule selection is not automatically Test leakage. A disjoint
   rule-building/classifier-training comparison is a diagnostic with sample-size
   confounding, not an assumed mandatory bug fix.
3. Freeze checkpoints, rule schemas, thresholds, selection policy, exclusions,
   score-based Average Precision and group-bootstrap settings before external scoring.
4. Evaluate the same retained external rows without retraining, feedback updates,
   threshold tuning or a fallback to fresh training. Preserve the first result,
   including failures, instead of overwriting it.

Reports distinguish an overlap-filtered external evaluation from a verified independent
blind test. Known overlap removal is not proof that unknown templates or collection
sources are independent. Once external results guide development, the dataset is no
longer an untouched blind test for the revised model.

Metrics include confusion counts, FPR and Average Precision (tied scores processed
together, not trapezoidal PR area), plus group-bootstrap intervals and their valid/
undefined replicate counts. Differences in attack prevalence affect precision, F1
and AP; do not describe a raw difference from the old Test as purely model degradation.
An external fixed-model test also does not establish the value of online updates.

The [detailed architecture document](sqlia_backend/docs/pure_arm_incremental_architecture.md)
separates interactive learning from fixed external evaluation.

The code-aligned documentation diagrams cover the
[project overview](sqlia_backend/docs/project_system_overview.svg),
[five-path prediction and Pure/Hybrid comparison](sqlia_backend/docs/single_sql_prediction_flow.svg),
[training/evaluation boundaries](sqlia_backend/docs/dataset_architecture_comparison_flow.svg),
and [research-only rule ablations](sqlia_backend/docs/rule_ablation_flow.svg).
They distinguish website warmup from full-Training research models and defense
recommendations from actual database-query interception. These documentation
diagrams do not replace the frontend's separate architecture assets.

### Executed external experiment: `generalization-20261025-1322`

The new immutable outputs are under
`sqlia_backend/data/experiments/generalization-20261025-1322/`. The audit counted
**148,326 CSV records** (not physical lines), retained **101,289** records
(48,879 benign; 52,410 malicious) in **97,323 heuristic template groups**, and
excluded **47,037** records. Exclusion reasons overlap: 32,612 records matched
old exact text, 42,729 matched old normalized text and 47,035 matched old template
keys. There were also two blank payloads and conflicting-label exclusions.
Do not add individual reason counts to obtain the total excluded count.

Development compared same-Training, disjoint A-rules/B-classifier, and B-rules/
B-classifier sample-size-control policies with two seeds. Original Training and
Validation contain 690 shared heuristic template groups, disclosed rather than
presented as independent template holdouts. The original policy won the
prespecified Validation selection for all five architectures; disjoint Training
was implemented as a diagnostic, not silently substituted into website models.
The dedicated source/template holdouts use stricter template isolation. SQLiV3's
remaining source-exclusive holdout has only 14 rows, so that result is unstable.
The new development runs did not read or re-evaluate the old final Test.

| New diagnostic holdout | Rows | SQLi rows | Hybrid F1 | Pure F1 |
|---|---:|---:|---:|---:|
| Modified_SQL_Dataset, strict source/template exclusion | 579 | 7 | 70.00% | 57.14% |
| sqli, strict source/template exclusion | 2,876 | 86 | 45.12% | 39.44% |
| SQLiV3, strict source/template exclusion | 14 | 13 | 60.00% | 35.29% |
| Template-group holdout | 6,742 | 2,104 | 93.26% | 97.18% |

These new diagnostic protocols differ from the earlier normalized-only source
holdouts documented below. Their exclusion counts and class distributions differ;
do not overwrite or combine the earlier results. SQLiV3 has only one benign row
in this stricter evaluation, making FPR particularly uninformative.

Separate post-freeze diagnostics saved in `backend-diagnostic-summary.json` include
six old-Training-only learning fits (25%, 50%, 100% nested template-group fractions,
two seeds), resubstitution Training metrics and old Validation score calibration.
Group fractions are not row fractions: quarter-group fits used 8,079 and 5,052 rows;
half-group fits used 13,108 and 10,178 rows. Hybrid Validation F1 ranged from 95.75%
to 98.31%, without a monotonic learning curve. This indicates composition/seed
sensitivity, not proof of underfitting or a robust advantage for disjoint Training.
These descriptive diagnostics did not alter the frozen model selection or trigger
another external evaluation. No speculative feature/classifier modification was
promoted into the live website.

The first frozen external evaluation completed without retraining, online updates
or threshold retuning:

| Architecture | Accuracy | Precision | Recall | F1 | FPR | Average Precision |
|---|---:|---:|---:|---:|---:|---:|
| ARM_PROFILE | 75.87% | 71.91% | 87.60% | 78.98% | 36.70% | 87.55% |
| RATIO_INCREMENTAL_ML | 67.25% | 63.90% | 84.37% | 72.72% | 51.12% | 75.98% |
| FULL_FLOW | 65.97% | 61.06% | 94.48% | 74.18% | 64.60% | 81.91% |
| EXTENDED_ARM | 61.03% | 57.12% | 98.98% | 72.44% | 79.67% | 57.10% |
| PURE_EXTENDED_ARM | 61.67% | 57.54% | 98.91% | 72.76% | 78.26% | 60.22% |

The 200-replicate template-group bootstrap F1 intervals are approximately
72.01–72.96% for Hybrid and 72.32–73.27% for Pure. These are sampling intervals,
not guarantees against label bias or distribution shift. Both models have high
recall but severe false-positive rates on this retained external dataset. High
same-source Test results therefore do not establish generalization. Do not tune
on these external outcomes and continue calling the dataset untouched.

The new Hybrid checkpoint is not identical to the older final-Test checkpoint;
its frozen threshold is `0.9999999999999896`, selected on old Validation. Historical
scores must retain their own model and protocol identifiers rather than being
combined into a same-checkpoint improvement claim.

### Post-evaluation error diagnosis

With explicit permission, external labels have now been used for development
diagnosis, not model updates. The original report, checkpoints and thresholds
remain unchanged. Revised models will require a new untouched holdout.

The [error-analysis report](sqlia_backend/docs/external_generalization_error_analysis.md)
documents a concrete failure: the high-confidence rule requiring AND but neither
WHERE nor a semicolon matches 33,139 external benign rows, covering 86.63% of
Pure's false positives. Ordinary English can satisfy that rule. Overlapping
coverage is not causal feature importance, and removing one rule does not
necessarily remove all those false positives.

The separate `external-errors` Maven execution verifies input hashes and requires
rescored confusion counts to equal the original report. It saves diagnostics in
a new directory, never retrains or retunes, and does not overwrite completed
evaluation artifacts. See the report for commands and improvement priorities.

### Isolated rule-policy ablations

The research-only `rule-ablation` execution compares the unchanged rule schema,
removing negative-item rules, and requiring stronger positive SQL context. It
filters before fitting Hybrid, retrains with two fixed seeds, chooses thresholds
only on old Validation, freezes all six checkpoints, then scores the previously
disclosed external data as development diagnostics. It never promotes a model;
website defaults remain unchanged.

The [executed comparison and tradeoffs](sqlia_backend/docs/rule_ablation_results.md)
show that removing negative rules can sharply reduce external false positives,
but increases false negatives. Stricter filtering is not automatically better.
These development results are not a replacement for a new independent holdout.

The dashboard's **External generalization** entry now has two separate tabs:
**Frozen evaluation** retains the original external snapshots; **Rule ablation**
lists saved development experiments. Open an experiment and select Pure ARM or
Hybrid to compare all three policies and both seeds, six metrics, FPR/Recall bars,
false-positive/false-negative changes, confusion counts, 95% intervals, exact
Validation-selected thresholds and checkpoint/input provenance. Small nonzero FPR
values are not displayed as zero. Downloads preserve the saved experiment JSON.
The page explicitly labels results **Research only — not deployed**.

Read-only API routes:

- `GET /api/experiments/rule-ablation/reports`
- `GET /api/experiments/rule-ablation/reports/{id}`
- `GET /api/experiments/rule-ablation/reports/{id}/download`

Only one-level `rule-ablation-*` folders are read. Completed reports must have the
declared policy/seed matrix, both architectures, required metrics and intervals,
matching Validation/external thresholds, provenance hashes and a non-deployment
policy. Incomplete reports show their status, not fabricated metrics. Invalid
reports return explicit errors. No route starts training, loads a checkpoint or
updates detection. Restart the backend after rebuilding to register the routes;
refresh the updated frontend to see the new tab.

### ROC-AUC and ranking-derived reports

Ranking evaluation adds tie-aware ROC-AUC (half credit for tied positive/negative
pairs), alongside the existing Average Precision (AP). AP is not trapezoidal
PR-AUC. AUC is undefined without both classes; undefined values are never zero-filled.
Template-cluster bootstrap supplies marginal 95% intervals and explicitly counts
undefined replicates. Neither AUC nor AP establishes probability calibration.

The external and ablation detail pages display ROC and Precision–Recall curves,
with an orange marker at the unchanged Validation-selected threshold. Ablation
tables and external comparison bars also include ROC-AUC when available. At most
401 whole-score-group points are shown; metrics and intervals use all scores,
not integration of the display polyline. The initial PR precision of 1 is a
plotting convention when nothing is predicted positive.

Historical JSON remains unchanged. Open a `generalization-ranking-*` or
`rule-ablation-ranking-*` report for the added evaluation; old reports explicitly
show that ranking curves are unavailable. Downloads include the saved derived
results. `rankingDerivation` records the source run, verified input/checkpoint
hashes and the source directory to which checkpoint paths refer. Derived runs
are report-only: they are not self-contained train/evaluate input directories.

To derive new reports from completed checkpoints, use **new sibling directories**:

```sh
cd sqlia_backend
mvn compile
mvn -q exec:java@ranking-evaluation \
  -Dexec.args="data/experiments/generalization-20261025-1322 data/experiments/rule-ablation-20261025-1427 data/experiments/generalization-ranking-NEW data/experiments/rule-ablation-ranking-NEW"
```

The runner verifies input hashes before and after scoring and reproduces all
historical overall metrics/counts before adding ROC-AUC, its interval and curves.
It does not retrain, update, select thresholds, or deploy models. Previously
disclosed external data remains development/retrospective evidence, not a new
independent blind test. New `GeneralizationMetrics.report` outputs include these
ranking fields automatically; other legacy comparison endpoints are unchanged.

Completed derived outputs:
[external ranking report](sqlia_backend/data/experiments/generalization-ranking-20261025-1554/external-report.json)
and [rule-ablation ranking report](sqlia_backend/data/experiments/rule-ablation-ranking-20261025-1554/ablation-report.json).
All 17 scoring results reproduced historical overall metrics and confusion counts.
Input reports, manifests, external export and checkpoints passed before/after
hash verification. Validation included 30 focused backend tests, 26 Flutter tests
(including the actual saved reports), targeted Dart analysis and a release web build.

Stage commands below are reproducible entry points, **not instructions to rerun
this already completed external evaluation**. Use a fresh run directory for new
development; do not overwrite the completed artifacts:

```sh
cd sqlia_backend
mvn compile
mvn -q exec:java@generalization -Dexec.args="audit data/experiments/generalization-NEW"
mvn -q exec:java@generalization -Dexec.args="develop data/experiments/generalization-NEW"
# Only after audit and frozen-manifest verification:
mvn -q exec:java@generalization -Dexec.args="evaluate data/experiments/generalization-NEW"
```

The website reads reports through `GET /api/external/reports`,
`GET /api/external/reports/{id}` and
`GET /api/external/reports/{id}/download`. These routes never evaluate a model.
Reports with unverified independent collection are labeled retrospective/external
overlap-filtered, not independent blind tests.

Focused regression commands:

```sh
cd sqlia_backend
mvn -q -Dtest=GeneralizationPipelineTest,ExternalReportServiceTest,FeaturePipelineTest,WebsitePureArmCalibrationTest,WebsiteComparisonServiceTest,IncrementalClassifierTest,SavedCheckpointEvaluationTest,FrozenCheckpointTest,FrozenArchitectureComparisonTest,EvaluationDatasetSupportTest,ResearchExperimentServiceTest test
cd ../sqlia_frontend
flutter test test/external_report_test.dart test/widget_test.dart
flutter analyze
flutter build web
```

These tests validate the new metrics and isolation contracts, saved-report schema,
HTTP routes and UI behavior. They are not additional external evaluation passes.
The integration validation is recorded in the run's `parent-validation.json`:
44 backend regression tests and 16 sequential Flutter regression tests passed.
After browser validation caught an asynchronous `setState` refresh callback,
both refresh handlers were corrected; the final focused UI run passed 10 tests
including refresh/retry and the non-web JSON fallback. Targeted Dart analysis and
the final release web build passed.
Separate report-service tests exercised real Javalin HTTP routes and the completed
report schema. Flutter hot restart/reload succeeded, and browser smoke verified
navigation, result bars, refresh and the JSON download invocation using a temporary
fixture serving the exact saved report. The production `ApiServer` debug launch
timed out without confirming startup; it was not retried through a terminal.
Therefore browser-plus-production-server integration is not claimed. The integrated
browser did not expose a download event, so native downloaded-file persistence was
not independently verified even though the Blob/filename invocation and download
endpoint were confirmed.

## Frozen five-architecture comparison

### Independent research runs

`data/experiments/research-20261025/research_results.json` records a separate
Validation-only search and three cross-source experiments. Launch the dedicated
`mvn compile exec:java@research` from the backend for a new research run; the current
runner intentionally refuses to overwrite that report or its saved artifacts.
The separate `artifact_verification.json` records SHA-256 hashes and exact
round-trip score equality for all 20 selected checkpoints across five architectures
and seven fixed synthetic inputs. Reproduce that non-evaluation check using
`mvn compile exec:java@research-verify`; it refuses to overwrite its report.
This verifies serialization consistency, not accuracy or a second held-out evaluation.

The prespecified finite grid contains four configurations:

1. Baseline: 15 ensemble members, standard support 0.05/confidence 0.6,
   extended support 0.1/confidence 0.6/lift 1.5/max length 3/max 25 rules per family.
2. Small ensemble: 5 members, otherwise baseline.
3. High-confidence rules: standard and extended minimum confidence 0.8,
   otherwise baseline.
4. Short extended rules: maximum length 2, otherwise baseline.

Every configuration uses all available Training rows. Each architecture independently
chooses its configuration and threshold by Validation F1, then minimum FPR, then grid
order. This is **not an exhaustive hyperparameter search** and does not tune every
possible support/seed/learner/feature choice. Merged research uses only the existing
Training and Validation CSVs and does not load or evaluate the existing Test partition.
Its selected artifacts are separate research outputs, not replacements for the frozen
deployment or the original final-Test experiment.

Training-order provenance also differs: the original frozen run inserts Training
in database snapshot text/source order, whereas research uses `train.csv` order.
Both apply the same seeded shuffle afterwards, but different initial orders still
produce different update sequences. Thus even the research configuration named
`baseline` is a separate fitted model; its Validation scores must not be substituted
into the original Test report. A controlled reproduction would need to fix the
initial ordering as well as settings/seeds. No held-out rerun was used to resolve
this distinction.

For each held-out source, exact shared-provenance examples remain on the non-held-out
side and are excluded from held-out evaluation. Held-out-only normalized variants
overlapping non-held-out groups are also excluded. Non-held-out data is split 85/15
into group-aware Training/Validation with seed 20261025; all configuration and threshold
selection finishes before one evaluation pass per architecture on held-out rows.
The report records source names, retained/excluded counts, fingerprints, class counts,
Validation trials and held-out confusion metrics. Source names `Modified_SQL_Dataset`,
`sqlia` (the sqli CSV), and `SQLiV3` are provenance identifiers, not model features.

#### Provenance note: research-runner scores vs. the older full-training report

The older full-training/Validation report and the newer research run use the same
24,943 Training and 5,344 Validation row counts and record the same defaults:
ensemble size 15; standard rule support/confidence 0.05/0.6; extended rule
support/confidence 0.1/0.6, lift 1.5, maximum length 3; and the same recorded
seeds. These matching counts and settings do **not** establish that both runs
fitted identical models.

The newer research runner reads `train.csv` in splitter-export order. The earlier
`FrozenArchitectureComparison` snapshot instead selected rows in
`raw_sql, source, split` SQL order, then inserted them as `batch_seq`/`id`.
`ModelStateService` applies its deterministic shuffle seed `20261003` to the
input sequence and then performs sequential `OnlineBagging` updates. Standard
and extended rule mining also read rows with `ORDER BY id`; moreover,
`ExtendedRuleMiner`'s greedy coverage selection can preserve ties according to
candidate order. Since the initial sequences differ, the same seed and
hyperparameters need not produce the same fitted ensemble or rule set.

This provenance difference is consistent with, but does not by itself explain,
the observed score changes. The new baseline `EXTENDED_ARM` Validation F1 is
`0.983410`, versus `0.974166` in the older report. The new `small_ensemble`
(5 members) trial happens to score `0.974166`, but the old report confirms
ensemble size 15; this numerical match is not evidence that the models or
trials are equivalent. For `FULL_FLOW`, the older score is `0.914666`, while
the new baseline (15 members) is `0.912392` and the new 5-member trial is
`0.913587`; no new trial reproduces the old score exactly.

The threshold selector is unchanged, and this difference is not attributed to
Validation policy or merged-Test leakage. Without a controlled reproduction
using only Training and Validation with explicitly matched input order and
artifacts, the exact metric cause cannot be isolated beyond this
order-sensitive training/mining provenance. Do not rerun or inspect held-out
Test to investigate these Validation differences.

The older full-training checkpoint was reloaded and verified against every
Validation row; its `training_validation.json` includes `checkpointSha256`.
The research-runner `.ser` artifacts were **not** reloaded to verify the
research scores, and the research report has no artifact SHA-256 manifest.
Treat those as artifact-verification limitations when interpreting or
reproducing the research results.

The three completed held-out partitions contain 669, 3,145 and 664 rows respectively.
They contain only **7, 155 and 16 SQLi examples**, so the first and third are explicitly
flagged `identifiable_sparse_minority`. Large overlap exclusions mean these are
evaluations of each source's remaining distinct inputs, not the full original sources.
Do not interpret high accuracy alone as strong generalization evidence; report F1,
Recall, FPR and confusion counts with these limitations. These are retrospective
experiments on datasets already used in development, not a newly collected blind
external benchmark.

Held-out SQLi F1 from this independent finite-grid run:

| Architecture | Modified_SQL_Dataset | sqlia | SQLiV3 |
|---|---:|---:|---:|
| ARM_PROFILE | 71.43% | 76.69% | 58.33% |
| RATIO_INCREMENTAL_ML | 80.00% | 51.67% | 38.10% |
| FULL_FLOW | 83.33% | 35.46% | 52.17% |
| EXTENDED_ARM | 80.00% | 44.39% | 85.71% |
| PURE_EXTENDED_ARM | 85.71% | 42.74% | 85.71% |

These materially lower cross-source scores show why the merged final-Test result
must not be presented as proof of generalization to new sources.

The standalone `FrozenArchitectureComparisonService` implements the research protocol,
not the website's performance endpoints. Research uses isolated experiment models,
not the mutable interactive feedback models. Its `compare` method can load a saved
experiment with `checkpointDirectory` or perform full training and test evaluation.
The website endpoints reject checkpoint requests and never call this research service.
Each fresh-training request:

1. Captures and validates a database snapshot: binary labels, unique exact text,
   both labels in each partition, and no similarity groups crossing partitions.
2. Requires a UTF-8 test CSV with explicit split/source metadata that exactly
   matches the entire current database test partition. Old or mismatched CSVs
   fail before training. The website separately selects `data/processed/test.csv`,
   not the old `test_holdout.csv`. A positive sample size can select a reproducible subset
   after the full test file has been verified; report the actual evaluated count.
3. Copies **only all Training rows** into an in-memory database and builds a new
   `ModelStateService` with no 2,000-row cap. Rules/profile/classifiers are fitted
   from Training only. Website feedback never changes these frozen experiments.
4. Scores Validation for each selected architecture and chooses its threshold
   by maximum SQLi F1, then minimum benign FPR. Equal-score observations are never
   split; remaining ties retain the first (lowest) candidate threshold. The score
   is an architecture score, not necessarily a calibrated probability.
5. Freezes the models and selected thresholds, then evaluates the same shuffled
   Test subset for every architecture. No feedback, profile update, or model update
   is performed. Requests with `updateModel=true` are rejected.

ARM_PROFILE and FULL_FLOW share a read-only profile within one frozen experiment,
which is safe because updates are unavailable in that path. Each new comparison
builds a new state. Replay/feedback and incremental-algorithm comparisons remain
separate experiments and must not be reported as this full-training protocol.

The response preserves aggregate fields and adds confusion counts, FPR, per-source
combination/type metrics, training/validation sizes, selected threshold, validation
F1/FPR, training duration, protocol/version/seed/settings, and dataset/test SHA-256
fingerprints. The dataset fingerprint covers text, label, source, and split, not
timestamps or database IDs; input-type metadata is recomputed, not used as a feature.
Training time is shared across architecture results and must not be summed.

**Rule-mining hyperparameters remain prespecified defaults** recorded in the result.
Only decision thresholds are selected on Validation here. This is not a grid search
of every architecture's hyperparameters, nor a reuse of the old RandomForest
ablation tuning file. Existing ablation/statistical outputs remain historical.
Repeatedly examining Test to choose settings would still invalidate final-test use.

Run the isolated protocol tests without fitting real dataset models:

```sh
cd sqlia_backend
mvn -Dtest=FrozenArchitectureComparisonTest,EvaluationDatasetSupportTest test
```

These use a synthetic trainer/scorer to verify orchestration, sample coverage,
thresholds, isolation and invalid-input rejection. They do not establish real-model
accuracy, production training runtime, or successful completion of full rule mining.
Before an intentional real run, back up current data/results and allocate time for
full rule mining and training. Rule-mining errors abort training rather than silently
substituting an empty rule set.

### Training and Validation without Test evaluation

`TrainValidationRunner` is a separate entry point that never scores Test rows.
It opens the existing database read-only, checks split integrity, copies Training
to an in-memory database, trains all rows, and selects five thresholds on Validation.
It does not replace the interactive API models or run the comparison endpoints.
Create a verified SQLite/data/results backup before launching it.

From `sqlia_backend`, use only the dedicated execution and a **new** output directory:

```sh
mvn compile exec:java@train-validation -Dexec.args='"/absolute/backend/db/SQLIA.db" "/absolute/new-run-directory"'
```

Do not use the default `exec:java` goal or rely on `-Dexec.mainClass`: the global
configuration targets the importing `Main` class.

The run writes `frozen-model.ser` and `training_validation.json`, recording Training
and Validation sizes/fingerprints, settings, Validation threshold/F1/FPR per architecture,
checkpoint SHA-256, and explicit `testEvaluated=false` / `testPredictions=0`.
Success requires every reloaded Validation score to exactly match the original model.
Only reports with `status=complete` represent successful runs; failed or interrupted
directories must not be reused. The checkpoint contains the three ensembles, feature
rules, extended rules, and normal profile, not JDBC connections or interactive state.
Load only locally generated trusted checkpoints using the same compatible code and
dependency versions; Java serialization is not a safe user-upload format.

Checkpoint round-trip validation uses actual Weka models without expensive rule mining:

```sh
mvn -Dtest=FrozenCheckpointTest,FrozenArchitectureComparisonTest,FeaturePipelineTest test
```

### Final Test using the saved checkpoint

The independent `SavedTestRunner` requires a backend-local saved experiment directory.
It evaluates Test only: it does not train, score Validation again, or choose new
thresholds. This runner is not connected to the website performance page.
Saved runs must be inside backend `data/experiments/`; do not place
untrusted serialized objects there. A missing or invalid checkpoint fails explicitly,
never falling back to fresh training.

Before predictions, the service verifies complete training status, protocol/settings,
dataset/Training/Validation fingerprints and counts, valid saved threshold choices,
and the checkpoint SHA-256. The exact complete Test CSV is still checked against the
database before deterministic optional subsampling. All architectures use the same
Test rows and their saved Validation-selected thresholds. Results use
`evaluationMode=frozen_saved_checkpoint`, include checkpoint/training-report SHA-256,
and retain confusion counts, FPR, and source/type metrics. `trainingDurationMs` is the
historical training duration, not new training during this request.

For a persistent full five-architecture report, run from `sqlia_backend`:

```sh
mvn compile exec:java@saved-test -Dexec.args='"/absolute/backend/db/SQLIA.db" "/absolute/backend/data/processed/test.csv" "/absolute/backend/data/experiments/saved-run" "/absolute/new-test-report.json"'
```

`SavedTestRunner` opens SQLite read-only, evaluates the entire fixed Test partition,
and refuses to overwrite an existing report. Successful reports record
`status=complete`, `testEvaluated=true`, `retrained=false`, and
`thresholdsRetuned=false`. It does not modify the checkpoint or training report.
Only a complete report is valid; an empty file can indicate an interrupted run.
Repeated evaluation is reproducible, not independent evidence. Once Test is examined,
do not use it to select models, rules, thresholds, or preprocessing settings.

```sh
mvn -Dtest=SavedCheckpointEvaluationTest,FrozenCheckpointTest,FrozenArchitectureComparisonTest test
```

The detector accepts a single input string, not only complete SQL: SQL statements,
injection fragments, numeric values, email addresses, and ordinary text are supported
input forms. A classification is not proof of safety or evidence that an injection
was executed. The JSON request field remains `sql` for compatibility. Single-input
endpoints reject blank values and inputs longer than 4,000 UTF-16 code units.

The `exact-dedup-grouped-v2` preprocessing pipeline:

1. Reads the three original CSV sources, retaining field text, source file, and CSV
   record number (not physical line number for multiline fields). UTF-8 and BOM-marked
   UTF-16LE/BE are supported. Missing columns or inconsistent field counts fail
   explicitly rather than guessing the label column.
2. Canonicalizes labels to `0`/`1`; quarantines invalid labels and blank input.
   Non-SQL benign values are retained. SQL parser success is not a cleaning criterion.
3. Merges only exactly identical text with the same label. Retains all source
   occurrences. Identical text with conflicting labels is quarantined in its entirety.
4. Preserves nonidentical variants. Uses a separate similarity key (URL form decoding,
   case folding, whitespace/operator-spacing normalization, and trailing semicolon
   removal) only for grouping. This heuristic does not prove semantic equivalence,
   does not cover every near-duplicate, and must not replace the original model input.
   Differently labelled variants in a group retain their labels.
5. Assigns whole groups to approximately 70% training, 15% validation, and 15% test
   using seed 42, balancing per-label sample counts rather than majority group labels.
   Large groups can prevent exact ratios. Keep the exported assignments fixed across
   all five architectures.

`DatasetPreprocessor.prepare(sources, outputDirectory)` writes:

| Output | Contents |
|---|---|
| `combined_preprocessed.csv` | Retained `payload,label,source` plus record/type/group/split metadata and occurrence counts |
| `train.csv`, `validation.csv`, `test.csv` | The same schema with fixed split membership |
| `quarantine.csv` | Excluded rows and explicit reasons; conflicts require review, not arbitrary relabelling |
| `provenance.csv` | Source file and CSV record for every retained or quarantined occurrence |
| `preprocessing_report.json` | Pipeline version, seed, counts, label/type/source distributions, groups, and split statistics |

`input_type` values are descriptive heuristics (`sql_like`, `fragment_like`,
`function_like`, `numeric`, `email`, `identifier_or_short_text`, `url_or_http_like`,
`markup_like`, `other_text`), not security labels. Source/type/group metadata is for
auditing and partitioning, not classifier features. Source combinations represent
merged provenance; they are not mutually independent datasets.

The source-holdout helper excludes held-out-only variants whose similarity groups
overlap training sources; it never adds those variants to training. Shared exact
records retain their other-source provenance on the training side. Full cross-dataset
experiments and training-flow changes are separate from this preprocessing update.

To verify the new pipeline without modifying the existing database, processed CSVs,
or trained models, run from `sqlia_backend`:

```sh
mvn -Dtest=DatasetPreprocessorTest,DeduplicatorTest,SingleInputDetectionTest,EvaluationDatasetSupportTest test
```

The real-source audit test uses an automatically cleaned temporary directory.
For a persistent preview, launch the main class
`tw.edu.cse.nsysu.preprocess.DatasetPreprocessor` with two explicit arguments:
the absolute backend directory and a new absolute output directory. For example,
use `<backend>/data/processed/review` as the output. This is an optional preview
directory, not a required historical archive. Obsolete dataset copies have been
removed from the working directory; current CSV filenames remain unchanged. The preview entry
point refuses an existing output directory and does not open SQLite or initialize
models. It is safe to inspect its CSV and JSON outputs before an intentional import.
From the backend directory, use the dedicated Maven execution (quote paths with
spaces inside `exec.args`):

```sh
mvn compile exec:java@preview -Dexec.args='"/absolute/backend" "/absolute/new-preview-directory"'
```

Do not substitute `exec:java -Dexec.mainClass=...`: this POM explicitly configures
`Main` for the default execution, so that form can run the importer instead.
The no-argument `prepare()` still writes to `data/processed` when explicitly called
by the importer. Running the importer rebuilds the database: do not run it merely
to preview preprocessing. Existing data/models remain on their previous version until
an intentional rebuild. Updated input wording does not improve an old model's accuracy.
Retrain and reevaluate before reporting results for the new dataset.

For Flutter input validation/widget checks, run from `sqlia_frontend`:

```sh
flutter test test/single_input_test.dart
```

# Installation Requirements
* **VScode (Recommended)**
* **JDK 21**  
(at least 17+ version, choose jdk-21_windows-x64_bin.exe)   
URL: https://www.oracle.com/tw/java/technologies/downloads/#java21 
* **SQLite(For Lookup table method 1)**  
(For Windows, choose **Precompiled Binaries for Windows** -> **sqlite-tools-win-x64-3530000.zip**)    
URL: https://www.sqlite.org/download.html
* **Apache-maven-3.9.15**  
(Choose **Binary zip archive** -> **apache-maven-3.9.15-bin.zip**)   
URL: https://maven.apache.org/download.cgi#CurrentMaven 
* **VSCode Flutter Extension**
* **Flutter SDK** (Can install from VSCode and set Path)
# VScode Extension
* **SQLite Veiwer (For Lookup table method 2):** To see the entire table content in VScode.

# Project Structure
```
sql_app
├─ pom.xml ----> Describe how to use the Maven project, dependency and plugins
├─ readme.md 
├─ SQLIA_schema.sql ----> A sqlite command file to create a database
├─ data/
│   ├─ raw  ---> Put all raw dataset (.csv files)
│   └─ processed  ---> Put all preprocessed files for specific purpose
├─ db/ -----> Put database(.db files) here
└─ src  ----> Put source code here
   ├─ main/java/tw/edu/cse/nsysu/
   │                       ├─ .....
   │                       ├─ ......
   │                       └─ Main.java
   └─ test/java/tw/edu/cse/nsysu/
```

# pom.xml dependencies
In Maven, to use a certain Java util, need to add the depedency to pom.xml:
* commons-csv:
    ```
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-csv</artifactId>
      <version>1.14.1</version>
    </dependency>
    ```
* sqlite-jdbc:
    ```
     <dependency>
      <groupId>org.xerial</groupId>
      <artifactId>sqlite-jdbc</artifactId>
      <version>3.41.2.2</version>
    </dependency>
    ```
* jsqlparser:
  ```
  <dependency>
      <groupId>com.github.jsqlparser</groupId>
      <artifactId>jsqlparser</artifactId>
      <version>4.6</version>
  </dependency>
  ```
  
* SPMF:   
  If the SPMF is not found in Maven storage, download spmf.jar from the following link:     
  url: https://www.philippe-fournier-viger.com/spmf/index.php?link=download.php       
  Place this spmf.jar in a lib/ folder.      
  And Execute the following command in terminal:   
  ```
   mvn install:install-file -Dfile=lib/spmf.jar -DgroupId=ca.pfv.spmf -DartifactId=spmf -Dversion=2.60 -Dpackaging=jar
  ```
  Finally, add the following dependency in pom.xml: 
  ```
  <dependency>
      <groupId>ca.pfv.spmf</groupId>
      <artifactId>spmf</artifactId>
      <version>2.60</version>
    </dependency>
  ```
# Maven related commands
* Check **maven version**:
    ```
    mvn --version
    ```
* To **build** Maven project:
    ```
    mvn compile
    ```
* To **clean** the previous built project:
    ```
    mvn clean
    ```
* To **clean and then build**:
    ```
    mvn clean compile
    ```
* To **execute Main.java**:
    *By adding the following plugins in pom.xml, the Main.java execution command will be shorter:*
    ```
    <plugins>
        <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.1.0</version>
        <configuration>
            <mainClass>tw.edu.cse.nsysu.Main</mainClass>
        </configuration>
        </plugin>
    </plugins>
    ```
    So now the command is:
    ```
    mvn exec:java
    ```

# To Look up the database table
## Method 1: By Using Command Prompt
1. After installing SQLite.zip, decompress the file and move sqlite3.exe under the same directory as SQLIA.db (under db folder).
2. Move from VSCode terminal to Command Prompt, use following commands:
* Move to db folder  
    ```
    cd db
    ```
* Enter sqlite3 environment  
    ```
    sqlite3 SQLIA.db
    ```
* To check tables exists in this database:
    ```
    .table
    ```
* To see the first 10 rows of data (Don't try to see the entire table in cmd because the data is a lot):   
    ```
    SELECT * FROM DataTable LIMIT 10;
    ```
* To see how many data:    
    ```
    SELECT COUNT(*) FROM DataTable;
    ```
    ```
    SELECT COUNT(*) FROM DataTable WHERE label=1;
    ```
* To see how may data are labeled 0 (benign):     
    ```
    SELECT COUNT(*) FROM DataTable WHERE label=0;
    ```

The backend automatically renames the legacy `training_data` table to
`DataTable` on startup and preserves its rows. If both table names exist, it
stops with an error instead of choosing one silently.

## Method 2: Visualize directly
This method only need to install SQLite Veiwer extension.

# To open the flutter website
After setting up Flutter, choose device: chrome
  ```
  cd .../sqlia_backend
  mvn compile exec:java@api
  ```
  Run this command from the backend project directory containing `pom.xml`.
  `exec:java@api` alone does not compile Java sources. After a clean build or
  source changes, it can fail with `ClassNotFoundException:
  tw.edu.cse.nsysu.api.ApiServer` or run stale classes. Including `compile`
  ensures compilation succeeds before the API starts on port 8080.

  Open new terminal:
  ```
  cd .../sqlia_frontend
  flutter pub get
  flutter run -d chrome
  ```

Flutter learning resources:
- [Learn Flutter](https://docs.flutter.dev/get-started/learn-flutter)
- [Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Flutter learning resources](https://docs.flutter.dev/reference/learning-resources)
- [Flutter documentation](https://docs.flutter.dev/)

# Mapping base SQL features to transaction items before association rule mining
## Feature vector to transaction item mapping

The current implementation extracts six base features in `FeatureExtract` and
converts them into categorical transaction items in `FeatureForML`.

| Vector index | Base feature | Condition | Transaction item |
|---|---|---|---:|
| [0] | Payload length | `> 100` | 1 |
| [1] | Symbol density | `> 0.30` | 2 |
| [2] | Comparison density | `== 0.0` | 3 |
| [2] | Comparison density | `> 0.05` | 33 |
| [2] | Comparison density | `> 0.0` and `<= 0.05` | 30 |
| [3] | SQL complexity | `0`, `1`, `2`, or `3` | 40, 41, 42, or 43 |
| [4] | Function-call density | `== 0.0` | 5 |
| [4] | Function-call density | `> 0.02` | 55 |
| [4] | Function-call density | `> 0.0` and `<= 0.02` | 50 |
| [5] | Discontinuous-character density | `> 0.30` | 6 |

Items are categorical representations of the continuous base features. A
feature whose value does not satisfy a listed threshold does not add an item,
except for comparison, complexity, and function-call density, which always
select one of their corresponding categories.

# Label mapping and data leakage prevention
| Label | Meaning | Transaction item (Stage 3 only) |
|---:|---|---:|
| 0 | Benign | 100 |
| 1 | Malicious / SQL Injection | 101 |

**Pure rule mining (Stage 3):** Append item `100` or `101` to each transaction
so that association rules can describe the relationship between SQL features
and the class label.

**Rule mining + ML (Stages 4 and 5):** Do not append `100` or `101` to the
transaction used to generate ML features. This prevents the class label from
leaking directly into the input features.



# Base models
Incremental Naive Bayes Classifier

# Extended Association Rules (`EXTENDED_ARM`)
`ExtendedRuleMiner` extends plain `{items} => SQLi` rules in three ways:
- **Token items**: 28 SQL token types (quote, UNION, comment, tautology, ...) become items.
- **Negative items**: rules may contain absent items, e.g. `{AND, NOT WHERE} => SQLi`.
- **Sequential rules**: PrefixSpan token-order rules, e.g. `UNION -> SELECT => SQLi`.
Rules are filtered by `lift`, then chosen by greedy coverage. Two distinct
detection paths use these rules:
- `EXTENDED_ARM` combines rule-confidence features with an incremental classifier.
- `PURE_EXTENDED_ARM` makes its prediction from matched-rule confidence only; it
  does not use an ML model. A matching rule at the configured confidence
  threshold predicts SQL injection and recommends `block`; no matching rule
  predicts the benign class for binary evaluation but recommends `review`,
  because absence of a rule is not proof that a query is safe.

Both paths return matched rules. Pure Extended ARM is a fixed rule model and
does not support online updates.

API: `GET /api/extended/rules`, `GET /api/extended/ablation`.
`POST /api/architectures/predict` returns results for both paths in the same
response. `POST /api/performance/compare` evaluates both on the same labeled
dataset and reports total records, accuracy, precision, recall, F1, and
execution time.

#### Extended ARM experiment groups

| Group | Path | What it measures |
|---|---|---|
| Rule-only | `PURE_EXTENDED_ARM` | Extended ARM's direct rule-matching performance, without a classifier |
| Hybrid | `EXTENDED_ARM` | The effect of classifying Extended ARM rule features with an incremental model |

The rule-only path uses the maximum confidence among matching rules and the
current fixed threshold of `0.6`. It is evaluated as a binary classifier:
matched confidence at or above the threshold is SQL injection; otherwise the
binary label is benign. For defense recommendations, the latter is `review`,
not `allow`, since no matching rule cannot guarantee safety. Keep the threshold
and held-out test split fixed for the final comparison; do not tune parameters
on test results.

Ablation experiment. Rules are mined on `train`; `minSup`, `minLift` and max rule count are
grid-searched on `validation` (`data/processed/tuning_results.csv`); `test` is used once with the
chosen values. Output: `data/processed/ablation_results.csv` (validation and test rows):
```bash
mvn compile exec:java@ablation
```

Statistical validation (needs `tuning_results.csv` from the ablation run):
```bash
mvn compile exec:java@statistics
```
Writes `data/processed/statistical_results.csv` (served by `GET /api/extended/statistics`):
5-fold x 2 repeats CV on `train` only (rules re-mined inside each fold) with the Nadeau-Bengio
corrected resampled t-test, plus exact McNemar tests and a paired bootstrap 95% CI on `test`.

# Incremental Learning in the Hybrid Method

Each dashboard function opens a mode-selection page with two choices:
**A. All architectures** (all five detection architectures) or
**B. Two Extended ARM methods** (Pure Extended ARM and Extended ARM with
Incremental Learning).

| Method | Prediction | Learning behavior |
|---|---|---|
| `PURE_EXTENDED_ARM` | Maximum confidence of matching Extended ARM rules; threshold `0.6` | Fixed rules; no online updates |
| `EXTENDED_ARM` | Incremental Online Bagging classifier using base SQL features and Extended ARM rule-confidence features; threshold `0.5` | Updates the classifier with verified labels |

The hybrid classifier is warmed up from a reproducibly shuffled sample of up to
2,000 training-split rows. Its mined rule set and feature schema remain fixed
for the model session; verified replay feedback updates the classifier but does
not re-mine rules. Updates are held in memory and do not survive an API restart.

The default Hybrid learner is **Online Bagging with 15 updateable Naive Bayes
classifiers**. For each labeled example, each ensemble member independently
draws `k ~ Poisson(1)` and updates on that example `k` times (`k = 0` skips it).
Prediction averages the members' class probabilities before applying the
decision threshold. Online Bagging is the ensemble strategy; updateable Naive
Bayes is its base learner.

The dashboard has one button for each function: architecture diagrams,
single-query comparison, association-rule analysis, held-out performance
comparison, and dataset replay. After selecting a function, choose mode A or B
on the next page. This keeps the full-system context available while letting
the presentation focus on Extended ARM.

The dataset replay page also compares the incremental learners where online
learning occurs. Mode A compares Online Bagging (Naive Bayes), stochastic
gradient descent (SGD), and Hoeffding Tree separately for every incremental
architecture (`RATIO_INCREMENTAL_ML`, `FULL_FLOW`, and `EXTENDED_ARM`). Mode B
compares the same three learners with the `EXTENDED_ARM` feature set. Each
architecture group uses the same feature representation, a reproducibly
shuffled warmup sample of up to 2,000 training rows, and the same held-out row
order for all three learners. The replay is prequential: each query is scored
before its verified label updates that learner. The page shows a cumulative-
accuracy line chart for each architecture group and the final evaluation
metrics. These isolated comparison models do not change the live detector
model.

The SGD implementation uses **log loss** (an incremental logistic linear
classifier), not hinge-loss SVM, despite the legacy UI label
`SGD / Incremental SVM`. Hoeffding Tree incrementally accumulates statistics
and grows tree splits. These are alternative learners evaluated separately,
not three learners combined into one ensemble. Incremental updates change
classifier state only: FP-Growth and PrefixSpan perform the initial rule
mining, and the learned rules and their confidence values remain fixed.

For `PURE_EXTENDED_ARM`, a matching rule at or above the website's
Validation-calibrated threshold predicts SQL
injection and recommends `block`. No qualifying matched rule predicts the
benign class for binary metrics and recommends `review`; rule absence does not
establish that a query is safe.

`POST /api/architectures/predict` and `POST /api/performance/compare` serve
the all-five-architecture pages. The focused pages use
`POST /api/extended/architectures/predict` and
`POST /api/extended/performance/compare`, which return only the two methods.
The replay-page incremental learner comparison uses
`POST /api/incremental/algorithms/compare` in mode A and
`POST /api/extended/incremental/algorithms/compare` in mode B.
Both performance comparisons use the same held-out data for each method and
disable model updates so evaluation does not change model state.

### Database table data volume (`DataTable`)

Current record counts in `sqlia_backend/db/SQLIA.db`:

| Split | Total records | Benign (`label=0`) | SQL injection (`label=1`) |
|---|---:|---:|---:|
| Training (`train`) | 24,943 | 16,395 | 8,548 |
| Validation (`validation`) | 5,344 | 3,513 | 1,831 |
| Testing (`test`) | 5,345 | 3,513 | 1,832 |
| **Total** | **35,632** | **23,421** | **12,211** |

Exact duplicates are removed. Distinct raw inputs with the same normalized key
remain separate records, but their entire similarity group stays in one split.
These counts reflect the current database and may change after importing or rebuilding.

The Extended ARM performance screen defaults to the held-out testing split
from `data/processed/test.csv` (5,345 records), generated together with the database
by v2 preprocessing. Do not use the legacy `test_holdout.csv` or export a
payload-only CSV: the current loader checks split, source and Training overlap.

Leakage safeguards: conflicting labels for the same exact input are quarantined;
normalized variants are grouped before splitting. Warmup, FP-Growth and extended
rule mining use Training only. `MLTrainer` and `ToTransactionList` read
`split_group` only. Re-running the default `mvn exec:java` rebuilds `DataTable`
from raw CSVs, replacing its rows; this is not the API startup command.
Use `mvn exec:java@api` to run the website without rebuilding data.

The performance comparison defaults to all rows in this file and evaluates both
Extended ARM methods against the current website state without updates. Dataset replay also
offers both methods; enabling feedback updates only the hybrid Incremental ML
classifier, while the pure rule set remains fixed.

The hybrid method uses its Extended ARM rule features with an Online Bagging
classifier based on Incremental Naive Bayes. Its verified feedback updates are
held in memory for the current API session only.
