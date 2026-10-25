import matplotlib.pyplot as plt
import pandas as pd

data = {
    'Scenario': [
        'S1:\nPure', 
        'S2:\nFP_0.05', 
        'S3:\nFP_0.1', 
        'S4:\nAR_0.1_0.6', 
        'S5:\nAR_0.05_0.6', 
        'S6:\nAR_0.05_0.8', 
        'S7:\nAR_0.1_0.8'
    ],
    'RandomForest': [94.92, 94.95, 94.98, 94.90, 94.90, 94.92, 94.92],
    'CART_Tree': [95.23, 95.19, 95.19, 95.15, 95.15, 95.23, 95.23],
    'XGBoost': [94.48, 94.35, 94.39, 94.31, 94.26, 94.48, 94.48],
    'LightGBM': [92.12, 92.82, 91.62, 91.96, 92.33, 92.12, 92.12],
    'SVM_SMO': [88.00, 87.88, 86.78, 90.05, 90.75, 88.00, 88.00],
    'Naive_Bayes': [81.73, 81.51, 81.91, 81.70, 81.80, 81.73, 81.73]
}

df = pd.DataFrame(data)


plt.figure(figsize=(13, 7), dpi=300) 
plt.grid(True, linestyle='--', alpha=0.5, zorder=1) 

styles = {
    'RandomForest': {'color': '#1f77b4', 'marker': 'o'},   # 藍
    'CART_Tree':    {'color': '#ff7f0e', 'marker': 's'},   # 橘
    'XGBoost':      {'color': '#2ca02c', 'marker': '^'},   # 綠
    'LightGBM':     {'color': '#9467bd', 'marker': 'D'},   # 紫
    'SVM_SMO':      {'color': '#d62728', 'marker': 'v', 'linewidth': 3}, # 紅 (加粗，凸顯逆襲故事)
    'Naive_Bayes':  {'color': '#7f7f7f', 'marker': 'x'}    # 灰
}

for model in df.columns[1:]:
    plt.plot(
        df['Scenario'], 
        df[model], 
        label=model,
        color=styles[model]['color'],
        marker=styles[model]['marker'],
        linewidth=styles[model].get('linewidth', 2), # 預設線寬 2
        markersize=7,
        zorder=3
    )

plt.title('SQL Injection Detection: Accuracy Across 7 Feature Scenarios', fontsize=14, fontweight='bold', pad=15)
plt.xlabel('Experiment Scenarios', fontsize=12, labelpad=10)
plt.ylabel('Total Accuracy (%)', fontsize=12, labelpad=10)

plt.ylim(80, 97) 

plt.legend(loc='lower left', frameon=True, facecolor='white', edgecolor='#e0e0e0', fontsize=11)

plt.tight_layout()

output_filename = 'ml_experiment_results.png'
plt.savefig(output_filename, dpi=300, bbox_inches='tight')
print(f"Saved under {output_filename}")

plt.show()