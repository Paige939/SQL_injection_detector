import os
import matplotlib.pyplot as plt
import pandas as pd

output_dir = 'experiment_plots'
if not os.path.exists(output_dir):
    os.makedirs(output_dir)

scenarios = ['S1:\nPure', 'S2:\nFP_0.05', 'S3:\nFP_0.1', 'S4:\nAR_0.6', 'S5:\nAR_0.05', 'S6:\nAR_0.8', 'S7:\nAR_0.1']

experiment_data = {
    # === Class 1 (Malicious) ===
    'c1_precision': {
        'RandomForest': [0.9568, 0.9577, 0.9586, 0.9564, 0.9564, 0.9568, 0.9568],
        'CART_Tree':    [0.9593, 0.9601, 0.9597, 0.9575, 0.9579, 0.9593, 0.9593],
        'XGBoost':      [0.9439, 0.9366, 0.9362, 0.9345, 0.9325, 0.9439, 0.9439],
        'LightGBM':     [0.8975, 0.9082, 0.8976, 0.8930, 0.9099, 0.8975, 0.8975],
        'SVM_SMO':      [0.8455, 0.8447, 0.8545, 0.8434, 0.8587, 0.8455, 0.8455],
        'Naive_Bayes':  [0.8247, 0.8092, 0.8137, 0.8252, 0.8424, 0.8247, 0.8247]
    },
    'c1_recall': {
        'RandomForest': [0.9045, 0.9045, 0.9045, 0.9045, 0.9045, 0.9045, 0.9045],
        'CART_Tree':    [0.9106, 0.9088, 0.9092, 0.9101, 0.9097, 0.9106, 0.9106],
        'XGBoost':      [0.9058, 0.9101, 0.9114, 0.9110, 0.9119, 0.9058, 0.9058],
        'LightGBM':     [0.8901, 0.8980, 0.8749, 0.8910, 0.8815, 0.8901, 0.8901],
        'SVM_SMO':      [0.8294, 0.8267, 0.7777, 0.9001, 0.8997, 0.8294, 0.8294], # 💡 S4, S5 顯著暴衝
        'Naive_Bayes':  [0.6474, 0.6591, 0.6674, 0.6457, 0.6292, 0.6474, 0.6474]
    },
    'c1_f1': {
        'RandomForest': [0.9299, 0.9303, 0.9307, 0.9297, 0.9297, 0.9299, 0.9299],
        'CART_Tree':    [0.9343, 0.9337, 0.9338, 0.9332, 0.9332, 0.9343, 0.9343],
        'XGBoost':      [0.9244, 0.9231, 0.9237, 0.9226, 0.9221, 0.9244, 0.9244],
        'LightGBM':     [0.8938, 0.9031, 0.8861, 0.8920, 0.8955, 0.8938, 0.8938],
        'SVM_SMO':      [0.8374, 0.8356, 0.8143, 0.8708, 0.8787, 0.8374, 0.8374],
        'Naive_Bayes':  [0.7254, 0.7265, 0.7333, 0.7245, 0.7204, 0.7254, 0.7254]
    },
    # === Class 0 (Benign) ===
    'c0_precision': {
        'RandomForest': [0.9451, 0.9451, 0.9451, 0.9450, 0.9450, 0.9451, 0.9451],
        'CART_Tree':    [0.9484, 0.9475, 0.9477, 0.9481, 0.9479, 0.9484, 0.9484],
        'XGBoost':      [0.9454, 0.9475, 0.9482, 0.9479, 0.9483, 0.9454, 0.9454],
        'LightGBM':     [0.9351, 0.9398, 0.9268, 0.9354, 0.9309, 0.9351, 0.9351],
        'SVM_SMO':      [0.8998, 0.8984, 0.8747, 0.9382, 0.9387, 0.8998, 0.8998],
        'Naive_Bayes':  [0.8143, 0.8177, 0.8215, 0.8137, 0.8086, 0.8143, 0.8143]
    },
    'c0_recall': {
        'RandomForest': [0.9758, 0.9763, 0.9768, 0.9755, 0.9755, 0.9758, 0.9758],
        'CART_Tree':    [0.9771, 0.9776, 0.9773, 0.9760, 0.9763, 0.9771, 0.9771],
        'XGBoost':      [0.9680, 0.9634, 0.9631, 0.9621, 0.9608, 0.9680, 0.9680],
        'LightGBM':     [0.9397, 0.9461, 0.9407, 0.9366, 0.9482, 0.9397, 0.9397],
        'SVM_SMO':      [0.9100, 0.9097, 0.9214, 0.9007, 0.9121, 0.9100, 0.9100],
        'Naive_Bayes':  [0.9183, 0.9077, 0.9092, 0.9188, 0.9301, 0.9183, 0.9183]
    },
    'c0_f1': {
        'RandomForest': [0.9602, 0.9604, 0.9607, 0.9600, 0.9600, 0.9602, 0.9602],
        'CART_Tree':    [0.9625, 0.9623, 0.9623, 0.9619, 0.9619, 0.9625, 0.9625],
        'XGBoost':      [0.9566, 0.9554, 0.9556, 0.9550, 0.9545, 0.9566, 0.9566],
        'LightGBM':     [0.9374, 0.9429, 0.9337, 0.9360, 0.9394, 0.9374, 0.9374],
        'SVM_SMO':      [0.9049, 0.9040, 0.8974, 0.9191, 0.9252, 0.9049, 0.9049],
        'Naive_Bayes':  [0.8632, 0.8603, 0.8632, 0.8630, 0.8651, 0.8632, 0.8632]
    },

    'macro_f1': {
        'RandomForest': [0.9450, 0.9454, 0.9457, 0.9449, 0.9449, 0.9450, 0.9450],
        'CART_Tree':    [0.9484, 0.9480, 0.9480, 0.9475, 0.9475, 0.9484, 0.9484],
        'XGBoost':      [0.9405, 0.9393, 0.9396, 0.9383, 0.9383, 0.9405, 0.9405],
        'LightGBM':     [0.9156, 0.9230, 0.9099, 0.9140, 0.9175, 0.9156, 0.9156],
        'SVM_SMO':      [0.8711, 0.8698, 0.8558, 0.8950, 0.9020, 0.8711, 0.8711],
        'Naive_Bayes':  [0.7943, 0.7934, 0.7982, 0.7938, 0.7927, 0.7943, 0.7943]
    }
}

colors = {'RandomForest': '#1f77b4', 'CART_Tree': '#ff7f0e', 'XGBoost': '#2ca02c', 'LightGBM': '#9467bd', 'SVM_SMO': '#d62728', 'Naive_Bayes': '#7f7f7f'}
markers = {'RandomForest': 'o', 'CART_Tree': 's', 'XGBoost': '^', 'LightGBM': 'D', 'SVM_SMO': 'v', 'Naive_Bayes': 'x'}

# 定義 7 張圖的個別屬性： { 數據Key : ( 圖表標題, Y軸標籤, 存檔檔名 ) }
plot_configs = {
    'c1_precision': ('Class 1 (Malicious) - Precision Trend', 'Precision', '1_class1_precision.png'),
    'c1_recall':    ('Class 1 (Malicious) - Recall Trend', 'Recall', '2_class1_recall.png'),
    'c1_f1':        ('Class 1 (Malicious) - F1-Score Trend', 'F1-Score', '3_class1_f1.png'),
    'c0_precision': ('Class 0 (Benign) - Precision Trend', 'Precision', '4_class0_precision.png'),
    'c0_recall':    ('Class 0 (Benign) - Recall Trend', 'Recall', '5_class0_recall.png'),
    'c0_f1':        ('Class 0 (Benign) - F1-Score Trend', 'F1-Score', '6_class0_f1.png'),
    'macro_f1':     ('Overall Performance - Macro Average F1-Score', 'Macro F1-Score', '7_macro_f1_overall.png')
}

print("Satrt drawing 7 pictures...")

for key, config in plot_configs.items():
    title, ylabel, filename = config
    
    #DataFrame
    df = pd.DataFrame(experiment_data[key])
    df.insert(0, 'Scenario', scenarios)
    

    plt.figure(figsize=(11, 6), dpi=300)
    plt.grid(True, linestyle='--', alpha=0.5, zorder=1)
    
    for model in df.columns[1:]:
        lw = 3.5 if model == 'SVM_SMO' else 2.0
        
        plt.plot(
            df['Scenario'], 
            df[model], 
            label=model,
            color=colors[model],
            marker=markers[model],
            linewidth=lw,
            markersize=7,
            zorder=3
        )
    
    plt.title(title, fontsize=13, fontweight='bold', pad=15)
    plt.xlabel('Experiment Scenarios', fontsize=11, labelpad=10)
    plt.ylabel(ylabel, fontsize=11, labelpad=10)
    
    current_min = df.iloc[:, 1:].min().min()
    current_max = df.iloc[:, 1:].max().max()
    plt.ylim(current_min - 0.03, min(current_max + 0.02, 1.01))
    

    plt.legend(loc='lower left', frameon=True, facecolor='white', edgecolor='#e0e0e0', fontsize=9)
    plt.tight_layout()
    
    save_path = os.path.join(output_dir, filename)
    plt.savefig(save_path, dpi=300, bbox_inches='tight')
    plt.close() 
    
    print(f" Save as {save_path}")

print(f"\n7 Picture drawing complete! Store in: {output_dir}！")