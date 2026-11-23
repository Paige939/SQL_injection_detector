# SQL Injection Detection and Defense with Extended Association Rule Mining

Java backend and Flutter frontend for SQL injection detection, association-rule
analysis, architecture/algorithm comparisons and controlled product-search defense.
The original detection pages provide recommendations; the **Defense laboratory**
also enforces decisions before a demo product query. This is a research prototype,
not a production WAF or a general-purpose database proxy.

## 1. Installation requirements

| Requirement | Notes |
|---|---|
| [JDK 21](https://www.oracle.com/java/technologies/downloads/#java21) | Required by the Maven compiler; configure `JAVA_HOME` and `PATH`. |
| [Apache Maven 3.9.x](https://maven.apache.org/download.cgi) | Builds the backend and resolves dependencies. |
| [Flutter SDK](https://docs.flutter.dev/install) | Use a release including Dart **3.13.1+**, as required by the frontend. |
| Google Chrome | Flutter Web development and testing. |
| [VS Code](https://code.visualstudio.com/) | Recommended IDE. |
| [SQLite CLI](https://www.sqlite.org/download.html) | Optional for initializing/inspecting local databases. |
| SPMF 2.60 JAR | Install locally if unavailable from Maven repositories; see below. |

Check your environment:

```sh
java -version
mvn --version
flutter --version
flutter doctor
```

### Recommended VS Code extensions

- **Extension Pack for Java**: Java language support, debugger, Maven and tests.
- **Flutter** and **Dart**: frontend development/debugging.
- **SQLite Viewer**: optional database inspection.

The included [Java settings](.vscode/settings.json) exclude backup projects with
duplicate Maven coordinates and macOS `._*` resource files. If a backup was already
imported, save edits and run **Java: Clean Java Language Server Workspace**,
then launch **SQLIA API (current backend)** using [launch.json](.vscode/launch.json).

## 2. Project structure

```text
sqlia_backend/
  pom.xml                 Maven dependencies and entry points
  SQLIA_schema.sql        DataTable schema and indexes
  src/main/java/          Preprocessing, mining, classifiers and REST API
  src/test/java/          Backend tests
  data/raw/               Locally supplied source CSVs
  data/processed/         Generated splits, transactions and evaluation files
  data/experiments/       Research checkpoints, manifests and reports
  db/SQLIA.db             Local dataset database
  lib/                    Locally supplied third-party JARs
  docs/                   Architecture diagrams and research explanations
sqlia_frontend/
  pubspec.yaml            Flutter dependencies
  lib/                    Screens, models, providers and API services
  assets/                 Architecture and rule diagrams
  test/                   Frontend tests
  web/                    Web entry files
```

Datasets, local databases, checkpoints, generated reports, third-party JARs,
runtime state, build outputs and backups are not bundled in a fresh clone.
Prepare these resources before starting the corresponding feature.

## 3. Dependencies

### Backend: [pom.xml](sqlia_backend/pom.xml)

The dependencies are already declared; do not add duplicate entries.

| Maven dependency | Version | Purpose |
|---|---|---|
| `org.apache.commons:commons-csv` | 1.14.1 | CSV parsing/export |
| `org.xerial:sqlite-jdbc` | 3.41.2.2 | SQLite access |
| `com.github.jsqlparser:jsqlparser` | 4.6 | SQL parsing/feature extraction |
| `ca.pfv.spmf:spmf` | 2.60 | FP-Growth, association rules and PrefixSpan |
| `nz.ac.waikato.cms.weka:weka-stable` | 3.8.6 | Classifiers |
| `nz.ac.waikato.cms.moa:moa` | 2021.07.0 | Incremental learning; excludes `dsol-xml` |
| `ml.dmlc:xgboost4j_2.12` | 1.7.5 | Existing ML experiments |
| `org.slf4j:slf4j-simple` | 1.7.36 | Logging |
| `io.javalin:javalin` | 6.3.0 | REST API |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.2 | JSON |
| `junit:junit` | 4.13.2 | Tests |

Maven uses Java 21, Compiler Plugin 3.11.0, Surefire 3.0.0 and Exec Plugin 3.1.0.

If SPMF cannot be resolved, obtain the matching JAR from the
[SPMF download page](https://www.philippe-fournier-viger.com/spmf/index.php?link=download.php),
place it at `sqlia_backend/lib/spmf.jar`, then run from the backend directory:

```sh
mvn install:install-file -Dfile=lib/spmf.jar -DgroupId=ca.pfv.spmf -DartifactId=spmf -Dversion=2.60 -Dpackaging=jar
```

### Frontend: [pubspec.yaml](sqlia_frontend/pubspec.yaml)

Flutter SDK, `dio`, `flutter_riverpod`, `fl_chart`, `flutter_svg` and
`cupertino_icons`; development uses `flutter_test` and `flutter_lints`.
Restore the declared versions with `flutter pub get`; do not upgrade packages
merely to run the project.

## 4. Raw and generated datasets

### Raw inputs

The [raw dataset](https://www.kaggle.com/datasets/syedsaqlainhussain/sql-injection-dataset)
includes `SQLiV3.csv`, `sqli.csv`, and `sqliv2.csv`.

Place the following locally acquired CSVs under `sqlia_backend/data/raw/`:

| File | Text column | Label column |
|---|---|---|
| `Modified_SQL_Dataset.csv` | `Query` | `Label` |
| `sqli.csv` | `Sentence` | `Label` |
| `SQLiV3.csv` | `Sentence` | `Label` |

Labels are **0 = benign**, **1 = malicious / SQL injection**.
`clean_sql_dataset.csv` is an optional external-evaluation source, not a default
training input. Its supplied provenance is
[Biggest SQL Injection Dataset](https://www.kaggle.com/datasets/gambleryu/biggest-sql-injection-dataset).
Verify source permissions and labels; do not redistribute datasets without permission.

### Preprocessing

The `exact-dedup-grouped-v2` pipeline:

1. Reads source text and provenance, supporting UTF-8 and BOM-marked UTF-16.
2. Quarantines invalid labels, blank text and exact-text label conflicts.
3. Merges identical text with the same label, retaining all source occurrences.
4. Preserves nonidentical text; uses a separate normalized similarity key for grouping.
5. Splits whole groups approximately **70% Training / 15% Validation / 15% Test**
   with seed **42**. Normalization is heuristic, not proof of SQL equivalence.

Non-SQL benign values are retained. Source, type and group metadata are not model
features. All architectures must use the same fixed split assignments.

| Generated file in `data/processed/` | Contents |
|---|---|
| `combined_preprocessed.csv` | Retained payloads, labels and split/provenance metadata |
| `train.csv`, `validation.csv`, `test.csv` | Fixed dataset partitions |
| `quarantine.csv` | Excluded records and reasons |
| `provenance.csv` | Source file and CSV record origins |
| `preprocessing_report.json` | Version, seed, counts and split statistics |
| `Stage*/Version*/SPMF_input_data.txt` | Optional legacy transaction export |
| `Stage*/Version*/FPGrowth_output_*.txt` | Optional legacy mining output |
| `tuning_results.csv`, `ablation_results.csv`, `statistical_results.csv` | Optional experiment outputs |

Current local dataset counts: **24,943 Training**, **5,344 Validation**,
**5,345 Test**. A fresh clone does not contain these rows.

## 5. Prepare, build and run

Commands below assume the repository root unless stated otherwise.
Quote absolute paths containing spaces inside `exec.args`.

### A. Preview preprocessing without changing the database

Supply a **new, nonexistent** output directory:

```sh
cd sqlia_backend
mvn compile exec:java@preview -Dexec.args='"/absolute/backend" "/absolute/new-preview-directory"'
```

This creates preview CSV/JSON files only; it refuses to overwrite an existing
directory and does not initialize models or open SQLite.

### B. Initialize or intentionally rebuild the dataset

**Warning:** default `Main` always imports data and extracts features. It rebuilds
`DataTable` and rewrites processed datasets. Stop the API and back up existing
data/results before an intentional rebuild. Do not use it to start the website.

From `sqlia_backend`:

```sh
mkdir -p db
mvn compile exec:java
```

The importer uses [SQLIA_schema.sql](sqlia_backend/SQLIA_schema.sql) and the three
raw sources. Creating the schema alone does not populate Training/Validation.
For optional legacy transaction export and mining:

```sh
mvn compile exec:java -Dexec.args="--spmf --fpgrowth"
```

This command **also rebuilds the dataset**. `--full` additionally runs the legacy
model/reset/incremental stages; it is not the normal website startup workflow.
Inspect logs and generated row counts because legacy importer failures may be logged
without a nonzero process exit.

### C. Build and start the backend

From `sqlia_backend`, with populated `db/SQLIA.db`:

```sh
mvn compile
mvn compile exec:java@api
```

API defaults to `http://localhost:8080`. To use another port:

```sh
mvn compile exec:java@api -Dsqlia.port=18080
```

Use the dedicated `@api` execution or the VS Code launch configuration.
Do not substitute `exec:java -Dexec.mainClass=...`: the default POM execution
explicitly targets the importer.

Startup reads the existing dataset, mines Training rules and warms website
classifiers with up to 2,000 rows. Pure ARM's threshold is selected using
Validation only; invalid, single-class or overlapping Validation fails startup.
Test is not used for startup calibration.

### D. Start or build the frontend

In a separate terminal:

```sh
cd sqlia_frontend
flutter pub get
flutter run -d chrome
```

For the alternate API port:

```sh
flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:18080
```

Production Web build:

```sh
flutter build web
```

Serve the generated `build/web` directory with a web server; the backend is a
separate process. For backend packaging use `mvn package`; the resulting JAR is
not documented as a standalone dependency-bundled server.

## 6. Architectures and transaction itemsets

### Five prediction paths

| ID | Method | Prediction |
|---|---|---|
| `ARM_PROFILE` | Normal Profile | Anomaly score derived from benign feature distributions |
| `RATIO_INCREMENTAL_ML` | Feature ML | Six base features + Online Bagging |
| `FULL_FLOW` | Extended FP-Growth + Incremental ML | Base features, standard ARM features and normal-profile features |
| `PURE_EXTENDED_ARM` | Pure Extended ARM | Maximum confidence among matched extended rules |
| `EXTENDED_ARM` | Extended ARM + Incremental ML | Base features + extended-rule confidence features |

**A mode** offers all five; **B mode** focuses on Pure Extended ARM and Extended ARM.
The website's default classifier is Online Bagging with updateable Naive Bayes.
The defense lab also selects **SGD / Incremental SVM** or **Hoeffding Tree** for
Extended ARM. Other architecture paths retain their own classifiers/decision logic.

### Base-feature transaction mapping

[FeatureExtract](sqlia_backend/src/main/java/tw/edu/cse/nsysu/FeatureExtract.java)
produces six features; [FeatureForML](sqlia_backend/src/main/java/tw/edu/cse/nsysu/FeatureForML.java)
maps them to categorical items:

| Index | Feature | Condition | Item |
|---|---|---|---:|
| 0 | Payload length | `> 100` | 1 |
| 1 | Symbol density | `> 0.30` | 2 |
| 2 | Comparison density | `== 0` / `(0, 0.05]` / `> 0.05` | 3 / 30 / 33 |
| 3 | SQL complexity | Levels 0 / 1 / 2 / 3 | 40 / 41 / 42 / 43 |
| 4 | Function density | `== 0` / `(0, 0.02]` / `> 0.02` | 5 / 50 / 55 |
| 5 | Discontinuous-character density | `> 0.30` | 6 |

Comparison, complexity and function density select one category each; the other
features add an item only when their threshold is exceeded. For example,
`[120, 0.40, 0.06, 2, 0.03, 0.40]` maps to `{1, 2, 6, 33, 42, 55}`.

During supervised mining, append **100 = benign** or **101 = SQLi** to discover
`antecedent => class` rules. Never include label items in prediction/ML inputs.
SPMF itemset rows are sorted positive integer IDs separated by spaces.

### Extended association rules

[ExtendedRuleMiner](sqlia_backend/src/main/java/tw/edu/cse/nsysu/ExtendedRuleMiner.java)
adds:

- **Token items:** 28 token types, encoded as `200 + tokenId`.
- **Negative items:** absence encoded as `1000 + itemId`; absence is not proof of safety.
- **Sequential patterns:** PrefixSpan preserves token order; matching permits gaps.

Token sequences are limited to 40 tokens with consecutive duplicates collapsed.
Itemset mining uses FP-Growth and association-rule generation. Rules carry
support, confidence and lift; lift filtering and greedy attack-coverage selection
limit redundancy. Defaults: extended support **0.1**, confidence **0.6**, lift
**1.5**, maximum antecedent length **3**, maximum **25 rules per family**.
Standard ARM defaults are support **0.05**, confidence **0.6**.

Each hybrid rule feature equals its confidence when matched, otherwise zero.
Pure ARM uses the strongest matched confidence, with zero when no rule matches.
Website rules stay fixed within a session; verified labels update classifiers,
not the rule miner. Pure ARM does not support online model updates.

## 7. Use the website and defense laboratory

Dashboard functions: architecture diagrams, one-input comparison, rule analysis,
Test performance, Test replay, saved external reports and the defense laboratory.
Single-input detection accepts SQL, fragments and ordinary input; blank or
over-4,000-UTF-16-unit inputs are rejected. Scores are not calibrated probabilities.

Website feedback/replay changes interactive predictions; restart rebuilds that
session from Training. Website performance is a current-state diagnostic, not
final fixed research evidence. Performance/replay use `data/processed/test.csv`,
not obsolete overlapping holdouts.

### Defense laboratory

Choose A/B, then use:

1. **Protected search:** select architecture; Extended ARM also selects an algorithm.
   Enter `Keyboard` and submit. An allowed request returns a product.
2. **Audit & gate evidence:** inspect request ID, action, rule matches, query flags
   and latency. Rejected product-query attempts should remain zero.
3. **Test & algorithms:** use `5345` to compare the complete local Test.
   A produces seven variants; B produces four. All use the same sampled rows
   without updates or product queries.

Server policy is fixed **ENFORCE**: `block` returns HTTP 403 without calling
the product repository. `review` pauses before the query and asks the user to
choose **Allow** or **Block**; only an explicit Allow runs the parameterized
product query, while Block returns no products. Direct `allow` runs the same
parameterized query, up to 20 results. Search accepts a product name or an exact
`price_cents` value and is limited to 1..100 characters. Detector/audit failures
return explicit errors; query attempted/completed flags distinguish failures.

**Pure ARM limitation:** below-threshold predictions become `review`, so each
Pure ARM search requires a user decision before any product query. In batch Test
evaluation, review counts as not automatically allowed; its policy metrics do
not include a human decision. In the verified 5,345-row Test, SGD achieved F1
**98.77%** and policy benign denial **0.74%**; these are existing-Test
diagnostics, not a new blind test or proof that external false positives are
resolved.

Lab models are fixed startup copies, isolated from website feedback/replay.
The independent product/audit/report SQLite defaults to `~/.sqlia/defense-lab.db`;
override with `-Dsqlia.defense.database=/absolute/local/path/defense-lab.db`.
Use a local filesystem: writable SQLite failed on the external project volume.
Raw search text is not saved. Pending review text is held in memory for up to
10 minutes and is lost on restart; audits/reports persist, while models are
recreated at startup.

Test latency measures prediction only. Audit totals end before the final audit
write. Unlabelled live searches cannot establish FPR. Parameterized queries remain
the basic defense regardless of ARM; do not attribute their protection solely to ML.

### Main API endpoints

| Endpoint | Purpose |
|---|---|
| `POST /api/architectures/predict` | Five predictions; body `{"sql":"..."}` |
| `POST /api/extended/architectures/predict` | Two Extended ARM methods |
| `GET /api/extended/rules` | Mined extended rules |
| `POST /api/performance/compare` | Current website Test comparison |
| `GET /api/defense/status` | Lab mode, version, architectures and algorithms |
| `POST /api/defense/search` | Body `{keyword, architecture, algorithm}` |
| `POST /api/defense/review` | Body `{requestId, decision}`; resolve a pending review with `allow` or `block` |
| `GET /api/defense/audit` | Latest 200 records and grouped latency |
| `GET /api/defense/evaluation` | Latest saved report or `missing` |
| `POST /api/defense/evaluation` | Body `{extendedOnly, sampleSize}`; size 1..10000 |

Check readiness:

```sh
curl http://localhost:8080/api/defense/status
```

## 8. Tests and database inspection

From `sqlia_backend`:

```sh
mvn test
mvn test -Dtest=DefenseServiceTest,DefenseModelsTest,DefenseRoutesTest,WebsiteComparisonServiceTest,WebsitePureArmCalibrationTest,SingleInputDetectionTest
sqlite3 db/SQLIA.db "SELECT split_group, COUNT(*) FROM DataTable GROUP BY split_group;"
```

Gate tests assert zero product-service calls for rejection/errors, parameter binding,
persistence and snapshot isolation; a UI `BLOCK` badge alone is insufficient.
SQLite Viewer can inspect the database without CLI commands.

From `sqlia_frontend`:

```sh
flutter analyze
flutter test test/defense_lab_test.dart test/widget_test.dart test/single_input_test.dart test/frozen_performance_test.dart test/external_report_test.dart test/ranking_evaluation_test.dart test/rule_ablation_report_test.dart
flutter build web
```

On external macOS volumes, `._*` files can be mistaken for test code. Maven excludes
them; Flutter commands list real test files explicitly. If package paths refer to
another computer, run `flutter pub get` again.

## 9. Research boundaries and further reading

Mining/training uses Training; configuration/threshold selection uses Validation;
Test is for fixed evaluation. Check overlaps before scoring external data, freeze
models/settings first, and never tune using Test results. An external dataset used
for error analysis is no longer untouched blind-test evidence for revised models.
Incremental improvement requires a separate predict-before-update stream experiment.

Optional research commands, from `sqlia_backend`:

```sh
mvn compile exec:java@research
mvn compile exec:java@research-verify
```

These use separate research artifacts, not website deployment. The research runner
uses a finite Training/Validation grid and source holdouts; it does not score the
existing final Test. Existing output directories/reports may prevent rerunning.
Advanced runners require their own frozen inputs and new output destinations.

- [Architecture, training and evaluation boundaries](sqlia_backend/docs/pure_arm_incremental_architecture.md)
- [System overview](sqlia_backend/docs/project_system_overview.svg)
- [Prediction flow](sqlia_backend/docs/single_sql_prediction_flow.svg)
- [Dataset comparison flow](sqlia_backend/docs/dataset_architecture_comparison_flow.svg)
- [Rule-policy ablation results and tradeoffs](sqlia_backend/docs/rule_ablation_results.md)
- [External generalization error analysis](sqlia_backend/docs/external_generalization_error_analysis.md)
- [Backend learning notes](sqlia_backend/CORE_TECHNOLOGY_LEARNING_NOTES.md)
