# 規則修正隔離實驗

## 本次修改範圍

依使用者選擇，實作並執行隔離比較，**沒有替換網站模型**。這不是把已知外部誤報直接
寫成白名單，也沒有用外部標籤調整門檻。

六次訓練與十二組架構／策略計分皆已完成，報告狀態為 `complete`。

本次產物：

- [實驗報告](../data/experiments/rule-ablation-20261025-1427/ablation-report.json)：
  各種子、策略、架構的 Validation 與外部開發診斷，含混淆矩陣、Accuracy、
  Precision、Recall、F1、FPR、Average Precision、來源／長度分組及信賴區間。
- [訓練前協定](../data/experiments/rule-ablation-20261025-1427/protocol.json)：
  固定策略、兩個種子、選門檻方式與限制。
- [外部計分前凍結清單](../data/experiments/rule-ablation-20261025-1427/ablation-frozen-manifest.json)：
  六個 checkpoint 的 hash、規則順序及舊 Validation 門檻。清單不包含外部成績。
- [模型目錄](../data/experiments/rule-ablation-20261025-1427/models/)：
  六個獨立 checkpoint 與訓練細節；沒有登錄為網站部署模型。

## 如何修改

三種策略都使用相同的 24,943 筆舊 Training、5,344 筆舊 Validation，以及
`20261025`、`20261026` 兩個種子。每個種子中，資料順序、ensemble 設定、
挖掘參數及原始候選規則順序均保持一致。

| 策略 | 修改 | 實際保留 Extended rules |
|---|---|---:|
| BASELINE | 原版，不過濾 | 16 |
| NO_NEGATIVE | 排除 antecedent 包含任何「不存在某特徵／token」的規則 | 9 |
| SQL_CONTEXT | NO_NEGATIVE，再要求比較式形狀或至少兩種不同的指定 SQL token | 1 |

SQL_CONTEXT 的精確定義：

- 允許 token 15 的比較式形狀，例如 `1=1` 或 `1=2`；這不是執行 SQL，也不是判斷
  比較式一定為真。
- 或者，規則的正向條件至少含兩種不同 token：
  UNION、SELECT、FROM、WHERE、DROP、INSERT、UPDATE、DELETE、時間函式關鍵字、
  schema/system token、十六進位、ORDER/GROUP BY、CONCAT/CHAR、LIMIT、HAVING、EXEC。
- AND、OR、單獨撇號、括號、等號或註解符號，不足以滿足此策略。
- 合法 SQL 也可能滿足條件，因此此策略不是 SQLi 正確性的保證。

實作在**分類器訓練前**過濾規則：Hybrid 會依剩下的規則維度重新訓練，Pure 也使用
同一份剩餘規則。沒有在已訓練模型上硬刪欄位，避免分類器 schema 不一致。

這次是在原有 capped、已選出的規則上過濾，**不補回候選規則名額**。因此它回答的是
「目前模型對這些規則有多依賴」，不是「最佳受限挖掘器可以達到多少分」。
SQL_CONTEXT 只剩一條比較式規則，不能當作完整 SQL 語法模型。

## 結果：誤報能大幅下降，但不是沒有代價

以下使用種子 `20261025`，所有門檻均只由舊 Validation 選出。
外部資料為先前已揭露標籤的同一批 101,289 筆，現在僅作**開發診斷**。

### 外部開發診斷

| 架構／策略 | Accuracy | Precision | Recall | F1 | FPR | AP |
|---|---:|---:|---:|---:|---:|---:|
| Pure 原版 | 61.67% | 57.54% | 98.91% | 72.76% | 78.26% | 60.22% |
| Pure NO_NEGATIVE | **92.40%** | **95.74%** | 89.27% | **92.40%** | **4.26%** | **94.64%** |
| Pure SQL_CONTEXT | 73.07% | 99.996% | **47.95%** | 64.82% | 0.0020% | 74.88% |
| Hybrid 原版 | 61.03% | 57.12% | 98.98% | 72.44% | 79.67% | 57.10% |
| Hybrid NO_NEGATIVE | **87.73%** | **84.03%** | 94.19% | **88.82%** | **19.20%** | 84.42% |
| Hybrid SQL_CONTEXT | 69.86% | 65.00% | 90.46% | 75.65% | 52.22% | **87.38%** |

不要只看最佳欄位：Hybrid SQL_CONTEXT 的 AP 雖較 NO_NEGATIVE 高，但舊 Validation
選出的 operating point 在外部仍有 52.22% FPR，不能拿 AP 代替實際攔截品質。

### NO_NEGATIVE 的實際取捨

| 架構／策略 | TP | FP | TN | FN |
|---|---:|---:|---:|---:|
| Pure 原版 | 51,839 | 38,252 | 10,627 | 571 |
| Pure NO_NEGATIVE | 46,789 | 2,081 | 46,798 | 5,621 |
| Hybrid 原版 | 51,878 | 38,942 | 9,937 | 532 |
| Hybrid NO_NEGATIVE | 49,367 | 9,385 | 39,494 | 3,043 |

- Pure 少了 **36,171 筆誤報**，但多了 **5,050 筆漏報**。
- Hybrid 少了 **29,557 筆誤報**，但多了 **2,511 筆漏報**。
- 更嚴格的 Pure SQL_CONTEXT 雖只剩一筆 FP，卻漏掉 27,279 筆攻擊；
  不能把接近零誤報包裝成成功修好模型。

這個受控比較支持「目前負向規則對外部誤報有重大影響」，但不是證明所有負向規則
都應刪除，也不是證明這個策略已適合實際部署。

### 第二個種子與穩定性

不能只報比較好的種子。Hybrid 兩種子的外部開發診斷如下：

| 策略 | 種子 | Accuracy | Recall | F1 | FPR | AP |
|---|---:|---:|---:|---:|---:|---:|
| BASELINE | 20261025 | 61.03% | 98.98% | 72.44% | 79.67% | 57.10% |
| BASELINE | 20261026 | 60.56% | 99.01% | 72.21% | 80.67% | 56.81% |
| NO_NEGATIVE | 20261025 | 87.73% | 94.19% | 88.82% | 19.20% | 84.42% |
| NO_NEGATIVE | 20261026 | 83.50% | 94.45% | 85.56% | 28.23% | 78.44% |
| SQL_CONTEXT | 20261025 | 69.86% | 90.46% | 75.65% | 52.22% | 87.38% |
| SQL_CONTEXT | 20261026 | 65.65% | 92.05% | 73.50% | 62.66% | 86.42% |

NO_NEGATIVE 的改善方向一致，但 Hybrid FPR 仍相差約 9.03 個百分點，
不能宣稱分類器對種子已穩健。更嚴格策略在兩種子下都不是較佳 operating point。

Pure 在兩種子下的各項成績完全一致；在相同資料與固定挖掘設定下，其規則及最高
confidence 計分不依賴分類器的隨機種子。這不是兩個獨立資料來源的重複驗證。

### 舊 Validation 沒有同步變好

| 架構／策略 | 舊 Validation F1 | 舊 Validation Recall | 舊 Validation FPR |
|---|---:|---:|---:|
| Pure 原版 | 98.36% | 98.53% | 0.94% |
| Pure NO_NEGATIVE | 96.62% | 94.43% | 0.54% |
| Pure SQL_CONTEXT | 77.18% | 63.19% | 0.28% |
| Hybrid 原版 | 98.31% | 98.74% | 1.11% |
| Hybrid NO_NEGATIVE | 97.41% | 96.72% | 0.97% |
| Hybrid SQL_CONTEXT | 93.83% | 90.93% | 1.51% |

原版在舊 Validation 仍較高分，再次顯示舊來源不能完整代表外部正常輸入。
此外，Train/Validation 有 690 個共享啟發式模板，這不是獨立模板泛化驗證。

### 信賴區間的解讀

種子 `20261025`、NO_NEGATIVE 的 200 次模板群 bootstrap 95% 區間：

| 架構 | 外部 FPR 區間 | 外部 Recall 區間 |
|---|---:|---:|
| Pure | 4.08–4.40% | 88.58–89.78% |
| Hybrid | 18.84–19.51% | 93.91–94.43% |

這些是固定資料來源上的抽樣區間，不包含未知來源偏差、標籤錯誤或策略搜尋的不確定性；
也不是以模型差值計算的 paired 顯著性檢定。

## 為什麼先不替換網站

1. 使用者選擇的是隔離實驗，並未授權部署。
2. NO_NEGATIVE 是值得繼續開發的候選，但仍有實際漏報，Hybrid 的誤報率也仍偏高。
3. 這份外部資料已用於錯誤分析與策略設計，不能拿改進後的分數當成首次盲測成果。
4. 本次沒有加入新來源資料、人工改標、讀舊 final Test 或實作串流增量驗證。
5. 網站預設訓練仍走 BASELINE，既有 checkpoint、首次外部報告及部署設定保持不變。

## 下一步應做什麼

建議以 NO_NEGATIVE 作為**下一輪開發比較基準，而非自動部署勝出者**：

1. 蒐集並稽核貼近實際使用情境的正常與攻擊資料，特別是長文、多語、HTML、
   合法 SQL 及帶常見符號的正常輸入。
2. 按來源／模板隔離新的開發集與未使用 final holdout。不能從這份已看過結果的
   外部資料再切一份，就聲稱那是獨立的新盲測。
3. 在獨立開發資料比較受限挖掘、數值特徵基準及規則／數值特徵消融，
   研究如何找回 NO_NEGATIVE 遺失的攻擊，而不是直接恢復所有高誤報規則。
4. 在取得可容忍 FPR 與最低 Recall 的產品要求後，只用 Validation 決定門檻。
   沒有可行門檻時應回到資料與特徵，不拿測試集調整。
5. 最後才在新的未使用 holdout 評估，再決定是否部署。增量效果另用先預測、
   後揭露標籤更新的串流協定驗證。

## 重現與保護措施

從 backend 目錄執行，輸出目錄必須尚不存在且不在歷史實驗目錄內：

```sh
mvn -q -Dtest=RuleAblationTest,GeneralizationPipelineTest,FrozenCheckpointTest,WebsitePureArmCalibrationTest,ExternalErrorAnalysisTest,WebsiteComparisonServiceTest,FeaturePipelineTest test
mvn -q exec:java@rule-ablation \
  -Dexec.args="data/experiments/generalization-20261025-1322 data/experiments/rule-ablation-NEW"
```

- 首先核對原始 manifest、development report、資料 export 及 checkpoint 的 hash。
- 舊 Train/Validation 的 hash 必須與原 development report 一致。
- 每個模型儲存後重新載入；逐筆核對全部 Validation 分數完全相等。
- 原版兩個種子的 Validation 門檻與混淆矩陣必須重現歷史結果。
- 先儲存全部六個模型與門檻，再讀外部 export 計分。
- 第一種子原版的外部門檻、TP、FP、TN、FN 必須重現首次外部報告。
- 完成前再次檢查輸入、凍結清單與輸出 checkpoint 的 hash。
- 失敗會明確保存 failed 診斷，既有目錄不覆寫，沒有靜默改用預設策略。

針對性回歸共 32 項通過。涵蓋策略條件、真實 tokenizer 的 token ID、空規則 schema、
checkpoint 維度與重載、原版數值比對、缺少 provenance hash 時失敗、拒絕覆寫，
以及原有網站 Pure 門檻校準與比較行為。

## 網站唯讀展示

網站入口為 **External generalization → Rule ablation**。先選比較模式，再進入新分頁，
開啟保存的實驗。**Frozen evaluation** 仍保留首次凍結評估，不與新實驗合併。

詳情可切換 Pure ARM／Hybrid，展示：

- 三種策略與兩個種子的六項指標，沒有只挑最好種子。
- FPR／Recall 並列比較，以及同種子相對 Baseline 的 FP／FN 筆數差。
- 各試驗的混淆矩陣、舊 Validation 指標、95% 區間與有效／未定義 bootstrap 次數。
- 原精度門檻、訓練與評估筆數、保留規則數、checkpoint 路徑／hash。
- 實驗協定、限制、輸入 hash，以及原始 JSON 下載。

新增的排名評估使用獨立 `rule-ablation-ranking-*` 衍生報告，不覆寫上述歷史實驗。
三策略／兩種子的表格另列 ROC-AUC；展開 Details 可查看 ROC-AUC 與 AP 的
template-cluster bootstrap 95% 區間、ROC／PR 曲線，以及原 Validation 門檻的橘色標記。
ROC-AUC 以原始分數計算，同分的正負樣本對給半分；缺少任一類別時為 Undefined。
AP 不等於梯形積分 PR-AUC。曲線最多顯示 401 點，但指標使用全部分數，
沒有以外部結果重選門檻。舊報告缺少曲線時明確顯示 unavailable，不補零。
`rankingDerivation` 保存來源 run、原始 checkpoint 相對路徑的基準目錄與輸入 hash；
這些報告不是另外訓練的新模型，也不是新盲測。

已完成的衍生報告為
[排名評估 JSON](../data/experiments/rule-ablation-ranking-20261025-1554/ablation-report.json)。
ROC-AUC（0–1）如下；Pure 兩種子結果相同，並非兩次獨立來源驗證：

| 策略 | Pure：兩種子 | Hybrid：20261025 | Hybrid：20261026 |
|---|---:|---:|---:|
| BASELINE | 0.667193 | 0.596758 | 0.592001 |
| NO_NEGATIVE | 0.944103 | 0.888955 | 0.843837 |
| SQL_CONTEXT | 0.739744 | 0.863684 | 0.858549 |

全部 17 組評分（首次外部五架構＋消融十二組）都重現原有總體指標與混淆計數；
原始報告、manifest、外部 export 與使用的 checkpoint hash 均核對不變。
較高 ROC-AUC 只反映排名能力，不能取代既有門檻下的 FPR／Recall，也不表示已改善
網站部署模型。ROC-AUC 區間使用 200 次模板群組 bootstrap，不是配對差異檢定。

英文警示固定存在於內容開頭：**Development experiment — not an independent blind test**
與 **Research only — not deployed. Website detection remains unchanged.**
這是報告展示，不是模型部署或額外訓練功能。

API 使用 `/api/experiments/rule-ablation/reports` 及其 `/{id}`、
`/{id}/download` 三條 GET 路由。只讀已存報告，不載入模型；完成報告缺少指標、
區間、種子／策略配對、hash 或門檻不一致時明確報錯。running／failed 報告不顯示
未完成指標，空列表、載入失敗與下載失敗均有提示。

後端需重建並重新啟動才會註冊新路由，前端需使用更新後的版本。正式偵測模型、
既有首次外部報告與本次實驗檔案都沒有因展示功能而改寫。

展示功能驗證：15 項後端報告／HTTP 路由測試、20 項 Flutter 模型／repository／widget
回歸測試通過，目標 Dart 檔案分析無診斷，production web build 成功。Flutter 的
`flutter analyze` 在此含非 ASCII 路徑環境發生 LSP 訊息截斷，改用
`dart analyze --format=machine` 完成相同目標檔案檢查。
瀏覽器已檢查實際保存報告的入口、分頁、兩種子表格、比較圖與差值，並成功熱更新，
未取得 Dart 執行期錯誤。瀏覽器使用臨時唯讀報告 fixture；正式 Java 路由則由測試啟動
真實 Javalin 驗證，不將此描述為正式網站伺服器已重新啟動或部署完成。
